import { requireUltra } from "./billing";
import { getCharacterDefaultPersona } from "./personas";
import type { Env, RequestContext } from "../env";
import { AppError, assert } from "../lib/errors";
import { getCharacterById } from "../db/queries/characters";
import { completeChatText } from "../providers/openrouter";

export const emotionKeys = ["trust", "affection", "stress", "energy", "openness", "joy", "sadness", "anger", "fear", "curiosity", "jealousy", "hope", "loneliness", "shame", "pride", "guilt"] as const;
export const personalityKeys = ["warmth", "confidence", "playfulness", "formality", "assertiveness", "volatility", "resilience", "adaptability"] as const;
export interface CharacterPsychologyDefaults {
  advancedDefinition: string;
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
    advancedDefinition: text(root.advancedDefinition, 8000),
    emotions: { ...scores(em, emotionKeys, 30), mood: text(em.mood, 100) || "Grounded", reason: text(em.reason, 500) },
    personality: { ...scores(pe, personalityKeys, 50), description: text(pe.description, 1500) },
    psychology: { cornerstone: text(ps.cornerstone, 1200), beliefs: lines(ps.beliefs), desires: lines(ps.desires), secretDesires: lines(ps.secretDesires), lifeStory: text(ps.lifeStory, 8000), dailyLife: text(ps.dailyLife), relationships: text(ps.relationships), significantEvents: lines(ps.significantEvents) },
    defaultPersona: { name: text(persona.name, 80), backstory: text(persona.backstory, 4000) }
  };
}
export async function characterPsychologyStatement(env: Env, characterId: string, input: unknown, ownerId?: string) {
  await ensureCharacterPsychologySchema(env);
  const previous = await getCharacterPsychologyDefaults(env, characterId);
  const value = normalizeCharacterPsychology(input);
  const raw = object(input);
  if (raw.advancedDefinition === undefined && previous) value.advancedDefinition = previous.advancedDefinition;
  assert(typeof raw.advancedDefinition !== "string" || raw.advancedDefinition.length <= 8000, 400, "ADVANCED_DEFINITION_LIMIT", "Ultra character detail is limited to 8,000 characters.");
  if (value.advancedDefinition !== (previous?.advancedDefinition ?? "")) {
    assert(ownerId, 403, "ULTRA_REQUIRED", "Advanced character detail requires Ultra.");
    await requireUltra(env, ownerId, "Advanced character detail");
  }
  return env.DB.prepare(`INSERT INTO character_psychology (character_id, defaults_json, updated_at) VALUES (?, ?, ?) ON CONFLICT(character_id) DO UPDATE SET defaults_json = excluded.defaults_json, updated_at = excluded.updated_at`)
    .bind(characterId, JSON.stringify({ ...value, defaultPersona: { name: "", backstory: "" } }), Date.now());
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
  await (await characterPsychologyStatement(context.env, id, value, context.user!.userId)).run();
  return getCharacterPsychologyDefaults(context.env, id);
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
export { portraitEmotions, readEmotionPortraits, generateEmotionPortraits, resumeEmotionPortraits } from "./images/characterArt";
