import { getCharacterDefaultPersona } from "./personas";
import type { Env, RequestContext } from "../env";
import { AppError, assert } from "../lib/errors";
import { getCharacterById } from "../db/queries/characters";
import { completeChatText } from "../providers/openrouter";
import { queuePortraitWithFal, pollPortraitWithFal, type QueuedPortraitJob } from "../providers/fal";
import { storeRemoteImageInR2 } from "../providers/r2";
import { publicAssetUrl } from "../lib/assets";

export const emotionKeys = ["trust", "affection", "stress", "energy", "openness", "joy", "sadness", "anger", "fear", "curiosity", "jealousy", "hope", "loneliness", "shame", "pride", "guilt"] as const;
export const personalityKeys = ["warmth", "confidence", "playfulness", "formality", "assertiveness", "volatility", "resilience", "adaptability"] as const;
export interface CharacterPsychologyDefaults {
  emotions: Record<string, number | string>;
  personality: Record<string, number | string>;
  psychology: { cornerstone: string; beliefs: string[]; desires: string[]; secretDesires: string[]; lifeStory: string; dailyLife: string; relationships: string; significantEvents: string[] };
  defaultPersona: { name: string; backstory: string };
}
const schemas = new WeakMap<D1Database, Promise<void>>();
export async function ensureCharacterPsychologySchema(env: Env) {
  let pending = schemas.get(env.DB);
  if (!pending) {
    pending = env.DB.batch([
      env.DB.prepare(`CREATE TABLE IF NOT EXISTS character_psychology (character_id TEXT PRIMARY KEY REFERENCES characters(id) ON DELETE CASCADE, defaults_json TEXT NOT NULL, updated_at INTEGER NOT NULL)`),
      env.DB.prepare(`CREATE TABLE IF NOT EXISTS character_emotion_portraits (character_id TEXT NOT NULL REFERENCES characters(id) ON DELETE CASCADE, emotion TEXT NOT NULL, source_url TEXT NOT NULL, image_url TEXT, job_json TEXT, status TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(character_id, emotion))`),
      env.DB.prepare(`CREATE TABLE IF NOT EXISTS character_autocreate_usage (user_id TEXT NOT NULL, day TEXT NOT NULL, requests INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(user_id, day))`)
    ]).then(() => undefined);
    schemas.set(env.DB, pending); pending.catch(() => schemas.delete(env.DB));
  }
  await pending;
}
function object(value: unknown): Record<string, unknown> {
  return value != null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}
function text(value: unknown, limit = 3000): string { return typeof value === "string" ? value.trim().slice(0, limit) : ""; }
function lines(value: unknown): string[] { return Array.isArray(value) ? value.slice(0, 20).map(v => text(v, 400)).filter(Boolean) : []; }
export function normalizeCharacterPsychology(input: unknown): CharacterPsychologyDefaults {
  const root = object(input), em = object(root.emotions), pe = object(root.personality), ps = object(root.psychology), persona = object(root.defaultPersona);
  const scores = (source: Record<string, unknown>, keys: readonly string[], fallback: number) => Object.fromEntries(keys.map(key => {
    const value = source[key]; return [key, typeof value === "number" && Number.isFinite(value) ? Math.round(Math.max(0, Math.min(100, value))) : fallback];
  }));
  return {
    emotions: { ...scores(em, emotionKeys, 30), mood: text(em.mood, 100) || "Grounded", reason: text(em.reason, 500) },
    personality: { ...scores(pe, personalityKeys, 50), description: text(pe.description, 1500) },
    psychology: { cornerstone: text(ps.cornerstone, 1200), beliefs: lines(ps.beliefs), desires: lines(ps.desires), secretDesires: lines(ps.secretDesires), lifeStory: text(ps.lifeStory, 8000), dailyLife: text(ps.dailyLife), relationships: text(ps.relationships), significantEvents: lines(ps.significantEvents) },
    defaultPersona: { name: text(persona.name, 80), backstory: text(persona.backstory, 4000) }
  };
}
export async function characterPsychologyStatement(env: Env, characterId: string, input: unknown) {
  await ensureCharacterPsychologySchema(env);
  return env.DB.prepare(`INSERT INTO character_psychology (character_id, defaults_json, updated_at) VALUES (?, ?, ?) ON CONFLICT(character_id) DO UPDATE SET defaults_json = excluded.defaults_json, updated_at = excluded.updated_at`)
    .bind(characterId, JSON.stringify({ ...normalizeCharacterPsychology(input), defaultPersona: { name: "", backstory: "" } }), Date.now());
}
export async function getCharacterPsychologyDefaults(env: Env, characterId: string): Promise<CharacterPsychologyDefaults | null> {
  await ensureCharacterPsychologySchema(env);
  const row = await env.DB.prepare("SELECT defaults_json FROM character_psychology WHERE character_id = ?").bind(characterId).first<{ defaults_json: string }>();
  if (!row) return null;
  try { return normalizeCharacterPsychology(JSON.parse(row.defaults_json)); } catch { return null; }
}
async function visibleCharacter(context: RequestContext, id: string, ownerOnly = false) {
  const character = await getCharacterById(context.env, context.user!.userId, id);
  assert(character && (character.visibility !== "private" || character.owner_user_id === context.user!.userId), 404, "CHARACTER_NOT_FOUND", "Character not found.");
  if (ownerOnly) assert(character.owner_user_id === context.user!.userId, 403, "CHARACTER_OWNER_REQUIRED", "Only the creator can edit these defaults.");
  return character;
}
export async function readCharacterPsychology(context: RequestContext, id: string) {
  const character = await visibleCharacter(context, id);
  assert(!character.definition_private || character.owner_user_id === context.user!.userId, 403, "PRIVATE_DEFINITION", "The creator has kept this character's psychology private.");
  return { ...await getCharacterPsychologyDefaults(context.env, id) ?? normalizeCharacterPsychology({}), defaultPersona: await getCharacterDefaultPersona(context.env, id) ?? { name: "", backstory: "", appearance: "", pronouns: "" } };
}
export async function saveCharacterPsychology(context: RequestContext, id: string, value: unknown) {
  await visibleCharacter(context, id, true);
  await (await characterPsychologyStatement(context.env, id, value)).run();
  return normalizeCharacterPsychology(value);
}
export async function autoCreateCharacter(context: RequestContext, idea: string) {
  await ensureCharacterPsychologySchema(context.env);
  const day = new Date().toISOString().slice(0, 10);
  const result = await context.env.DB.prepare(`INSERT INTO character_autocreate_usage(user_id, day, requests) VALUES (?, ?, 1) ON CONFLICT(user_id, day) DO UPDATE SET requests = requests + 1 WHERE requests < 12 RETURNING requests`).bind(context.user!.userId, day).first();
  assert(result, 429, "CREATION_LIMIT", "Today's character creation allowance has been used. Your draft is saved.");
  const response = await completeChatText({ ...context.env, OPENROUTER_MODEL: context.env.OPENROUTER_ULTRA_MODEL || "deepseek/deepseek-v4-pro-0813", OPENROUTER_FALLBACK_MODELS: "", OPENROUTER_PROVIDERS: context.env.OPENROUTER_ULTRA_PROVIDERS || "" }, [
    { role: "system", content: `Design a layered fictional character from the user's idea. Return only a JSON object with name (80 characters), tagline (50), appearance (1200), greeting (1200), bio (500), characterDefinition (6000), psychologyDefaults: {emotions,personality,psychology,defaultPersona}. Emotions numeric 0-100: ${emotionKeys.join(", ")}; also mood and reason strings. Personality numeric 0-100: ${personalityKeys.join(", ")}; also description. Psychology strings: cornerstone (a specific grounding experience or belief), lifeStory, dailyLife (include work/school and habits), relationships; string arrays beliefs, desires, secretDesires, significantEvents. defaultPersona has name and backstory, leave both blank unless the user asks for a particular role. Make values coherent, nuanced, individual and capable of evolving. Avoid generic traumatic backstories. Greeting must use first-person character actions and second-person address, without inventing user actions. Match the requested tone, naturally varying message length and separate speech from actions with blank lines. Preserve every explicit user detail.` },
    { role: "user", content: idea }
  ], { maxTokens: 6000, temperature: 0.8 });
  const raw = response.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
  let parsed: Record<string, unknown>;
  try { parsed = object(JSON.parse(raw)); } catch { throw new AppError(502, "CREATION_FORMAT", "The character draft couldn't be completed. Please try again."); }
  assert(text(parsed.name, 80) && text(parsed.greeting, 1200), 502, "CREATION_INCOMPLETE", "The character draft was incomplete. Please try again.");
  return { name: text(parsed.name, 80), tagline: text(parsed.tagline, 50), appearance: text(parsed.appearance, 1200), greeting: text(parsed.greeting, 1200), bio: text(parsed.bio, 500), characterDefinition: text(parsed.characterDefinition, 6000), psychologyDefaults: normalizeCharacterPsychology(parsed.psychologyDefaults) };
}
export const portraitEmotions = ["neutral", "joy", "sadness", "anger", "fear", "affection"] as const;
export async function readEmotionPortraits(context: RequestContext, id: string) {
  await visibleCharacter(context, id);
  await ensureCharacterPsychologySchema(context.env);
  const result = await context.env.DB.prepare("SELECT emotion, image_url, status FROM character_emotion_portraits WHERE character_id = ?").bind(id).all<{ emotion: string; image_url: string | null; status: string }>();
  if (result.results.some(r => r.status === "generating")) context.waitUntil?.(resumeEmotionPortraits(context.env, id));
  return { portraits: Object.fromEntries(result.results.filter(r => r.image_url).map(r => [r.emotion, r.image_url])), generating: result.results.some(r => r.status === "generating") };
}
export async function generateEmotionPortraits(context: RequestContext, id: string) {
  const character = await visibleCharacter(context, id, true);
  const source = character.avatar_url;
  const prefix = publicAssetUrl(context.env.R2_PUBLIC_BASE_URL, `portraits/${context.user!.userId}/`);
  assert(source?.startsWith(prefix), 400, "PORTRAIT_REQUIRED", "Choose a generated or uploaded portrait first.");
  await ensureCharacterPsychologySchema(context.env);
  // Claims persist across Worker isolates. An interrupted generation becomes retryable after three minutes.
  const claimed: string[] = [];
  for (const emotion of portraitEmotions) {
    const row = await context.env.DB.prepare(`INSERT INTO character_emotion_portraits(character_id,emotion,source_url,status,updated_at) VALUES (?,?,?,'generating',?) ON CONFLICT(character_id,emotion) DO UPDATE SET source_url=excluded.source_url,image_url=NULL,job_json=NULL,status='generating',updated_at=excluded.updated_at WHERE source_url != excluded.source_url OR status='failed' OR (status='generating' AND updated_at < ?) RETURNING emotion`)
      .bind(id, emotion, source, Date.now(), Date.now() - 180_000).first();
    if (row) claimed.push(emotion);
  }
  if (context.waitUntil) context.waitUntil(resumeEmotionPortraits(context.env, id));
  else await resumeEmotionPortraits(context.env, id);
  return readEmotionPortraits(context, id);
}

/** A GET/cron advances durable provider jobs without waiting for the image itself. */
export async function resumeEmotionPortraits(env: Env, characterId?: string) {
  await ensureCharacterPsychologySchema(env);
  const result = await env.DB.prepare(`SELECT e.character_id,e.emotion,e.source_url,e.job_json,e.updated_at,c.owner_user_id
    FROM character_emotion_portraits e JOIN characters c ON c.id=e.character_id
    WHERE e.status='generating' ${characterId ? "AND e.character_id=?" : ""} ORDER BY e.updated_at ASC LIMIT 12`)
    .bind(...(characterId ? [characterId] : [])).all<{ character_id:string;emotion:string;source_url:string;job_json:string|null;updated_at:number;owner_user_id:string }>();
  for (let start = 0; start < result.results.length; start += 3) await Promise.all(result.results.slice(start,start+3).map(async row => {
    try {
      if (!row.job_json) {
        // SQL compare-and-swap prevents concurrent GET/cron ticks from submitting duplicate paid jobs.
        const claim = await env.DB.prepare("UPDATE character_emotion_portraits SET job_json='claiming',updated_at=? WHERE character_id=? AND emotion=? AND source_url=? AND job_json IS NULL RETURNING emotion")
          .bind(Date.now(),row.character_id,row.emotion,row.source_url).first();
        if (!claim) return;
        const job = await queuePortraitWithFal(env,
          `Use this exact character as the identity reference. Preserve the same face, facial proportions, age, hair, eyes, skin, clothing and art style. Upper-body portrait, consistent front three-quarter framing and lighting, quiet unobtrusive dark backdrop. Express ${row.emotion} naturally through face and posture, no exaggerated caricature. No captions, borders or text.`, row.source_url);
        await env.DB.prepare("UPDATE character_emotion_portraits SET job_json=?,updated_at=? WHERE character_id=? AND emotion=? AND source_url=? AND job_json='claiming'")
          .bind(JSON.stringify(job),Date.now(),row.character_id,row.emotion,row.source_url).run();
        return;
      }
      if (row.job_json === "claiming") {
        if (row.updated_at < Date.now()-180_000) await env.DB.prepare("UPDATE character_emotion_portraits SET status='failed' WHERE character_id=? AND emotion=? AND job_json='claiming'").bind(row.character_id,row.emotion).run();
        return;
      }
      const url = await pollPortraitWithFal(env, JSON.parse(row.job_json) as QueuedPortraitJob);
      if (!url) {
        if (row.updated_at < Date.now()-1_800_000) throw new Error("Expression generation expired");
        return;
      }
      const imageUrl = await storeRemoteImageInR2(env,`portraits/${row.owner_user_id}/${row.character_id}_${row.emotion}_${row.updated_at}.jpg`,url);
      await env.DB.prepare("UPDATE character_emotion_portraits SET image_url=?,status='ready',updated_at=? WHERE character_id=? AND emotion=? AND source_url=? AND job_json=?")
        .bind(imageUrl,Date.now(),row.character_id,row.emotion,row.source_url,row.job_json).run();
    } catch (error) {
      // Transient status errors keep their provider request; never purchase the same image again on a retry.
      if (row.job_json && row.job_json !== "claiming" && row.updated_at > Date.now()-1_800_000 && !(error instanceof AppError && error.code === "FAL_FAILED")) return;
      await env.DB.prepare("UPDATE character_emotion_portraits SET status='failed',updated_at=? WHERE character_id=? AND emotion=? AND source_url=?")
        .bind(Date.now(),row.character_id,row.emotion,row.source_url).run();
    }
  }));
}
