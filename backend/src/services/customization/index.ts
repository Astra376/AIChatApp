import type { Env, RequestContext } from "../../env";
import { assert } from "../../lib/errors";
import { hasUltra, requireUltra } from "../billing";
import { generateImageWithFallback, IMAGE_MODELS } from "../../providers/openrouterImages";

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS user_appearance(user_id TEXT PRIMARY KEY, settings TEXT NOT NULL DEFAULT '{}', updated_at INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS appearance_assets(id TEXT PRIMARY KEY, user_id TEXT NOT NULL, kind TEXT NOT NULL, asset_key TEXT NOT NULL, created_at INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS appearance_assets_owner ON appearance_assets(user_id,created_at)`,
  `CREATE TABLE IF NOT EXISTS appearance_jobs(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,started_at INTEGER NOT NULL,fingerprint TEXT NOT NULL)`
];
const schemas = new WeakMap<D1Database, Promise<void>>();
export async function ensureAppearanceSchema(env: Env) {
  let pending = schemas.get(env.DB);
  if (!pending) {
    pending = env.DB.batch(SCHEMA.map(sql => env.DB.prepare(sql))).then(() => undefined);
    schemas.set(env.DB, pending); pending.catch(() => schemas.delete(env.DB));
  }
  await pending;
}
export interface Appearance {
  profileFont: string; frame: string; bannerId: string; profileBackgroundId: string;
  featuredCharacterId: string; widgets: string[]; background: string; backgroundId: string; icon: string;
}
export const DEFAULT_APPEARANCE: Appearance = {
  profileFont: "default", frame: "none", bannerId: "", profileBackgroundId: "", featuredCharacterId: "",
  widgets: [], background: "default", backgroundId: "", icon: "default"
};
const CHOICES: Record<string, string[]> = {
  profileFont: ["default", "sans", "serif", "mono", "rounded"], frame: ["none", "halo", "orbit", "laurel", "prism"],
  background: ["default", "aurora", "midnight", "rose", "paper", "image"], icon: ["default", "midnight", "rose", "mint", "sunset"]
};
export function validateAppearance(input: unknown, current: Appearance = DEFAULT_APPEARANCE): Appearance {
  assert(input && typeof input === "object" && !Array.isArray(input), 400, "INVALID_APPEARANCE", "Choose valid appearance settings.");
  const next = { ...current, widgets: [...current.widgets] };
  for (const [key, value] of Object.entries(input)) {
    assert(Object.prototype.hasOwnProperty.call(DEFAULT_APPEARANCE,key), 400, "INVALID_APPEARANCE", "Unknown appearance setting.");
    if (key === "widgets") {
      assert(Array.isArray(value) && value.length <= 6 && value.every(item => ["charactersChatted", "longestChat", "favorites", "created", "recommended"].includes(item)), 400, "INVALID_APPEARANCE", "Choose available profile widgets.");
      next.widgets = [...new Set(value)];
    } else {
      assert(typeof value === "string" && value.length <= 200, 400, "INVALID_APPEARANCE", "Invalid appearance value.");
      if (CHOICES[key]) assert(CHOICES[key].includes(value), 400, "INVALID_APPEARANCE", "Choose an available appearance.");
      Object.assign(next, { [key]: value });
    }
  }
  return next;
}
async function stored(env: Env, userId: string): Promise<Appearance> {
  const row = await env.DB.prepare("SELECT settings FROM user_appearance WHERE user_id=?").bind(userId).first<{settings: string}>();
  if (!row) return { ...DEFAULT_APPEARANCE, widgets: [] };
  try { return validateAppearance(JSON.parse(row.settings)); } catch { return { ...DEFAULT_APPEARANCE, widgets: [] }; }
}
async function sign(env: Env, value: string) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.SESSION_HMAC_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return Array.from(new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value))), x => x.toString(16).padStart(2,"0")).join("");
}
async function assetUrl(context: RequestContext, id: string, ownerId: string): Promise<string | null> {
  if (!id) return null;
  const asset = await context.env.DB.prepare("SELECT id FROM appearance_assets WHERE id=? AND user_id=?").bind(id,ownerId).first();
  if (!asset) return null;
  const expires = Date.now() + 3_600_000;
  return `${context.url.origin}/v1/appearance-assets/${encodeURIComponent(id)}?expires=${expires}&signature=${await sign(context.env,`${id}:${expires}`)}`;
}
async function withAssets(context: RequestContext, prefs: Appearance, owner: string) {
  const [bannerUrl,profileBackgroundUrl,backgroundUrl] = await Promise.all([
    assetUrl(context,prefs.bannerId,owner), assetUrl(context,prefs.profileBackgroundId,owner), assetUrl(context,prefs.backgroundId,owner)
  ]);
  return { ...prefs,bannerUrl,profileBackgroundUrl,backgroundUrl };
}
export async function getAppearance(context: RequestContext) {
  await ensureAppearanceSchema(context.env);
  const ultra = await hasUltra(context.env,context.user!.userId);
  const saved = await stored(context.env,context.user!.userId);
  return { ...await withAssets(context, ultra ? saved : DEFAULT_APPEARANCE, context.user!.userId), ultra };
}
export async function saveAppearance(context: RequestContext, input: unknown) {
  await requireUltra(context.env,context.user!.userId,"Appearance customization");
  await ensureAppearanceSchema(context.env);
  const userId = context.user!.userId;
  const next = validateAppearance(input, await stored(context.env,userId));
  for (const [id,kind] of [[next.bannerId,"banner"],[next.profileBackgroundId,"background"],[next.backgroundId,"background"]]) {
    if (!id) continue;
    assert(await context.env.DB.prepare("SELECT id FROM appearance_assets WHERE id=? AND user_id=? AND kind=?").bind(id,userId,kind).first(), 400,"INVALID_APPEARANCE","Choose one of your uploaded images.");
  }
  if (next.featuredCharacterId) assert(await context.env.DB.prepare("SELECT id FROM characters WHERE id=? AND visibility='public'").bind(next.featuredCharacterId).first(),400,"INVALID_APPEARANCE","Only public characters can be featured.");
  await context.env.DB.prepare("INSERT INTO user_appearance(user_id,settings,updated_at) VALUES (?,?,?) ON CONFLICT(user_id) DO UPDATE SET settings=excluded.settings,updated_at=excluded.updated_at").bind(userId,JSON.stringify(next),Date.now()).run();
  return getAppearance(context);
}
export async function getShowcase(context: RequestContext, userId: string) {
  await ensureAppearanceSchema(context.env);
  const ultra = await hasUltra(context.env,userId);
  const saved = ultra ? await stored(context.env,userId) : DEFAULT_APPEARANCE;
  const pick = (sql: string) => context.env.DB.prepare(sql).bind(userId).all<{id:string;name:string;avatarUrl:string|null;value:number}>().then(result => result.results);
  const [totals,favorites,created,recommended,featured] = await Promise.all([
    saved.widgets.some(x => x === "charactersChatted" || x === "longestChat")
      ? context.env.DB.prepare(`SELECT COUNT(DISTINCT c.character_id) AS charactersChatted, COALESCE(MAX((SELECT COUNT(*) FROM messages m WHERE m.conversation_id=c.id)),0) AS longestChat FROM conversations c WHERE c.owner_user_id=? AND EXISTS(SELECT 1 FROM messages m WHERE m.conversation_id=c.id AND m.role='user')`).bind(userId).first<{charactersChatted:number;longestChat:number}>() : Promise.resolve(null),
    saved.widgets.includes("favorites") ? pick(`SELECT c.id,c.name,c.avatar_url AS avatarUrl,c.like_count AS value FROM character_likes l JOIN characters c ON c.id=l.character_id WHERE l.user_id=? AND c.visibility='public' ORDER BY l.created_at DESC LIMIT 6`) : [],
    saved.widgets.includes("created") ? pick(`SELECT id,name,avatar_url AS avatarUrl,public_chat_count AS value FROM characters WHERE owner_user_id=? AND visibility='public' ORDER BY public_chat_count DESC,like_count DESC LIMIT 6`) : [],
    saved.widgets.includes("recommended") ? pick(`SELECT c.id,c.name,c.avatar_url AS avatarUrl,COUNT(m.id) AS value FROM conversations v JOIN characters c ON c.id=v.character_id JOIN messages m ON m.conversation_id=v.id WHERE v.owner_user_id=? AND c.visibility='public' GROUP BY c.id ORDER BY COUNT(m.id) DESC LIMIT 6`) : [],
    saved.featuredCharacterId ? context.env.DB.prepare("SELECT id,name,avatar_url AS avatarUrl,public_chat_count AS value FROM characters WHERE id=? AND visibility='public'").bind(saved.featuredCharacterId).first() : null
  ]);
  // Only the widgets the owner explicitly publishes expose engagement statistics.
  const settings = await withAssets(context,{...saved,backgroundId:"",background:"default",icon:"default"},userId);
  return { appearance: settings, stats: { charactersChatted: saved.widgets.includes("charactersChatted") ? totals?.charactersChatted ?? 0 : null,
    longestChat: saved.widgets.includes("longestChat") ? totals?.longestChat ?? 0 : null }, favorites,created,recommended,featured };
}
export function imageType(bytes: Uint8Array): string | null {
  if (bytes[0]===0xff && bytes[1]===0xd8 && bytes[2]===0xff) return "image/jpeg";
  if ([137,80,78,71,13,10,26,10].every((x,i) => bytes[i]===x)) return "image/png";
  if (new TextDecoder().decode(bytes.slice(0,4))==="RIFF" && new TextDecoder().decode(bytes.slice(8,12))==="WEBP") return "image/webp";
  return null;
}
function requireKind(kind: string) { assert(["banner","background","icon"].includes(kind),400,"INVALID_IMAGE","Choose a banner, background or icon."); }
async function saveAsset(context: RequestContext, id: string, kind: string, bytes: Uint8Array) {
  const type = imageType(bytes);
  assert(type && bytes.length > 32 && bytes.length <= 10_000_000,400,"INVALID_IMAGE","Choose a JPG, PNG or WebP under 10 MB.");
  const key = `appearance/${context.user!.userId.replace(/[^a-zA-Z0-9_-]/g,"_")}/${id}`;
  await context.env.ASSETS.put(key,bytes,{httpMetadata:{contentType:type}});
  await context.env.DB.prepare("INSERT INTO appearance_assets(id,user_id,kind,asset_key,created_at) VALUES (?,?,?,?,?) ON CONFLICT(id) DO NOTHING").bind(id,context.user!.userId,kind,key,Date.now()).run();
  return { id,kind,url: await assetUrl(context,id,context.user!.userId) };
}
export async function uploadAppearance(context: RequestContext, kind: string, file: File) {
  await requireUltra(context.env,context.user!.userId,"Custom images"); await ensureAppearanceSchema(context.env); requireKind(kind);
  assert(file.size<=10_000_000,413,"INVALID_IMAGE","Choose an image under 10 MB.");
  const count = await context.env.DB.prepare("SELECT COUNT(*) AS total FROM appearance_assets WHERE user_id=? AND created_at>?").bind(context.user!.userId,Date.now()-86_400_000).first<{total:number}>();
  assert((count?.total??0)<30,429,"IMAGE_LIMIT","Today's image upload limit has been reached.");
  return saveAsset(context,crypto.randomUUID(),kind,new Uint8Array(await file.arrayBuffer()));
}
export async function generateAppearance(context: RequestContext, input: {kind:string;prompt:string;requestKey:string}) {
  await requireUltra(context.env,context.user!.userId,"Generated appearance"); await ensureAppearanceSchema(context.env); requireKind(input.kind);
  assert(input.prompt.trim().length>=8 && input.prompt.length<=1200 && /^[a-zA-Z0-9_-]{16,100}$/.test(input.requestKey),400,"INVALID_IMAGE","Describe the image in a little more detail.");
  const digest = await crypto.subtle.digest("SHA-256",new TextEncoder().encode(`${context.user!.userId}:${input.requestKey}`));
  const id = Array.from(new Uint8Array(digest),x=>x.toString(16).padStart(2,"0")).join("");
  const fingerprint = JSON.stringify([input.kind,input.prompt.trim()]);
  const priorJob = await context.env.DB.prepare("SELECT fingerprint FROM appearance_jobs WHERE id=? AND user_id=?").bind(id,context.user!.userId).first<{fingerprint:string}>();
  assert(!priorJob || priorJob.fingerprint===fingerprint,409,"IMAGE_REQUEST_CONFLICT","This request was already used for a different image. Start a new generation.");
  const existing = await context.env.DB.prepare("SELECT kind FROM appearance_assets WHERE id=? AND user_id=?").bind(id,context.user!.userId).first<{kind:string}>();
  if (existing) { assert(existing.kind===input.kind,409,"IMAGE_REQUEST_CONFLICT","This request belongs to a different image."); return {id,kind:existing.kind,url:await assetUrl(context,id,context.user!.userId)}; }
  // Paid submissions are idempotent; failed submissions require an explicit new request.
  const acquired = await context.env.DB.prepare(`INSERT INTO appearance_jobs(id,user_id,started_at,fingerprint) SELECT ?,?,?,? WHERE (SELECT COUNT(*) FROM appearance_jobs WHERE user_id=? AND started_at>?)<10 ON CONFLICT(id) DO NOTHING`).bind(id,context.user!.userId,Date.now(),fingerprint,context.user!.userId,Date.now()-86_400_000).run();
  assert(acquired.meta.changes===1,429,"IMAGE_BUSY","This image is being prepared, or today's generation limit was reached.");
  const prompt = input.kind==="icon" ? `Premium app icon. One bold centered symbol, clean silhouette, no lettering, generous safe margins. ${input.prompt}` : `Tasteful atmospheric ${input.kind}, uncluttered composition, no text, readable behind app content. ${input.prompt}`;
  const image = await generateImageWithFallback(context.env, {
    model: input.kind === "icon" ? IMAGE_MODELS.nano : context.env.OPENROUTER_BACKGROUND_MODEL || IMAGE_MODELS.nano,
    prompt, aspectRatio: input.kind === "icon" ? "1:1" : "9:16"
  });
  return saveAsset(context, id, input.kind, image.bytes);
}
export async function getAppearanceAsset(context: RequestContext) {
  await ensureAppearanceSchema(context.env);
  const id = context.params.id, expires = Number(context.url.searchParams.get("expires"));
  const signature = context.url.searchParams.get("signature")??"";
  assert(/^[a-zA-Z0-9_-]{20,100}$/.test(id) && Number.isFinite(expires) && expires>Date.now() && expires<=Date.now()+3_600_000,403,"ASSET_EXPIRED","Image link expired.");
  const expected = await sign(context.env,`${id}:${expires}`);
  let mismatch = signature.length ^ expected.length;
  for(let i=0;i<expected.length;i++) mismatch |= (signature.charCodeAt(i)||0)^expected.charCodeAt(i);
  assert(mismatch===0,403,"ASSET_EXPIRED","Image link expired.");
  const record = await context.env.DB.prepare("SELECT asset_key FROM appearance_assets WHERE id=?").bind(id).first<{asset_key:string}>();
  const object = record ? await context.env.ASSETS.get(record.asset_key) : null;
  assert(object,404,"ASSET_NOT_FOUND","Image not found.");
  return new Response(object.body,{headers:{"Content-Type":object.httpMetadata?.contentType??"image/jpeg","Cache-Control":"private,max-age=1800","X-Content-Type-Options":"nosniff"}});
}
