import type { RequestContext } from "../../env";
import { all } from "../../db/client";
import type { CharacterRecord } from "../../db/queries/characters";
const MODEL="openai/text-embedding-3-small", DIMENSIONS=256;
const schemas=new WeakMap<object,Promise<unknown>>();
function text(c:CharacterRecord) {return `${c.name}\n${c.tagline}\n${c.description}`.slice(0,4500);}
export function cosine(a:number[],b:number[]) {
  if(a.length!==b.length || !a.length) return 0;
  let dot=0,aa=0,bb=0;
  for(let i=0;i<a.length;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}
  return dot/(Math.sqrt(aa*bb)||1);
}
// Only public catalog metadata is embedded. No conversation text, user identity,
// private character definition or derived user profile leaves the ranking service.
export async function catalogEmbeddings(context:RequestContext,catalog:CharacterRecord[]):Promise<Map<string,number[]>> {
  let ready=schemas.get(context.env.DB);
  if(!ready){ready=context.env.DB.prepare(`CREATE TABLE IF NOT EXISTS discovery_embeddings(
    character_id TEXT PRIMARY KEY,model TEXT NOT NULL,source_text TEXT NOT NULL DEFAULT '',vector_json TEXT NOT NULL DEFAULT '[]',
    lease_until INTEGER NOT NULL DEFAULT 0,FOREIGN KEY(character_id) REFERENCES characters(id) ON DELETE CASCADE)`).run();
    schemas.set(context.env.DB,ready);ready.catch(()=>schemas.delete(context.env.DB));}
  await ready;
  const stored=await all<{character_id:string;source_text:string;vector_json:string;lease_until:number}>(context.env.DB.prepare(
    "SELECT * FROM discovery_embeddings WHERE model=? AND character_id IN (SELECT value FROM json_each(?))").bind(MODEL,JSON.stringify(catalog.map(c=>c.id))));
  const records=new Map(stored.map(r=>[r.character_id,r])),vectors=new Map<string,number[]>();
  for(const c of catalog){const record=records.get(c.id);if(record?.source_text===text(c)){
    try{const vector=JSON.parse(record.vector_json);if(Array.isArray(vector)&&vector.length===DIMENSIONS&&vector.every(Number.isFinite))vectors.set(c.id,vector);}catch{}
  }}
  const missing=catalog.filter(c=>!vectors.has(c.id) && (records.get(c.id)?.lease_until??0)<Date.now()).slice(0,32);
  if(missing.length && context.env.OPENROUTER_API_KEY && context.waitUntil) context.waitUntil(warm(context,missing).catch(()=>undefined));
  return vectors;
}
async function warm(context:RequestContext,items:CharacterRecord[]) {
  const selected:CharacterRecord[]=[];
  for(const c of items){
    await context.env.DB.prepare("INSERT OR IGNORE INTO discovery_embeddings(character_id,model) VALUES(?,?)").bind(c.id,MODEL).run();
    const lease=await context.env.DB.prepare("UPDATE discovery_embeddings SET lease_until=? WHERE character_id=? AND lease_until<?")
      .bind(Date.now()+120_000,c.id,Date.now()).run();
    if(lease.meta.changes)selected.push(c);
  }
  if(!selected.length)return;
  const response=await fetch("https://openrouter.ai/api/v1/embeddings",{method:"POST",
    headers:{Authorization:`Bearer ${context.env.OPENROUTER_API_KEY}`,"Content-Type":"application/json","X-Title":"Meek catalog recommendations"},
    body:JSON.stringify({model:MODEL,input:selected.map(text),dimensions:DIMENSIONS,encoding_format:"float"}),signal:AbortSignal.timeout(15_000)});
  if(!response.ok){await response.body?.cancel();return;}
  const data=await response.json() as {data?:Array<{index:number;embedding:number[]}>};
  for(const item of data.data??[]){
    const c=selected[item.index];
    if(!c || !Array.isArray(item.embedding)||item.embedding.length!==DIMENSIONS||!item.embedding.every(Number.isFinite))continue;
    await context.env.DB.prepare(`UPDATE discovery_embeddings SET model=?,source_text=?,vector_json=?,lease_until=0
      WHERE character_id=? AND EXISTS(SELECT 1 FROM characters WHERE id=? AND visibility='public' AND updated_at=?)`)
      .bind(MODEL,text(c),JSON.stringify(item.embedding),c.id,c.id,c.updated_at).run();
  }
}
