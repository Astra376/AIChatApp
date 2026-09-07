import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { describe, expect, it, vi, afterEach } from "vitest";
import type { Env, RequestContext } from "../../env";
import { DEFAULT_NOTIFICATION_SETTINGS, dismissNotifications, getFollowState, getNotificationSettings,
  listNotifications, notifyCharacterPublished, processRecommendations, setFollow, updateNotificationSettings, updatePresence } from "./index";
import { deliverNotificationEmails, unsubscribeEmail, unsubscribeToken } from "./email";
import { dueOfflineStage, latestOfflineTranscript, offlineSchedule, processOfflineMessages, saveOfflineMessage } from "../chat/offline";

function database() {
  const sqlite = new DatabaseSync(":memory:");
  for (const file of ["0001_initial.sql", "0002_conversation_streaming.sql", "0003_offline_messaging.sql", "0007_notifications.sql"])
    sqlite.exec(readFileSync(new URL(`../../db/migrations/${file}`, import.meta.url), "utf8"));
  const now = Date.now();
  for (const id of ["user", "creator", "other", "third", "fourth"]) {
    sqlite.prepare("INSERT INTO users VALUES (?, ?, ?, 1, 1, NULL)").run(id, id, `${id}@example.com`);
    sqlite.prepare("INSERT INTO profiles VALUES (?, ?, NULL, 1, 1)").run(id, id);
  }
  sqlite.exec(`INSERT INTO characters (id,owner_user_id,name,tagline,description,system_prompt,visibility,last_active_at,created_at,updated_at)
    VALUES ('character','creator','Astrid','A new adventure','description','You are Astrid','public',1,1,1);
    INSERT INTO conversations (id,owner_user_id,character_id,updated_at,started_at,version) VALUES ('chat','user','character',1,1,0);`);
  function statement(sql: string, args: any[] = []) {
    return {
      bind(...parameters: any[]) { return statement(sql, parameters); },
      async run() { const result = sqlite.prepare(sql).run(...args); return {success: true, meta: {changes: Number(result.changes)}}; },
      async first() { return sqlite.prepare(sql).get(...args) ?? null; },
      async all() { return {results: sqlite.prepare(sql).all(...args)}; }
    };
  }
  const env = {DB: {prepare: statement, async batch(statements: Array<ReturnType<typeof statement>>) {
    sqlite.exec("BEGIN");
    try { const results = []; for (const item of statements) results.push(await item.run()); sqlite.exec("COMMIT"); return results; }
    catch (error) { sqlite.exec("ROLLBACK"); throw error; }
  }}, SESSION_HMAC_SECRET: "test-only-signing-secret", R2_PUBLIC_BASE_URL: "https://example.com/v1/assets"} as unknown as Env;
  const context = (userId = "user") => ({env, user: {userId}, request: new Request("https://example.com"), url: new URL("https://example.com"), params: {}} as RequestContext);
  const addMessage = (position: number, role = "user", text = `message ${position}`) => sqlite.prepare(
    "INSERT INTO messages (id,conversation_id,position,role,content,edited,created_at,updated_at) VALUES (?,'chat',?,?,?,0,?,?)")
    .run(`message${position}`,position,role,text,now - 20 * 60_000,now - 20 * 60_000);
  const candidate = (anchor = "message0") => ({id: "chat", owner_user_id: "user", character_id: "character", version: 0,
    name: "Astrid", system_prompt: "", avatar_url: null, anchor_id: anchor, anchor_at: now - 20 * 60_000, user_count: 51, delivered_stage: -1});
  const addNotification = (id: string, owner = "user", kind = "chat") => sqlite.prepare(
    "INSERT INTO notifications (id,user_id,kind,title,body,conversation_id,created_at,updated_at,dedup_key) VALUES (?,?,?,'Astrid sent you a message','Hello','chat',?,?,?)").run(id,owner,kind,now,now,id);
  return {sqlite,env,context,now,addMessage,candidate,addNotification};
}
afterEach(() => { vi.unstubAllGlobals(); });

describe("notification preferences and activity", () => {
  it("defaults on, updates individual toggles, and rejects invalid settings", async () => {
    const {env,context,sqlite} = database();
    try {
      expect(await getNotificationSettings(env,"user")).toEqual(DEFAULT_NOTIFICATION_SETTINGS);
      expect(await updateNotificationSettings(context(),{emailEnabled:false})).toEqual({...DEFAULT_NOTIFICATION_SETTINGS,emailEnabled:false});
      await expect(updateNotificationSettings(context(),{pushEnabled:"yes"} as any)).rejects.toMatchObject({code:"INVALID_SETTINGS"});
    } finally { sqlite.close(); }
  });
  it("marks the owner's current chat read and rejects another user's conversation", async () => {
    const {context,sqlite,addNotification} = database();
    try {
      sqlite.exec("UPDATE conversations SET unread_count=4,has_unread_badge=1"); addNotification("n");
      await expect(updatePresence(context("other"),"chat")).rejects.toMatchObject({code:"CONVERSATION_NOT_FOUND"});
      await updatePresence(context(),"chat");
      expect(sqlite.prepare("SELECT unread_count FROM conversations").get()?.unread_count).toBe(0);
      expect(sqlite.prepare("SELECT read_at FROM notifications").get()?.read_at).not.toBeNull();
    } finally { sqlite.close(); }
  });
  it("clears individual/all activity without deleting quotas, dedup records, or another user's items", async () => {
    const {context,sqlite,addNotification} = database();
    try {
      addNotification("one"); addNotification("two"); addNotification("private","other");
      await dismissNotifications(context(),"one");
      expect((await listNotifications(context(),0,50)).items.map(row=>row.id)).toEqual(["two"]);
      await dismissNotifications(context());
      expect((await listNotifications(context(),0,50)).items).toEqual([]);
      expect((await listNotifications(context("other"),0,50)).items).toHaveLength(1);
      expect(sqlite.prepare("SELECT COUNT(*) AS count FROM notifications").get()?.count).toBe(3);
    } finally { sqlite.close(); }
  });
});

describe("offline message scheduling", () => {
  it("adapts to engagement, collapses missed intervals, and ends its finite schedule", () => {
    expect(dueOfflineStage(50,15*60_000)).toBe(0);
    expect(dueOfflineStage(1,15*60_000)).toBeNull();
    expect(dueOfflineStage(12,3_600_000)).toBe(0);
    expect(dueOfflineStage(50,8*86_400_000)).toBe(5);
    expect(dueOfflineStage(50,1000*86_400_000,7)).toBeNull();
    expect(offlineSchedule(1)[0]).toBe(86_400_000);
  });
  it("reads latest selected transcript and appends past position100 without duplicate writes", async () => {
    const {env,sqlite,addMessage,now,candidate} = database();
    try {
      for(let i=0;i<=100;i++) addMessage(i,i%2?"assistant":"user");
      sqlite.exec("INSERT INTO assistant_regenerations VALUES ('regen','message99','selected reply',1); UPDATE messages SET selected_regeneration_id='regen' WHERE id='message99'");
      const transcript=await latestOfflineTranscript(env,"chat");
      expect(transcript).toHaveLength(30); expect(transcript[0].content).toBe("message 71"); expect(transcript[28].content).toBe("selected reply");
      expect(await saveOfflineMessage(env,candidate("message100"),0,"A follow-up",now)).toBe(true);
      expect(await saveOfflineMessage(env,candidate("message100"),0,"duplicate",now)).toBe(false);
      expect(sqlite.prepare("SELECT MAX(position) AS position,COUNT(*) AS count FROM messages").get()).toMatchObject({position:101,count:102});
      expect(sqlite.prepare("SELECT unread_count,version,active_run_id FROM conversations").get()).toMatchObject({unread_count:1,version:1,active_run_id:null});
      expect(sqlite.prepare("SELECT COUNT(*) AS count FROM notifications").get()?.count).toBe(1);
    } finally { sqlite.close(); }
  });
  it.each(["active run","new message","edit","foreground","disabled","private"])("discards stale generation after %s without locking the chat",async reason=>{
    const {env,sqlite,addMessage,now,candidate}=database();
    try {
      addMessage(0);
      if(reason==="active run") sqlite.prepare("UPDATE conversations SET active_run_id='foreground',active_run_expires_at=?").run(now+75_000);
      if(reason==="new message") addMessage(1);
      if(reason==="edit") sqlite.exec("UPDATE conversations SET version=1");
      if(reason==="foreground") sqlite.prepare("INSERT INTO user_presence VALUES ('user',?,NULL)").run(now);
      if(reason==="disabled") sqlite.exec("INSERT INTO notification_settings (user_id,chat_messages_enabled) VALUES ('user',0)");
      if(reason==="private") sqlite.exec("UPDATE characters SET visibility='private'");
      expect(await saveOfflineMessage(env,candidate(),0,"stale",now)).toBe(false);
      expect(sqlite.prepare("SELECT COUNT(*) AS count FROM notifications").get()?.count).toBe(0);
      expect(sqlite.prepare("SELECT unread_count FROM conversations").get()?.unread_count).toBe(0);
    }finally{sqlite.close();}
  });
  it("enforces the daily quota even after all activity was cleared",async()=>{
    const {env,sqlite,context,addMessage,candidate,addNotification,now}=database();
    try{
      addMessage(0); for(let i=0;i<3;i++)addNotification(`old${i}`);
      await dismissNotifications(context());
      expect(await saveOfflineMessage(env,candidate(),0,"too many",now)).toBe(false);
    }finally{sqlite.close();}
  });
  it("runs candidate SQL without calling generation before a low-engagement chat is due",async()=>{
    const {env,sqlite,addMessage}=database(); const fetch=vi.fn();vi.stubGlobal("fetch",fetch);
    try{addMessage(0);await processOfflineMessages(env);expect(fetch).not.toHaveBeenCalled();}
    finally{sqlite.close();}
  });
});

describe("social notifications",()=>{
  it("deduplicates follows and groups third/subsequent followers into one activity item",async()=>{
    const {context,sqlite}=database();
    try{
      for(const follower of ["user","other","third","fourth"])await setFollow(context(follower),"creator",true);
      await setFollow(context("fourth"),"creator",true);
      expect(await getFollowState(context("user"),"creator")).toEqual({following:true,followerCount:4,followingCount:0});
      expect(await getFollowState(context("user"),"user")).toEqual({following:false,followerCount:0,followingCount:1});
      const items=(await listNotifications(context("creator"),0,50)).items;
      expect(items).toHaveLength(3);expect(items.find(item=>item.title==="New followers")?.count).toBe(2);
      await setFollow(context("user"),"creator",false);expect((await getFollowState(context("user"),"creator")).following).toBe(false);
    }finally{sqlite.close();}
  });
  it("respects follower preferences and publishes public characters once to followers",async()=>{
    const {env,context,sqlite}=database();
    try{
      await updateNotificationSettings(context("creator"),{followersEnabled:false});await setFollow(context(),"creator",true);
      expect((await listNotifications(context("creator"),0,50)).items).toHaveLength(0);
      await notifyCharacterPublished(env,"character");await notifyCharacterPublished(env,"character");
      expect((await listNotifications(context(),0,50)).items).toHaveLength(1);
      sqlite.exec("UPDATE characters SET visibility='private'");await notifyCharacterPublished(env,"character");
      expect((await listNotifications(context("other"),0,50)).items).toHaveLength(0);
    }finally{sqlite.close();}
  });
  it("recommends relevant new characters once to dormant users",async()=>{
    const {env,context,sqlite,now}=database();
    try{
      sqlite.prepare("INSERT INTO user_presence VALUES ('user',?,NULL)").run(now-5*86_400_000);
      sqlite.prepare("UPDATE characters SET created_at=?").run(now-86_400_000);
      await processRecommendations(env,now);await processRecommendations(env,now);
      expect((await listNotifications(context(),0,50)).items).toHaveLength(1);
      expect((await listNotifications(context("other"),0,50)).items).toHaveLength(0);
    }finally{sqlite.close();}
  });
});

describe("email delivery",()=>{
  it("does not pretend to deliver without configured credentials",async()=>{
    const {env,sqlite}=database();const fetch=vi.fn();vi.stubGlobal("fetch",fetch);
    try{await deliverNotificationEmails(env);expect(fetch).not.toHaveBeenCalled();}finally{sqlite.close();}
  });
  it("verifies recipient-specific unsubscribe tokens and rejects tampering",async()=>{
    const {env,sqlite}=database();
    try{
      const token=await unsubscribeToken(env,"user");expect(await unsubscribeEmail(env,token+"tampered")).toBe(false);
      expect(await unsubscribeEmail(env,token)).toBe(true);expect((await getNotificationSettings(env,"user")).emailEnabled).toBe(false);
      expect((await getNotificationSettings(env,"other")).emailEnabled).toBe(true);
    }finally{sqlite.close();}
  });
  it("honors disable settings and marks sent only after provider confirmation",async()=>{
    const {env,context,sqlite,now,addNotification}=database();
    Object.assign(env,{RESEND_API_KEY:"test-only",NOTIFICATION_EMAIL_FROM:"Meek <test@example.com>"});addNotification("email");
    const fetch=vi.fn().mockResolvedValue(new Response(JSON.stringify({id:"fake-provider-id"}),{status:200}));vi.stubGlobal("fetch",fetch);
    try{
      await updateNotificationSettings(context(),{emailEnabled:false});await deliverNotificationEmails(env,now);expect(fetch).not.toHaveBeenCalled();
      await updateNotificationSettings(context(),{emailEnabled:true});await deliverNotificationEmails(env,now);expect(fetch).toHaveBeenCalledOnce();
      expect(fetch.mock.calls[0][1].headers["Idempotency-Key"]).toBe("notification/email");
      expect(sqlite.prepare("SELECT emailed_at FROM notifications").get()?.emailed_at).toBe(now);
      await deliverNotificationEmails(env,now);expect(fetch).toHaveBeenCalledOnce();
    }finally{sqlite.close();}
  });
});
