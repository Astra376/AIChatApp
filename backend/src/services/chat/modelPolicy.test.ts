import {DatabaseSync} from 'node:sqlite';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import type {Env, RequestContext} from '../../env';
const billing = vi.hoisted(() => ({hasUltra: vi.fn()}));
vi.mock('../billing', () => billing);
import {classifyChatComplexity, resolveAutomaticModel, getChatModelPreferences, updateChatModelPreferences, STANDARD_MODEL, ULTRA_MODEL} from './modelPolicy';
function database() {
  const db = new DatabaseSync(':memory:');
  db.exec(`PRAGMA foreign_keys = ON; CREATE TABLE users(id TEXT PRIMARY KEY); CREATE TABLE conversations(id TEXT PRIMARY KEY,owner_user_id TEXT); CREATE TABLE messages(conversation_id TEXT,role TEXT); INSERT INTO users VALUES ('owner'),('other'); INSERT INTO conversations VALUES ('chat','owner');`);
  function prepare(sql: string, args: any[] = []): any { return {
    bind(...values: any[]) {return prepare(sql, values)},
    async first() {return db.prepare(sql).get(...args) ?? null},
    async all() {return {results: db.prepare(sql).all(...args)}},
    async run() {const result = db.prepare(sql).run(...args); return {success:true,meta:{changes:Number(result.changes)}}}
  }; }
  const env = {OPENROUTER_PROVIDERS:'venice', DB:{prepare, async batch(statements:any[]) {db.exec('BEGIN');try {const results=[];for(const statement of statements) results.push(await statement.run());db.exec('COMMIT');return results}catch(error){db.exec('ROLLBACK');throw error}}}} as unknown as Env;
  const context = {env,user:{userId:'owner'}} as RequestContext;
  return {env,context,db};
}
const demanding = 'Please analyze this strategy and compare the consequences because I promised my sister I would stay, but the bridge must close before dawn. If I leave now I cannot help my friend, while staying means I might lose the letter forever. Help me reason through the conflicting commitments and decide how to handle these constraints.';
beforeEach(() => billing.hasUltra.mockResolvedValue(false));
describe('chat model routing and server entitlements', () => {
  it('keeps routine dialogue on fast Flash without database or subscription work', async () => {
    const result = await resolveAutomaticModel({} as Env,'u','group:g','hey :)',1);
    expect(result.modelId).toBe(STANDARD_MODEL);
    expect(result.reasoningEnabled).toBe(false);
    expect(billing.hasUltra).not.toHaveBeenCalled();
  });
  it('recognizes demanding reasoning without treating long routine narration as difficult', () => {
    expect(classifyChatComplexity(demanding)).toBe('demanding');
    expect(classifyChatComplexity('The sea was quiet and the wind moved gently. '.repeat(20))).toBe('simple');
  });
  it('shares a bounded free Pro allowance across separate groups and direct scopes', async () => {
    const {env,db} = database();
    try {
      const first = await resolveAutomaticModel(env,'owner','group:a',demanding,1);
      const second = await resolveAutomaticModel(env,'owner','conversation:b',demanding,1);
      expect(first.modelId).toBe(ULTRA_MODEL);
      expect(first.env.OPENROUTER_PROVIDERS).toBe('');
      expect(second.modelId).toBe(STANDARD_MODEL);
      expect(second.reasoningEnabled).toBe(true);
      expect(db.prepare("SELECT COUNT(*) AS count FROM chat_reasoning_reservations WHERE model_tier='ultra'").get()?.count).toBe(1);
    } finally {db.close()}
  });
  it('does not spend again on retries or adjacent turns and stops after the daily quota', async () => {
    const {env,db} = database();
    try {
      await resolveAutomaticModel(env,'owner','group:a',demanding,1);
      expect((await resolveAutomaticModel(env,'owner','group:a',demanding,1)).reasoningEnabled).toBe(false);
      expect((await resolveAutomaticModel(env,'owner','group:a',demanding,2)).reasoningEnabled).toBe(false);
      for(let turn=5;turn<=13;turn+=4) await resolveAutomaticModel(env,'owner','group:a',demanding,turn);
      expect((await resolveAutomaticModel(env,'owner','group:b',demanding,1)).reasoningEnabled).toBe(false);
      expect(db.prepare('SELECT COUNT(*) AS count FROM chat_reasoning_reservations').get()?.count).toBe(4);
    } finally {db.close()}
  });
  it('authorizes model and font writes on the server and revokes expired entitlements gracefully', async () => {
    const {env,context,db}=database();
    try {
      await expect(updateChatModelPreferences(context,'chat',{mode:'ultra'})).rejects.toMatchObject({code:'ULTRA_REQUIRED'});
      await expect(updateChatModelPreferences(context,'chat',{chatFont:'serif'})).rejects.toMatchObject({code:'ULTRA_REQUIRED'});
      billing.hasUltra.mockResolvedValue(true);
      await updateChatModelPreferences(context,'chat',{mode:'ultra',chatFont:'serif'});
      expect((await getChatModelPreferences(context,'chat')).chatFont).toBe('serif');
      billing.hasUltra.mockResolvedValue(false);
      expect(await getChatModelPreferences(context,'chat')).toMatchObject({mode:'auto',chatFont:'default',effectiveModel:'Meek Standard'});
      await expect(getChatModelPreferences({...context, user:{userId:'other'}} as RequestContext,'chat')).rejects.toMatchObject({code:'CONVERSATION_NOT_FOUND'});
    } finally {db.close()}
  });
});
