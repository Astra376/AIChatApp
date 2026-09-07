import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { describe,it,expect,vi,beforeEach } from "vitest";
import type { Env,RequestContext } from "../../env";
import { DEFAULT_APPEARANCE,validateAppearance,imageType,getAppearance,getShowcase,saveAppearance,generateAppearance,getAppearanceAsset } from "./index";
vi.mock("../billing",()=>({hasUltra:vi.fn(async()=>true),requireUltra:vi.fn(async()=>undefined)}));
import { hasUltra,requireUltra } from "../billing";
function setup() {
  const db=new DatabaseSync(":memory:"); db.exec(readFileSync(new URL("../../db/migrations/0001_initial.sql",import.meta.url),"utf8"));
  db.exec(`INSERT INTO users VALUES ('owner','owner','owner@example.com',1,1),('other','other','other@example.com',1,1);
    INSERT INTO characters VALUES ('public','owner','Public','tag','bio','prompt','public',NULL,10,2,1,1,1),('private','owner','Private','tag','bio','prompt','private',NULL,0,0,1,1,1);
    INSERT INTO conversations VALUES ('c','owner','public',1,1,1),('private-chat','owner','private',1,1,1);
    INSERT INTO messages VALUES ('m1','c',0,'user','hi',0,1,1,NULL),('m2','c',1,'assistant','hey',0,1,1,NULL),('m3','private-chat',0,'user','secret',0,1,1,NULL);
    INSERT INTO character_likes VALUES ('owner','public',1),('owner','private',2);`);
  function statement(sql:string,args:any[]=[]):any { return {
    bind(...values:any[]){return statement(sql,values)}, async run(){ const result=db.prepare(sql).run(...args);return{meta:{changes:Number(result.changes)}} },
    async first(){return db.prepare(sql).get(...args)??null}, async all(){return{results:db.prepare(sql).all(...args)}}
  }; }
  const env={DB:{prepare:statement,async batch(items:any[]){return Promise.all(items.map(item=>item.run()))}},SESSION_HMAC_SECRET:"test-only",ASSETS:{get:vi.fn()}} as unknown as Env;
  const context=(userId="owner")=>({env,user:{userId},request:new Request("https://example.com"),url:new URL("https://example.com"),params:{}} as RequestContext);
  return{db,env,context};
}
beforeEach(()=>{vi.mocked(hasUltra).mockResolvedValue(true);vi.mocked(requireUltra).mockResolvedValue(undefined)});
describe("appearance access and profile publishing",()=>{
  it("rejects invalid fields, choices, and widgets instead of retaining arbitrary JSON",()=>{
    expect(()=>validateAppearance({profileFont:"javascript:bad"})).toThrow();
    expect(()=>validateAppearance({ultra:true})).toThrow();
    expect(()=>validateAppearance({widgets:["privateMessages"]})).toThrow();
    expect(validateAppearance({widgets:["favorites","favorites"]}).widgets).toEqual(["favorites"]);
  });
  it("does not publish chat activity by default and only publishes selected metrics",async()=>{
    const {db,context}=setup();try{
      const initial=await getShowcase(context(),"owner");expect(initial.stats).toEqual({charactersChatted:null,longestChat:null});expect(initial.favorites).toEqual([]);
      await saveAppearance(context(),{widgets:["charactersChatted","longestChat","favorites","created","recommended"],featuredCharacterId:"public"});
      const result=await getShowcase(context("other"),"owner");
      expect(result.stats).toEqual({charactersChatted:2,longestChat:2});
      expect(result.favorites.map(x=>x.id)).toEqual(["public"]);expect(result.created.map(x=>x.id)).toEqual(["public"]);expect(result.recommended.map(x=>x.id)).toEqual(["public"]);
    }finally{db.close()}
  });
  it("rejects private featured characters and images belonging to someone else",async()=>{
    const {db,context}=setup();try{
      await getAppearance(context());
      db.exec("INSERT INTO appearance_assets VALUES ('other-image','other','banner','appearance/other/image',1)");
      await expect(saveAppearance(context(),{featuredCharacterId:"private"})).rejects.toMatchObject({code:"INVALID_APPEARANCE"});
      await expect(saveAppearance(context(),{bannerId:"other-image"})).rejects.toMatchObject({code:"INVALID_APPEARANCE"});
    }finally{db.close()}
  });
  it("restores default appearance after expiry without deleting the saved design",async()=>{
    const {db,context}=setup();try{
      await saveAppearance(context(),{frame:"prism",widgets:["favorites"]});
      vi.mocked(hasUltra).mockResolvedValue(false);
      expect(await getAppearance(context())).toMatchObject({...DEFAULT_APPEARANCE,ultra:false});
      expect((await getShowcase(context(),"owner")).favorites).toEqual([]);
      vi.mocked(hasUltra).mockResolvedValue(true);expect((await getAppearance(context())).frame).toBe("prism");
    }finally{db.close()}
  });
  it("rejects forged private asset links before R2 access",async()=>{
    const {db,context,env}=setup();try{
      const c=context();c.params.id="a".repeat(32);c.url=new URL(`https://example.com?expires=${Date.now()+10000}&signature=forged`);
      await expect(getAppearanceAsset(c)).rejects.toMatchObject({code:"ASSET_EXPIRED"});expect(env.ASSETS.get).not.toHaveBeenCalled();
    }finally{db.close()}
  });
  it("returns the same finished generation without submitting another paid request",async()=>{
    const {db,context}=setup();try{
      await getAppearance(context());const requestKey="request-key-with-enough-characters";
      const digest=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(`owner:${requestKey}`));
      const id=Array.from(new Uint8Array(digest),x=>x.toString(16).padStart(2,"0")).join("");
      db.prepare("INSERT INTO appearance_assets VALUES (?,'owner','icon','appearance/icon',1)").run(id);
      const asset=await generateAppearance(context(),{kind:"icon",prompt:"A moon above a sleeping city",requestKey});expect(asset.id).toBe(id);
      expect(db.prepare("SELECT COUNT(*) AS n FROM appearance_jobs").get()?.n).toBe(0);
    }finally{db.close()}
  });
  it("rejects a changed payload for the same paid generation key",async()=>{
    const {db,context}=setup();try{
      await getAppearance(context());const requestKey="request-key-with-enough-characters";
      const digest=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(`owner:${requestKey}`));
      const id=Array.from(new Uint8Array(digest),x=>x.toString(16).padStart(2,"0")).join("");
      db.prepare("INSERT INTO appearance_jobs VALUES (?,'owner',?,?)").run(id,Date.now(),JSON.stringify(["icon","Original prompt"]));
      await expect(generateAppearance(context(),{kind:"icon",prompt:"A completely different prompt",requestKey})).rejects.toMatchObject({code:"IMAGE_REQUEST_CONFLICT"});
    }finally{db.close()}
  });
  it("checks Ultra before changing or generating anything",async()=>{
    const {db,context}=setup();try{
      vi.mocked(requireUltra).mockRejectedValueOnce(Object.assign(new Error("Ultra required"),{code:"ULTRA_REQUIRED"}));
      await expect(saveAppearance(context(),{frame:"prism"})).rejects.toMatchObject({code:"ULTRA_REQUIRED"});
      expect(db.prepare("SELECT name FROM sqlite_master WHERE name='user_appearance'").get()).toBeUndefined();
    }finally{db.close()}
  });
  it("recognizes supported image bytes and rejects SVG or executable content",()=>{
    expect(imageType(new Uint8Array([137,80,78,71,13,10,26,10]))).toBe("image/png");
    expect(imageType(new TextEncoder().encode("<svg onload='x'>"))).toBeNull();
  });
});
