import {DatabaseSync} from 'node:sqlite';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import type {Env, RequestContext} from '../../env';
const mocks = vi.hoisted(() => ({hasUltra: vi.fn(), getCharacterById: vi.fn(), getCharacterPsychologyDefaults: vi.fn()}));
vi.mock('../billing', () => ({hasUltra: mocks.hasUltra}));
vi.mock('../../db/queries/characters', () => ({getCharacterById: mocks.getCharacterById}));
vi.mock('../characterPsychology', () => ({getCharacterPsychologyDefaults: mocks.getCharacterPsychologyDefaults}));
beforeEach(() => {mocks.hasUltra.mockResolvedValue(false);mocks.getCharacterById.mockResolvedValue({owner_user_id:'owner',definition_private:0});mocks.getCharacterPsychologyDefaults.mockResolvedValue(null)});
import {ensureConversationMemorySchema} from '../../db/ensureConversationMemorySchema';
import {getConversationMemory, createConversationMemoryIfMissing, saveConversationMemory, saveAutomaticConversationMemory} from '../../db/queries/conversationMemory';
import {projectValidMemory, selectRelevantMemory, STANDARD_MEMORY_LIMITS, ULTRA_MEMORY_LIMITS, getCharacterMemory, updateCharacterMemory, buildCharacterMemoryPrompt} from './memory';
async function database() {
  const db = new DatabaseSync(':memory:');
  db.exec(`CREATE TABLE conversations(id TEXT PRIMARY KEY, version INTEGER, owner_user_id TEXT DEFAULT 'owner', character_id TEXT DEFAULT 'character'); CREATE TABLE messages(id TEXT PRIMARY KEY,conversation_id TEXT,position INTEGER,content TEXT,selected_regeneration_id TEXT); CREATE TABLE assistant_regenerations(id TEXT PRIMARY KEY,message_id TEXT,content TEXT); INSERT INTO conversations(id,version) VALUES('chat',3); INSERT INTO messages VALUES('m0','chat',0,'old',NULL),('m1','chat',1,'kept',NULL),('m2','chat',2,'discard',NULL);`);
  function prepare(sql:string,args:any[]=[]):any {return {
    bind(...values:any[]){return prepare(sql,values)},
    async first(){return db.prepare(sql).get(...args)??null},
    async all(){return {results:db.prepare(sql).all(...args)}},
    async run(){const result=db.prepare(sql).run(...args);return {success:true,meta:{changes:Number(result.changes)}}}
  };}
  const env={DB:{prepare,async batch(statements:any[]){db.exec('BEGIN');try{const result=[];for(const statement of statements)result.push(await statement.run());db.exec('COMMIT');return result}catch(error){db.exec('ROLLBACK');throw error}}}} as unknown as Env;
  await ensureConversationMemorySchema(env);
  await createConversationMemoryIfMissing(env,'chat',1);
  const snapshot = {conversationId:'chat',shortTerm:'Recent arc',midTerm:'Earlier arc',longTerm:'- Manual note\n- Kept event\n- Removed event',autoLongTermEntries:JSON.stringify([{text:'Kept event',sourcePosition:1},{text:'Removed event',sourcePosition:2}]),consolidatedPosition:2,expectedRevision:0,expectedConversationVersion:3,updatedAt:2,force:false,
    sceneState:JSON.stringify({summary:'Current scene',location:'Lighthouse',fictionalTime:null,timeline:[{text:'Kept event',sourcePosition:1,fictionalTime:null},{text:'Removed event',sourcePosition:2,fictionalTime:null}],sourcePosition:2}),
    emotionState:JSON.stringify({mood:'Pleased',sourcePosition:2}),personalityState:JSON.stringify({description:'Patient',sourcePosition:-1}),psychologyState:JSON.stringify({cornerstone:'Manual premise',sourcePosition:-1})};
  expect(await saveAutomaticConversationMemory(env,snapshot)).toBe(true);
  return {env,db,snapshot};
}
describe('memory database consistency',()=>{
  it('invalidates removed branch events in the delete transaction before a model refresh can finish',async()=>{
    const {env,db}=await database();try{
      db.prepare("DELETE FROM messages WHERE id='m2'").run();
      const raw=(await getConversationMemory(env,'chat'))!;
      expect(raw.invalidated_from_position).toBe(2);
      const view=projectValidMemory(raw);
      expect(view.long_term).toBe('- Manual note\n- Kept event');
      expect(view.short_term).toBe('');expect(view.mid_term).toBe('');
      expect(view.emotion_state).toBeNull();
      expect(JSON.parse(view.personality_state!).description).toBe('Patient');
      expect(JSON.parse(view.psychology_state!).cornerstone).toBe('Manual premise');
      expect(JSON.parse(view.scene_state!).timeline).toHaveLength(1);
    }finally{db.close()}
  });
  it('fences a late summary after rewind or a manual memory edit',async()=>{
    const {env,db,snapshot}=await database();try{
      const revision=(await getConversationMemory(env,'chat'))!.revision;
      db.prepare("DELETE FROM messages WHERE id='m2'").run();
      expect(await saveAutomaticConversationMemory(env,{...snapshot,expectedRevision:revision,force:true})).toBe(false);
      const newRevision=(await getConversationMemory(env,'chat'))!.revision;
      await saveConversationMemory(env,{conversationId:'chat',midTerm:'My edited arc',updatedAt:10});
      expect(await saveAutomaticConversationMemory(env,{...snapshot,expectedRevision:newRevision,force:true})).toBe(false);
      expect((await getConversationMemory(env,'chat'))!.mid_term).toBe('My edited arc');
    }finally{db.close()}
  });
  it('fences a summary when a newer chat turn changed the conversation version',async()=>{
    const {env,db,snapshot}=await database();try{
      const revision=(await getConversationMemory(env,'chat'))!.revision;
      db.prepare("UPDATE conversations SET version=4 WHERE id='chat'").run();
      expect(await saveAutomaticConversationMemory(env,{...snapshot,expectedRevision:revision,force:true})).toBe(false);
    }finally{db.close()}
  });
  it('invalidates selected-variant edits while unused alternatives do not erase current memory',async()=>{
    const {env,db}=await database();try{
      db.exec("INSERT INTO assistant_regenerations VALUES('r1','m1','variant'); INSERT INTO assistant_regenerations VALUES('r2','m1','unused'); UPDATE messages SET selected_regeneration_id='r1' WHERE id='m1'; UPDATE conversation_memories SET invalidated_from_position=NULL;");
      db.exec("UPDATE assistant_regenerations SET content='unused edit' WHERE id='r2'");
      expect((await getConversationMemory(env,'chat'))!.invalidated_from_position).toBeNull();
      db.exec("UPDATE assistant_regenerations SET content='selected edit' WHERE id='r1'");
      expect((await getConversationMemory(env,'chat'))!.invalidated_from_position).toBe(1);
    }finally{db.close()}
  });
  it('keeps automatic provenance when only a psychology field is edited',async()=>{
    const {env,db}=await database();try{
      await saveConversationMemory(env,{conversationId:'chat',emotionState:JSON.stringify({mood:'Calm',sourcePosition:-1}),updatedAt:3});
      expect(JSON.parse((await getConversationMemory(env,'chat'))!.auto_long_term_entries)).toHaveLength(2);
      await saveConversationMemory(env,{conversationId:'chat',longTerm:'Manually replaced notes',updatedAt:4});
      expect((await getConversationMemory(env,'chat'))!.auto_long_term_entries).toBe('[]');
    }finally{db.close()}
  });
});
describe('tier memory budgets and retrieval',()=>{
  it('uses the requested Standard and Ultra sizes',()=>{
    expect(STANDARD_MEMORY_LIMITS).toEqual({shortTerm:4000,midTerm:8000,longTerm:32000});
    expect(ULTRA_MEMORY_LIMITS).toEqual({shortTerm:16000,midTerm:16000,longTerm:64000});
  });
  it('selects relevant durable notes within the prompt budget without altering stored memory',()=>{
    const notes='- We once ate dinner at home.\n- Mara promised to return the lighthouse key.\n- The forest contains an old tree.';
    expect(selectRelevantMemory(notes,'Where is the lighthouse key?',55)).toBe('- Mara promised to return the lighthouse key.');
    expect(selectRelevantMemory(notes,'key',500)).toBe(notes);
  });
});

describe('memory API ownership and plan enforcement',()=>{
  function context(env:Env) {return {env,user:{userId:'owner'}} as RequestContext;}
  it('uses saved advanced character detail once in generation context without leaking it through the memory API',async()=>{
    const {env,db}=await database();try{
      const detail = 'I restore antique violins and carry a silver tuning fork from my mentor.';
      mocks.getCharacterPsychologyDefaults.mockResolvedValue({advancedDefinition:detail});
      // The reader may be Standard; the creator's saved definition remains
      // part of the character for everyone who is allowed to chat with it.
      const prompt = await buildCharacterMemoryPrompt(context(env),'chat');
      expect(prompt).toContain('ADDITIONAL CHARACTER DEFINITION');
      expect(prompt.split(detail)).toHaveLength(2);
      expect(prompt).toContain('LONG-TERM MEMORY');
      expect(JSON.stringify(await getCharacterMemory(context(env),'chat'))).not.toContain(detail);
    }finally{db.close()}
  });
  it('rejects Standard writes exceeding the active plan while Ultra accepts its larger allowance',async()=>{
    const {env,db}=await database();try{
      await expect(updateCharacterMemory(context(env),'chat',{shortTerm:'a'.repeat(4001)})).rejects.toMatchObject({code:'MEMORY_LIMIT'});
      await expect(updateCharacterMemory(context(env),'chat',{longTerm:'a'.repeat(32001)})).rejects.toMatchObject({code:'MEMORY_LIMIT'});
      mocks.hasUltra.mockResolvedValue(true);
      const memory=await updateCharacterMemory(context(env),'chat',{shortTerm:'a'.repeat(16000),midTerm:'b'.repeat(16000),longTerm:'c'.repeat(64000)});
      expect(memory.limits).toEqual(ULTRA_MEMORY_LIMITS);expect(memory.tier).toBe('ultra');
      await expect(updateCharacterMemory(context(env),'chat',{longTerm:'a'.repeat(64001)})).rejects.toMatchObject({code:'MEMORY_LIMIT'});
    }finally{db.close()}
  });
  it('preserves legacy memories after downgrade and allows unrelated edits without truncating them',async()=>{
    const {env,db}=await database();try{
      await saveConversationMemory(env,{conversationId:'chat',longTerm:'x'.repeat(50000),updatedAt:5});
      const memory=await updateCharacterMemory(context(env),'chat',{shortTerm:'A short updated note'});
      expect(memory.longTerm).toHaveLength(50000);expect(memory.shortTerm).toBe('A short updated note');
      expect(memory.limits.longTerm).toBe(32000);
    }finally{db.close()}
  });
  it("does not expose another owner's memory",async()=>{
    const {env,db}=await database();try{
      await expect(getCharacterMemory({env,user:{userId:'other'}} as RequestContext,'chat')).rejects.toMatchObject({status:403});
    }finally{db.close()}
  });
  it('keeps a private creator definition out of psychology responses',async()=>{
    const {env,db}=await database();try{
      mocks.getCharacterById.mockResolvedValue({owner_user_id:'creator',definition_private:1});
      const memory=await getCharacterMemory(context(env),'chat');
      expect(memory.psychology).toBeNull();expect(memory.psychologyPrivate).toBe(true);
      expect(memory.personality?.description).toBe('');
    }finally{db.close()}
  });
});
