import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Env, RequestContext } from "../env";
import { queuePortraitWithFal, pollPortraitWithFal } from "../providers/fal";
import { storeRemoteImageInR2 } from "../providers/r2";
import { completeChatText } from "../providers/openrouter";
import { autoCreateCharacter, generateEmotionPortraits, getCharacterPsychologyDefaults, normalizeCharacterPsychology,
  readCharacterPsychology, readEmotionPortraits, resumeEmotionPortraits, saveCharacterPsychology } from "./characterPsychology";
vi.mock("../providers/fal", () => ({queuePortraitWithFal:vi.fn(),pollPortraitWithFal:vi.fn()}));
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
    R2_PUBLIC_BASE_URL:"https://assets.example", FAL_MODEL:"fal-ai/nano-banana-2",ASSETS:{head:vi.fn(async()=>({}))},OPENROUTER_PROVIDERS:"venice",OPENROUTER_ULTRA_MODEL:"deepseek/deepseek-v4-pro-0813"} as unknown as Env;
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
  it("queues expressions once and resumes their provider jobs across requests",async()=>{
    const {db,env,context}=setup();try{
      vi.mocked(queuePortraitWithFal).mockImplementation(async(_env,prompt)=>({model:"fal-ai/nano-banana-2/edit",requestId:prompt,statusUrl:"https://queue.fal.run/status",resultUrl:"https://queue.fal.run/result"}));
      vi.mocked(pollPortraitWithFal).mockResolvedValue(null);
      await generateEmotionPortraits(context(),"character");
      expect(queuePortraitWithFal).toHaveBeenCalledTimes(6);
      for(const call of vi.mocked(queuePortraitWithFal).mock.calls) expect(call[2]).toBe("https://assets.example/portraits%2Fowner%2Fsource.jpg");
      await Promise.all([resumeEmotionPortraits(env),resumeEmotionPortraits(env)]);
      expect(queuePortraitWithFal).toHaveBeenCalledTimes(6);
      vi.mocked(pollPortraitWithFal).mockResolvedValue("https://fal.example/expression.jpg");
      vi.mocked(storeRemoteImageInR2).mockImplementation(async(_env,key)=>`https://assets.example/${key}`);
      await resumeEmotionPortraits(env);
      const result=await readEmotionPortraits(context("viewer"),"character");
      expect(Object.keys(result.portraits)).toHaveLength(6);expect(result.generating).toBe(false);
      await generateEmotionPortraits(context(),"character");
      expect(queuePortraitWithFal).toHaveBeenCalledTimes(6);
    }finally{db.close()}
  });
  it("retains the same paid provider job when a status request briefly fails",async()=>{
    const {db,env,context}=setup();try{
      vi.mocked(queuePortraitWithFal).mockResolvedValue({model:"m",requestId:"r",statusUrl:"https://q.example/s",resultUrl:"https://q.example/r"});
      await generateEmotionPortraits(context(),"character");
      vi.mocked(pollPortraitWithFal).mockRejectedValue(new Error("Network interrupted"));
      await resumeEmotionPortraits(env);
      expect(db.prepare("SELECT count(*) AS count FROM character_emotion_portraits WHERE status='generating' AND job_json IS NOT NULL").get()?.count).toBe(6);
      expect(queuePortraitWithFal).toHaveBeenCalledTimes(6);
    }finally{db.close()}
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
