import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Env, RequestContext } from "../env";
import { queueImage, imageJobStatus } from "./images/jobs";
import { storeRemoteImageInR2 } from "../providers/r2";
import { completeChatText } from "../providers/openrouter";
import { autoCreateCharacter, generateEmotionPortraits, getCharacterPsychologyDefaults, normalizeCharacterPsychology,
  readCharacterPsychology, readEmotionPortraits, resumeEmotionPortraits, saveCharacterPsychology } from "./characterPsychology";
vi.mock("./images/jobs", () => ({queueImage:vi.fn(),imageJobStatus:vi.fn()}));
vi.mock("../providers/r2", () => ({storeRemoteImageInR2:vi.fn()}));
vi.mock("../providers/openrouter", () => ({completeChatText:vi.fn()}));
function setup() {
  const db = new DatabaseSync(":memory:");
  db.exec("PRAGMA foreign_keys=ON");
  for (const file of ["0001_initial.sql","0005_character_greeting.sql"]) db.exec(readFileSync(new URL(`../db/migrations/${file}`,import.meta.url),"utf8"));
  db.exec(`INSERT INTO users VALUES ('owner','o','owner@example.com',1,1),('viewer','v','viewer@example.com',1,1);
    INSERT INTO profiles VALUES ('owner','Owner',NULL,1,1);
    INSERT INTO characters(id,owner_user_id,name,tagline,description,system_prompt,visibility,last_active_at,created_at,updated_at,avatar_url,definition_private)
      VALUES ('character','owner','Astrid','','','','public',1,1,1,'https://assets.example/portraits%2Fowner%2Fsource.jpg',1);`);
  function statement(sql:string,args:any[]=[]):any { return {
    bind(...values:any[]) {return statement(sql,values)},
    async run(){const result=db.prepare(sql).run(...args);return {success:true,meta:{changes:Number(result.changes)}}},
    async first(){return db.prepare(sql).get(...args)??null},
    async all(){return {results:db.prepare(sql).all(...args)}}
  }; }
  const env={DB:{prepare:statement,async batch(statements:any[]){db.exec("BEGIN");try{const results=[];for(const s of statements)results.push(await s.run());db.exec("COMMIT");return results}catch(e){db.exec("ROLLBACK");throw e}}},
    R2_PUBLIC_BASE_URL:"https://assets.example", ASSETS:{head:vi.fn(async()=>({}))},OPENROUTER_PROVIDERS:"venice",OPENROUTER_ULTRA_MODEL:"deepseek/deepseek-v4-pro-0813"} as unknown as Env;
  const context=(id="owner")=>({env,user:{userId:id}} as RequestContext);
  return {db,env,context};
}
beforeEach(()=>vi.clearAllMocks());
describe("character psychology and durable expressions",()=>{
  it("normalizes all emotion axes and rejects non-finite scores",()=>{
    const defaults=normalizeCharacterPsychology({emotions:{joy:900,anger:-5,fear:NaN},personality:{warmth:83.6},psychology:{beliefs:["  protect home ",2],secretDesires:["be understood"]}});
    expect(defaults.emotions.joy).toBe(100);expect(defaults.emotions.anger).toBe(0);expect(defaults.emotions.fear).toBe(30);
    expect(defaults.personality.warmth).toBe(84);expect(defaults.psychology.beliefs).toEqual(["protect home"]);
    expect(defaults.psychology.secretDesires).toEqual(["be understood"]);
  });
  it("keeps private character defaults out of another user's view and blocks edits",async()=>{
    const {db,env,context}=setup();try{
      await saveCharacterPsychology(context(),"character",{psychology:{cornerstone:"My sister's promise",secretDesires:["Leave the city"]}});
      expect((await getCharacterPsychologyDefaults(env,"character"))?.psychology.cornerstone).toBe("My sister's promise");
      await expect(readCharacterPsychology(context("viewer"),"character")).rejects.toMatchObject({status:403});
      await expect(saveCharacterPsychology(context("viewer"),"character",{})).rejects.toMatchObject({status:403});
      expect((await readCharacterPsychology(context(),"character")).psychology.secretDesires).toEqual(["Leave the city"]);
    }finally{db.close()}
  });
  it("gates new advanced direction but preserves existing detail after Ultra expires", async () => {
    const {db,env,context}=setup();try {
      await saveCharacterPsychology(context(),"character",{psychology:{cornerstone:"A promise"}});
      await expect(saveCharacterPsychology(context(),"character",{advancedDefinition:"A complex growth arc"})).rejects.toMatchObject({code:"ULTRA_REQUIRED"});
      const saved=normalizeCharacterPsychology({advancedDefinition:"Existing paid detail",psychology:{cornerstone:"A promise"}});
      db.prepare("UPDATE character_psychology SET defaults_json=? WHERE character_id='character'").run(JSON.stringify(saved));
      await saveCharacterPsychology(context(),"character",{psychology:{cornerstone:"An evolving promise"}});
      expect((await getCharacterPsychologyDefaults(env,"character"))?.advancedDefinition).toBe("Existing paid detail");
      await saveCharacterPsychology(context(),"character",{...saved,psychology:{cornerstone:"One more edit"}});
      expect((await getCharacterPsychologyDefaults(env,"character"))?.advancedDefinition).toBe("Existing paid detail");
      await expect(saveCharacterPsychology(context(),"character",{advancedDefinition:""})).rejects.toMatchObject({code:"ULTRA_REQUIRED"});
    } finally {db.close()}
  });
  it("builds one neutral body before expressions and preserves durable requests", async () => {
    const {db,env,context}=setup();try {
      vi.mocked(queueImage).mockImplementation(async (_env,_input,id) => ({provider:"openrouter",id:id!}));
      vi.mocked(imageJobStatus).mockResolvedValue({status:"running"});
      await generateEmotionPortraits(context(),"character");
      expect(queueImage).toHaveBeenCalledTimes(1);
      const neutralInput=vi.mocked(queueImage).mock.calls[0][1];
      expect(neutralInput.image).toMatchObject({background:"transparent",aspectRatio:"2:3",referenceImageUrl:"https://assets.example/portraits%2Fowner%2Fsource.jpg"});
      expect(neutralInput.outputKey).toMatch(/^character-art\/owner\/.+_neutral\.png$/);
      await Promise.all([resumeEmotionPortraits(env),resumeEmotionPortraits(env)]);
      expect(queueImage).toHaveBeenCalledTimes(1);
      vi.mocked(env.ASSETS.head).mockResolvedValue({customMetadata:{alpha:"verified"}} as any);
      vi.mocked(imageJobStatus).mockImplementation(async (_env,job) => {
        const call=vi.mocked(queueImage).mock.calls.find(call=>call[2]===job.id)!;
        return {status:"completed",imageUrl:`https://assets.example/${encodeURIComponent(call[1].outputKey)}`};
      });
      await resumeEmotionPortraits(env); // neutral is published first
      const neutral=(await readEmotionPortraits(context("viewer"),"character")).portraits.neutral;
      expect(neutral).toBeTruthy();
      await resumeEmotionPortraits(env); // expression references use the body, never the avatar
      expect(queueImage).toHaveBeenCalledTimes(6);
      for (const call of vi.mocked(queueImage).mock.calls.slice(1)) expect(call[1].image.referenceImageUrl).toBe(neutral);
      const ready=await readEmotionPortraits(context("viewer"),"character");
      expect(Object.keys(ready.portraits)).toHaveLength(6);expect(ready.generating).toBe(false);
      await generateEmotionPortraits(context(),"character");
      expect(queueImage).toHaveBeenCalledTimes(6);
    } finally {db.close()}
  });
  it("reuses the paid attempt after queue acknowledgement loss and transient status failure", async () => {
    const {db,env,context}=setup();try {
      vi.mocked(queueImage).mockRejectedValueOnce(new Error("Lost queue acknowledgement"));
      await generateEmotionPortraits(context(),"character");
      const originalId=vi.mocked(queueImage).mock.calls[0][2];
      vi.mocked(queueImage).mockImplementation(async (_env,_input,id)=>({provider:"openrouter",id:id!}));
      vi.mocked(imageJobStatus).mockRejectedValue(new Error("Status unavailable"));
      await resumeEmotionPortraits(env);
      expect(vi.mocked(queueImage).mock.calls[1][2]).toBe(originalId);
      await resumeEmotionPortraits(env);
      expect(queueImage).toHaveBeenCalledTimes(2);
      expect(db.prepare("SELECT count(*) AS count FROM character_body_art WHERE status='generating'").get()?.count).toBe(6);
    } finally {db.close()}
  });
  it("never exposes old opaque portraits and retries failed attempts only explicitly", async () => {
    const {db,env,context}=setup();try {
      vi.mocked(queueImage).mockImplementation(async (_env,_input,id)=>({provider:"openrouter",id:id!}));
      vi.mocked(imageJobStatus).mockResolvedValue({status:"failed",error:"IMAGE_ALPHA_REQUIRED"});
      await generateEmotionPortraits(context(),"character");
      await resumeEmotionPortraits(env);
      const result=await readEmotionPortraits(context(),"character");
      expect(result).toMatchObject({portraits:{},generating:false,failed:true,format:"transparent-upper-body-v1"});
      await readEmotionPortraits(context(),"character");
      expect(queueImage).toHaveBeenCalledTimes(1);
      const original=vi.mocked(queueImage).mock.calls[0][2];
      await generateEmotionPortraits(context(),"character");
      expect(queueImage).toHaveBeenCalledTimes(2);
      expect(vi.mocked(queueImage).mock.calls[1][2]).not.toBe(original);
    } finally {db.close()}
  });
  it("ignores a stale completion after the avatar changes", async () => {
    const {db,env,context}=setup();try {
      vi.mocked(queueImage).mockImplementation(async (_env,_input,id)=>({provider:"openrouter",id:id!}));
      vi.mocked(imageJobStatus).mockResolvedValue({status:"running"});
      await generateEmotionPortraits(context(),"character");
      const oldAttempt=vi.mocked(queueImage).mock.calls[0][2];
      db.prepare("UPDATE characters SET avatar_url='https://assets.example/portraits%2Fowner%2Fnew.jpg'").run();
      await generateEmotionPortraits(context(),"character");
      expect(vi.mocked(queueImage).mock.calls[1][2]).not.toBe(oldAttempt);
      expect(db.prepare("SELECT count(*) AS count FROM character_body_art WHERE source_url LIKE '%new.jpg'").get()?.count).toBe(6);
      expect((await readEmotionPortraits(context("viewer"),"character")).portraits).toEqual({});
    } finally {db.close()}
  });
  it("uses Pro for complete AI creation and removes the incompatible Standard provider restriction",async()=>{
    const {db,context}=setup();try{
      vi.mocked(completeChatText).mockResolvedValue(JSON.stringify({name:"Astrid",greeting:"Hey.",psychologyDefaults:{psychology:{cornerstone:"Keep my promises"}}}));
      const result=await autoCreateCharacter(context(),"A grounded astronomer");
      expect(result.psychologyDefaults.psychology.cornerstone).toBe("Keep my promises");
      expect(vi.mocked(completeChatText).mock.calls[0][0]).toMatchObject({OPENROUTER_MODEL:"deepseek/deepseek-v4-pro-0813",OPENROUTER_PROVIDERS:""});
      db.prepare("UPDATE character_autocreate_usage SET requests=12").run();
      await expect(autoCreateCharacter(context(),"Again")).rejects.toMatchObject({status:429});
      expect(completeChatText).toHaveBeenCalledTimes(1);
    }finally{db.close()}
  });
});
