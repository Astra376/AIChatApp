import { catalogEmbeddings, cosine } from "./embeddings";
import type { RequestContext } from "../../env";
import { getPublicFeed, type CharacterRecord } from "../../db/queries/characters";
import { toCharacterDto } from "../characters/characterDto";
import { AppError } from "../../lib/errors";
import { all } from "../../db/client";

export type InterestSignal = { character_id: string; turns: number; days: number; sessions: number; last_at: number; liked: number };
type Category = { id: string; title: string; ids: string[] };
export type DiscoverySnapshot = { version: string; createdAt: number; turnCount: number; likedAt: number; ids: string[]; categories: Category[] };
const DAY = 86_400_000;
const ready = new WeakMap<object, Promise<unknown>>();
const facets: Array<[string, string, RegExp]> = [
  ["fantasy", "Fantasy worlds", /\b(fantasy|magic|magical|wizard|witch|elf|elves|dragon|kingdom|sorcer\w*)\b/i],
  ["romance", "Romance & slow burns", /\b(romance|romantic|lover|love|crush|dating|flirt\w*|slow.burn)\b/i],
  ["adventure", "Adventure & action", /\b(adventure\w*|warrior|knight|quest|battle|fighter|samurai|ninja|hero\w*)\b/i],
  ["scifi", "Science fiction", /\b(sci.fi|space|cyberpunk|robot|android|spaceship|alien|futur\w*)\b/i],
  ["mystery", "Mystery & suspense", /\b(mystery|detective|investigat\w*|crime|secret|spy|thriller)\b/i],
  ["supernatural", "Supernatural encounters", /\b(vampire|demon|angel|ghost|spirit|werewolf|supernatural|haunt\w*)\b/i],
  ["everyday", "Slice of life", /\b(slice.of.life|roommate|neighbor|neighbour|cafe|coffee|school|college|university|classmate|coworker)\b/i],
  ["warm", "Warm & caring", /\b(kind|caring|gentle|warm|nurturing|supportive|compassion\w*|comfort\w*)\b/i],
  ["playful", "Playful personalities", /\b(playful|teas\w*|mischiev\w*|funny|humor\w*|humour\w*|witty|cheerful)\b/i],
  ["confident", "Confident & bold", /\b(confident|bold|assertive|charismatic|leader|fearless|ambitious)\b/i],
  ["reserved", "Quiet & mysterious", /\b(quiet|reserved|shy|introvert\w*|mysterious|stoic|enigmatic|aloof)\b/i],
  ["rivals", "Rivals & tension", /\b(rival\w*|enemies.to.lovers|tsundere|competitive|antagonist|villain)\b/i],
  ["anime", "Anime & manga", /\b(anime|manga|otaku|shonen|shoujo|isekai)\b/i],
  ["history", "Historical stories", /\b(historical|victorian|medieval|ancient|regency|royal|emperor|princess|prince|queen|king)\b/i]
];
const stop = new Set("the and with that this from your you are for has her his she him their they who but not into have will can about just character chat personality story user also very one all its our an as is of to in on a i it be my me at or by so he we was lives always never".split(" "));
export function publicTerms(c: CharacterRecord): string[] {
  // Private system prompts and transcript contents never enter catalog features.
  return [...new Set(`${c.tagline} ${c.description}`.toLowerCase().match(/[\p{L}][\p{L}\p{N}-]{2,}/gu) ?? [])].filter(t => !stop.has(t)).slice(0,160);
}
export function interestWeight(s: InterestSignal, now: number): number {
  const depth = Math.log1p(Math.max(0,s.turns));
  const recurrence = Math.log1p(Math.max(0,s.days - 1)) * 1.8 + Math.log1p(Math.max(0,s.sessions - 1)) * .5;
  // Diminishing returns prevent spam/long single sessions from owning the feed.
  return (depth * 2.8 + recurrence + s.liked * 4) * (.35 + .65 * Math.pow(.5, Math.max(0,now-s.last_at)/(30*DAY)));
}
export function buildDiscovery(catalog: CharacterRecord[], signals: InterestSignal[], collaborative: Map<string,number>, now: number, previous?: DiscoverySnapshot, embeddings: Map<string,number[]> = new Map()): DiscoverySnapshot {
  const terms = new Map(catalog.map(c => [c.id, publicTerms(c)]));
  const documentFrequency = new Map<string,number>();
  terms.forEach(ts => ts.forEach(t => documentFrequency.set(t,(documentFrequency.get(t) ?? 0)+1)));
  const idf = (t: string) => Math.log(1 + catalog.length/(1+(documentFrequency.get(t) ?? 0)));
  const preferences = new Map<string,number>();
  const weights = new Map(signals.map(s => [s.character_id,interestWeight(s,now)]));
  signals.forEach(signal => (terms.get(signal.character_id) ?? []).forEach(t => preferences.set(t,(preferences.get(t) ?? 0)+(weights.get(signal.character_id) ?? 0)*idf(t))));
  const preferenceNorm = Math.sqrt([...preferences.values()].reduce((n,x)=>n+x*x,0)) || 1;
  const vectors = new Map(catalog.map(c=>[c.id,new Set(terms.get(c.id))]));
  const semanticAnchors=signals.filter(s=>embeddings.has(s.character_id)).map(s=>({vector:embeddings.get(s.character_id)!,weight:weights.get(s.character_id) ?? 0}));
  const anchorWeight=semanticAnchors.reduce((n,a)=>n+a.weight,0);
  const score = new Map(catalog.map((c,index) => {
    const ts=terms.get(c.id) ?? [];
    const norm=Math.sqrt(ts.reduce((n,t)=>n+idf(t)**2,0)) || 1;
    const content=ts.reduce((n,t)=>n+(preferences.get(t) ?? 0)*idf(t),0)/(norm*preferenceNorm);
    const vector=embeddings.get(c.id);
    const semantic=vector && anchorWeight>0 ? semanticAnchors.reduce((n,a)=>n+Math.max(0,cosine(vector,a.vector))*a.weight,0)/anchorWeight : null;
    const affinity=semantic==null?content:content*.35+semantic*.65;
    const engagement=signals.find(s=>s.character_id===c.id);
    const familiarity=engagement ? Math.min(.2,interestWeight(engagement,now)*.012) : .14;
    const quality=Math.log1p(c.like_count)/Math.max(3,Math.log1p(c.public_chat_count+10));
    // Retrieval order retains the existing entitlement/activity prior but bounds
    // its effect below strong personal affinity. Cold starts use that prior.
    const prior=1/(1+index/30);
    return [c.id,affinity*4 + Math.log1p(collaborative.get(c.id) ?? 0)*.55 + quality*.15 + familiarity + prior*.3];
  }));
  function diversify(input: CharacterRecord[]) {
    const pending=[...input].sort((a,b)=>(score.get(b.id)!-score.get(a.id)!) || a.id.localeCompare(b.id));
    const ordered: CharacterRecord[]=[];
    while(pending.length) {
      // MMR on a bounded frontier keeps full-catalog ranking linear-ish.
      let best=0, bestScore=-Infinity;
      for(let i=0;i<Math.min(40,pending.length);i++) {
        const c=pending[i], own=vectors.get(c.id)!;
        const recent=ordered.slice(-5);
        const similarity=Math.max(0,...recent.map(p=>{
          const other=vectors.get(p.id)!; const intersection=[...own].filter(t=>other.has(t)).length;
          return intersection/Math.max(1,own.size+other.size-intersection);
        }));
        const value=score.get(c.id)!-.35*similarity-.25*recent.filter(p=>p.owner_user_id===c.owner_user_id).length;
        if(value>bestScore) {best=i;bestScore=value;}
      }
      ordered.push(pending.splice(best,1)[0]);
    }
    return ordered.map(c=>c.id);
  }
  const ids=diversify(catalog);
  const candidates=facets.map(([id,title,pattern])=>{
    const members=catalog.filter(c=>pattern.test(`${c.tagline} ${c.description}`));
    const affinity=members.reduce((n,c)=>n+(weights.get(c.id) ?? 0),0);
    return {id,title,ids:diversify(members),rank:affinity+Math.log1p(members.length)*.4};
  }).filter(c=>c.ids.length>=2).sort((a,b)=>b.rank-a.rank||a.id.localeCompare(b.id));
  // Public vocabulary supplies catalog-specific genres beyond the base taxonomy.
  const usedTitles=new Set(candidates.map(c=>c.title.toLowerCase()));
  [...preferences.entries()].sort((a,b)=>b[1]-a[1]).slice(0,24).forEach(([term,value])=>{
    const members=catalog.filter(c=>terms.get(c.id)?.includes(term));
    if(members.length<3 || members.length>catalog.length*.6 || usedTitles.has(term)) return;
    const id=`theme-${term}`, title=term.charAt(0).toUpperCase()+term.slice(1);
    if(candidates.some(c=>c.ids.length===members.length && members.every(m=>c.ids.includes(m.id)))) return;
    candidates.push({id,title,ids:diversify(members),rank:value/preferenceNorm}); usedTitles.add(term);
  });
  candidates.sort((a,b)=>b.rank-a.rank||a.id.localeCompare(b.id));
  const selected=candidates.slice(0,8);
  // Hysteresis: retain useful shelves and their order while admitting at most
  // two new interests per refresh. Card ranking can evolve within those shelves.
  const priorIds=previous?.categories.map(c=>c.id).filter(id=>id!=="for-you") ?? [];
  const retained=priorIds.map(id=>candidates.find(c=>c.id===id)).filter((c): c is typeof candidates[number]=>!!c && (selected.includes(c)||c.rank>.5)).slice(0,6);
  const shelves=[...retained,...selected.filter(c=>!retained.some(p=>p.id===c.id))].slice(0,8);
  return {version:String(now),createdAt:now,turnCount:signals.reduce((n,s)=>n+s.turns,0),likedAt:0,ids,
    categories:[{id:"for-you",title:"For you",ids},...shelves.map(({id,title,ids})=>({id,title,ids}))]};
}
async function ensure(context: RequestContext) {
  let promise=ready.get(context.env.DB);
  if(!promise) {
    promise=context.env.DB.prepare(`CREATE TABLE IF NOT EXISTS discovery_snapshots(
      user_id TEXT NOT NULL,version TEXT NOT NULL,snapshot_json TEXT NOT NULL,created_at INTEGER NOT NULL,
      PRIMARY KEY(user_id,version),FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE)`).run();
    ready.set(context.env.DB,promise);promise.catch(()=>ready.delete(context.env.DB));
  }
  await promise;
}
async function snapshot(context:RequestContext, version?: string):Promise<DiscoverySnapshot> {
  await ensure(context);
  const userId=context.user!.userId;
  const stored=await context.env.DB.prepare(`SELECT snapshot_json FROM discovery_snapshots WHERE user_id=? ${version ? "AND version=?" : ""} ORDER BY created_at DESC LIMIT 1`)
    .bind(...(version?[userId,version]:[userId])).first<{snapshot_json:string}>();
  const previous=stored?JSON.parse(stored.snapshot_json) as DiscoverySnapshot:undefined;
  if(version) {if(previous) return previous;throw new AppError(410,"DISCOVERY_EXPIRED","Refresh Discover to see your latest categories.");}
  // Open/close is a read of persistent user data; no ranking/model call on every open.
  if(previous && Date.now()-previous.createdAt<30*60_000) return previous;
  const signals=await all<InterestSignal>(context.env.DB.prepare(`
    WITH activity AS (
      SELECT c.character_id,COUNT(m.id) AS turns,COUNT(DISTINCT CAST(m.created_at/86400000 AS INTEGER)) AS days,
        COUNT(DISTINCT CASE WHEN m.id IS NOT NULL THEN c.id END) AS sessions,MAX(m.created_at) AS last_at
      FROM conversations c LEFT JOIN messages m ON m.conversation_id=c.id AND m.role='user'
      WHERE c.owner_user_id=? GROUP BY c.character_id
    ), interests AS (SELECT character_id FROM activity UNION SELECT character_id FROM character_likes WHERE user_id=?)
    SELECT i.character_id,COALESCE(a.turns,0) AS turns,COALESCE(a.days,0) AS days,COALESCE(a.sessions,0) AS sessions,
      MAX(COALESCE(a.last_at,0),COALESCE(l.created_at,0)) AS last_at,CASE WHEN l.user_id IS NULL THEN 0 ELSE 1 END AS liked
    FROM interests i LEFT JOIN activity a ON a.character_id=i.character_id
    LEFT JOIN character_likes l ON l.character_id=i.character_id AND l.user_id=?
  `).bind(userId,userId,userId));
  const turnCount=signals.reduce((n,s)=>n+s.turns,0),likedAt=Math.max(0,...signals.filter(s=>s.liked).map(s=>s.last_at));
  if(previous && Date.now()-previous.createdAt<6*60*60_000 && turnCount-previous.turnCount<8 && likedAt<=previous.likedAt) return previous;
  const catalog=await getPublicFeed(context.env,userId,0,2000);
  const anchors=signals.filter(s=>s.turns>=3 || s.liked).sort((a,b)=>interestWeight(b,Date.now())-interestWeight(a,Date.now())).slice(0,12).map(s=>s.character_id);
  const peers=anchors.length?await all<{character_id:string; affinity:number}>(context.env.DB.prepare(`
    WITH engaged AS (
      SELECT c.owner_user_id,c.character_id,COUNT(m.id) AS turns FROM conversations c
      JOIN messages m ON m.conversation_id=c.id AND m.role='user'
      WHERE c.character_id IN (SELECT value FROM json_each(?)) AND c.owner_user_id<>?
      GROUP BY c.owner_user_id,c.character_id HAVING COUNT(m.id)>=3
    ), peers AS (SELECT owner_user_id,COUNT(*) AS overlap FROM engaged GROUP BY owner_user_id ORDER BY overlap DESC LIMIT 100)
    SELECT c.character_id,SUM(p.overlap*1.0/(1+CAST((?-c.updated_at)/2592000000 AS REAL))) AS affinity
    FROM conversations c JOIN peers p ON p.owner_user_id=c.owner_user_id
    WHERE EXISTS(SELECT 1 FROM messages m WHERE m.conversation_id=c.id AND m.role='user' LIMIT 1)
    GROUP BY c.character_id
  `).bind(JSON.stringify(anchors),userId,Date.now())):[];
  const embeddings=await catalogEmbeddings(context,catalog);
  const next=buildDiscovery(catalog,signals,new Map(peers.map(p=>[p.character_id,p.affinity])),Date.now(),previous,embeddings);next.likedAt=likedAt;
  await context.env.DB.prepare("INSERT OR REPLACE INTO discovery_snapshots(user_id,version,snapshot_json,created_at) VALUES(?,?,?,?)")
    .bind(userId,next.version,JSON.stringify(next),next.createdAt).run();
  return next;
}
async function cards(context:RequestContext, ids:string[]) {
  if(!ids.length) return [];
  const rows=await all<CharacterRecord>(context.env.DB.prepare(`SELECT c.*,p.display_name AS owner_display_name,
    CASE WHEN l.user_id IS NULL THEN 0 ELSE 1 END AS liked_by_me FROM json_each(?) ordered
    JOIN characters c ON c.id=ordered.value AND c.visibility='public'
    LEFT JOIN profiles p ON p.user_id=c.owner_user_id
    LEFT JOIN character_likes l ON l.character_id=c.id AND l.user_id=? ORDER BY CAST(ordered.key AS INTEGER)`)
    .bind(JSON.stringify(ids),context.user!.userId));
  return rows.map(row=>toCharacterDto(row,context.user!.userId));
}
export async function discover(context:RequestContext) {
  const data=await snapshot(context);
  const categories=[];
  for(const section of data.categories) categories.push({id:section.id,title:section.title,total:section.ids.length,items:await cards(context,section.ids.slice(0,12))});
  return {version:data.version,categories};
}
export async function recommendedPage(context:RequestContext, rawCursor:string|null, limit:number, categoryId="for-you", requestedVersion?:string|null) {
  const match=rawCursor?.match(/^(\d+):(\d+)$/);
  const data=await snapshot(context,match?.[1] ?? requestedVersion ?? undefined);
  const offset=match?Number(match[2]):Math.max(0,Number(rawCursor)||0);
  const category=data.categories.find(c=>c.id===categoryId);
  if(!category) throw new AppError(404,"CATEGORY_NOT_FOUND","This category is no longer available. Refresh Discover.");
  const slice=category.ids.slice(offset,offset+limit);
  return {items:await cards(context,slice),nextCursor:offset+limit<category.ids.length?`${data.version}:${offset+limit}`:null};
}
