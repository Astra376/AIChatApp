import { hasUltra, requireUltra } from "../billing";
import type { Env, RequestContext } from "../../env";
import { AppError, assert } from "../../lib/errors";

export const OFFICIAL_VOICES = ["Vivian", "Serena", "Uncle_Fu", "Dylan", "Eric", "Ryan", "Aiden", "Ono_Anna", "Sohee"];
const PREVIEW_TEXT = "Hello there. I was just thinking about our next adventure. Tell me, where should we go today?";
const schema = new WeakMap<D1Database, Promise<void>>();
export async function ensureVoiceSchema(env: Env): Promise<void> {
  let pending = schema.get(env.DB);
  if (!pending) {
    pending = env.DB.batch([
      env.DB.prepare(`CREATE TABLE IF NOT EXISTS voices (id TEXT PRIMARY KEY, owner_user_id TEXT NOT NULL,
        name TEXT NOT NULL, description TEXT NOT NULL, visibility TEXT NOT NULL DEFAULT 'private',
        embedding_key TEXT NOT NULL, preview_key TEXT, created_at INTEGER NOT NULL)`),
      env.DB.prepare("CREATE INDEX IF NOT EXISTS voices_owner ON voices(owner_user_id)"),
      env.DB.prepare("CREATE TABLE IF NOT EXISTS character_voices (character_id TEXT PRIMARY KEY, voice_id TEXT NOT NULL)"),
      env.DB.prepare("CREATE TABLE IF NOT EXISTS voice_jobs (job_key TEXT PRIMARY KEY, user_id TEXT NOT NULL, started_at INTEGER NOT NULL)"),
      env.DB.prepare("CREATE INDEX IF NOT EXISTS voice_jobs_user_started ON voice_jobs(user_id,started_at)")
    ]).then(() => undefined);
    schema.set(env.DB, pending);
    pending.catch(() => schema.delete(env.DB));
  }
  await pending;
}
interface Voice { id: string; owner_user_id: string; name: string; description: string; visibility: string; embedding_key: string; preview_key: string | null; }
function dto(voice: Voice, viewer: string) {
  return { id: voice.id, name: voice.name, description: voice.description, official: false,
    mine: voice.owner_user_id === viewer, public: voice.visibility === "public" };
}
export async function listVoices(context: RequestContext) {
  await ensureVoiceSchema(context.env);
  const voices = await context.env.DB.prepare("SELECT * FROM voices WHERE owner_user_id = ? OR visibility = 'public' ORDER BY created_at DESC LIMIT 100")
    .bind(context.user!.userId).all<Voice>();
  return { available: Boolean(context.env.FAL_API_KEY?.trim()), canCreate: await hasUltra(context.env, context.user!.userId), items: [
    ...OFFICIAL_VOICES.map(name => ({ id: `official:${name}`, name: name.replaceAll("_", " "), description: "Qwen3 official voice", official: true, mine: false, public: true })),
    ...voices.results.map(voice => dto(voice, context.user!.userId))
  ] };
}
async function voiceForUser(context: RequestContext, id: string): Promise<Voice | null> {
  if (id.startsWith("official:")) {
    assert(OFFICIAL_VOICES.includes(id.slice(9)), 400, "VOICE_NOT_FOUND", "Choose an available voice.");
    return null;
  }
  const voice = await context.env.DB.prepare("SELECT * FROM voices WHERE id = ? AND (owner_user_id = ? OR visibility = 'public')")
    .bind(id, context.user!.userId).first<Voice>();
  assert(voice, 404, "VOICE_NOT_FOUND", "This voice is not available.");
  return voice;
}
export async function characterVoiceStatement(context: RequestContext, characterId: string, voiceId: string, visibility: string): Promise<D1PreparedStatement> {
  await ensureVoiceSchema(context.env);
  const voice = await voiceForUser(context, voiceId);
  assert(!voice || voice.visibility === "public" || visibility === "private", 400, "VOICE_PRIVATE", "Use a community voice for a public or unlisted character.");
  return context.env.DB.prepare("INSERT INTO character_voices(character_id,voice_id) VALUES (?,?) ON CONFLICT(character_id) DO UPDATE SET voice_id=excluded.voice_id").bind(characterId, voiceId);
}
export async function setCharacterVoice(context: RequestContext, characterId: string, voiceId: string) {
  await ensureVoiceSchema(context.env);
  const character = await context.env.DB.prepare("SELECT id FROM characters WHERE id = ? AND owner_user_id = ?")
    .bind(characterId, context.user!.userId).first();
  assert(character, 404, "CHARACTER_NOT_FOUND", "Character not found.");
  const voice = await voiceForUser(context, voiceId);
  // A published character must not distribute a creator's private voice.
  if (voice?.visibility === "private") {
    const visibility = await context.env.DB.prepare("SELECT visibility FROM characters WHERE id = ?").bind(characterId).first<{ visibility: string }>();
    assert(visibility?.visibility === "private", 400, "VOICE_PRIVATE", "Publish this voice to use it with a public or unlisted character.");
  }
  await context.env.DB.prepare("INSERT INTO character_voices(character_id,voice_id) VALUES (?,?) ON CONFLICT(character_id) DO UPDATE SET voice_id=excluded.voice_id")
    .bind(characterId, voiceId).run();
  return { voiceId };
}

async function hmac(env: Env, value: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.SESSION_HMAC_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return Array.from(new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value))), byte => byte.toString(16).padStart(2, "0")).join("");
}
async function signedAsset(context: RequestContext, key: string): Promise<string> {
  const expires = Date.now() + 10 * 60_000;
  const signature = await hmac(context.env, `${key}:${expires}`);
  return `${context.url.origin}/v1/voice-assets/${encodeURIComponent(key)}?expires=${expires}&signature=${signature}`;
}
export async function getVoiceAsset(context: RequestContext) {
  const key = context.params.key;
  const expires = Number(context.url.searchParams.get("expires"));
  const signature = context.url.searchParams.get("signature") ?? "";
  assert(/^voice\/[a-zA-Z0-9_/-]+\.(mp3|wav|m4a|safetensors)$/.test(key)
    && Number.isFinite(expires) && expires > Date.now() && expires <= Date.now() + 10 * 60_000,
    403, "ASSET_EXPIRED", "Audio link expired.");
  const expected = await hmac(context.env, `${key}:${expires}`);
  let mismatch = signature.length ^ expected.length;
  for (let i = 0; i < expected.length; i++) mismatch |= (signature.charCodeAt(i) || 0) ^ expected.charCodeAt(i);
  assert(mismatch === 0, 403, "ASSET_EXPIRED", "Audio link expired.");
  const asset = await context.env.ASSETS.get(key);
  assert(asset, 404, "ASSET_NOT_FOUND", "Audio not found.");
  return new Response(asset.body, { headers: { "Content-Type": asset.httpMetadata?.contentType ?? "application/octet-stream", "Cache-Control": "private, max-age=300", "X-Content-Type-Options": "nosniff" } });
}

// Single submission only: retries of a paid inference can duplicate charges.
async function infer(env: Env, task: "text-to-speech" | "voice-design" | "clone-voice", input: object) {
  assert(Boolean(env.FAL_API_KEY?.trim()), 503, "VOICE_UNAVAILABLE", "Voices are not available yet.");
  const response = await fetch(`https://fal.run/fal-ai/qwen-3-tts/${task}/1.7b`, {
    method: "POST", headers: { Authorization: `Key ${env.FAL_API_KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify(input), signal: AbortSignal.timeout(90_000)
  });
  assert(response.ok, 502, "VOICE_PROVIDER_ERROR", "The voice could not be generated. Please try again.");
  return response.json() as Promise<{ audio?: { url: string }; speaker_embedding?: { url: string } }>;
}
function providerAssetUrl(value: string | undefined): string {
  assert(value, 502, "VOICE_PROVIDER_ERROR", "The voice provider returned no audio.");
  const url = new URL(value);
  assert(url.protocol === "https:" && !url.username && !url.password
    && (url.hostname.endsWith(".fal.media") || url.hostname === "fal.media" || url.hostname === "storage.googleapis.com"),
    502, "VOICE_PROVIDER_ERROR", "The voice provider returned an invalid asset.");
  return url.toString();
}
async function saveProviderAsset(context: RequestContext, url: string | undefined, key: string, contentType: string) {
  const response = await fetch(providerAssetUrl(url), { signal: AbortSignal.timeout(20_000), redirect: "error" });
  assert(response.ok && Number(response.headers.get("Content-Length") ?? 0) <= 20_000_000, 502, "VOICE_PROVIDER_ERROR", "Audio download failed.");
  const bytes = await response.arrayBuffer();
  assert(bytes.byteLength <= 20_000_000, 502, "VOICE_PROVIDER_ERROR", "Audio file is too large.");
  await context.env.ASSETS.put(key, bytes, { httpMetadata: { contentType } });
}
async function acquireJob(context: RequestContext, key: string, limit: number) {
  const now = Date.now();
  const result = await context.env.DB.prepare(`INSERT INTO voice_jobs(job_key,user_id,started_at)
    SELECT ?,?,? WHERE (SELECT COUNT(*) FROM voice_jobs WHERE user_id=? AND started_at>?) < ?
    ON CONFLICT(job_key) DO UPDATE SET started_at=excluded.started_at WHERE voice_jobs.started_at < ?`)
    .bind(key, context.user!.userId, now, context.user!.userId, now - 3_600_000, limit, now - 180_000).run();
  assert(result.meta.changes === 1, 429, "VOICE_BUSY", "Voice is already being prepared or the hourly limit was reached.");
}
export async function createVoice(context: RequestContext, input: { name: string; description: string; sample?: File; public: boolean; requestKey: string }) {
  await requireUltra(context.env, context.user!.userId, "Creating custom voices");
  await ensureVoiceSchema(context.env);
  assert(/^[a-zA-Z0-9_-]{16,100}$/.test(input.requestKey), 400, "INVALID_REQUEST", "Invalid voice request.");
  const id = `custom_${context.user!.userId.replace(/[^a-zA-Z0-9_-]/g, "_")}_${input.requestKey}`;
  const existing = await context.env.DB.prepare("SELECT * FROM voices WHERE id=? AND owner_user_id=?").bind(id, context.user!.userId).first<Voice>();
  if (existing) return dto(existing, context.user!.userId);
  assert(input.sample || input.description.trim().length >= 10, 400, "VOICE_DESCRIPTION_REQUIRED", "Describe the voice or choose a sample.");
  await acquireJob(context, `create/${id}`, 10);
  const previewKey = `voice/${id}/preview.mp3`;
  let sourceUrl: string;
  let sampleKey: string | undefined;
  if (input.sample) {
    assert(input.sample.size >= 1000 && input.sample.size <= 10_000_000, 400, "VOICE_SAMPLE_INVALID", "Choose an audio sample under 10 MB.");
    assert(["audio/mp4", "audio/mpeg", "audio/wav", "audio/x-wav", "audio/aac", "audio/flac", "audio/ogg"].includes(input.sample.type), 400, "VOICE_SAMPLE_INVALID", "Choose an audio recording or a video with an audio track.");
    sampleKey = `voice/${id}/sample.m4a`;
    await context.env.ASSETS.put(sampleKey, await input.sample.arrayBuffer(), { httpMetadata: { contentType: input.sample.type } });
    sourceUrl = await signedAsset(context, sampleKey);
  } else {
    const designed = await infer(context.env, "voice-design", { text: PREVIEW_TEXT, prompt: input.description, max_new_tokens: 2048 });
    await saveProviderAsset(context, designed.audio?.url, previewKey, "audio/mpeg");
    sourceUrl = await signedAsset(context, previewKey);
  }
  try {
    const cloned = await infer(context.env, "clone-voice", { audio_url: sourceUrl, ...(!input.sample ? { reference_text: PREVIEW_TEXT } : {}) });
    const embeddingKey = `voice/${id}/voice.safetensors`;
    await saveProviderAsset(context, cloned.speaker_embedding?.url, embeddingKey, "application/octet-stream");
    await context.env.DB.prepare("INSERT OR IGNORE INTO voices(id,owner_user_id,name,description,visibility,embedding_key,preview_key,created_at) VALUES (?,?,?,?,?,?,?,?)")
      .bind(id, context.user!.userId, input.name, input.description, input.public ? "public" : "private", embeddingKey, input.sample ? null : previewKey, Date.now()).run();
    return { id, name: input.name, description: input.description, official: false, mine: true, public: input.public };
  } finally {
    if (sampleKey) await context.env.ASSETS.delete(sampleKey);
  }
}
export function speechText(content: string): string {
  return content.replace(/```[\s\S]*?```/g, "").replace(/[*_`#]/g, "").trim();
}
export async function speakMessage(context: RequestContext, input: { conversationId: string; messageId: string }) {
  await ensureVoiceSchema(context.env);
  const message = await context.env.DB.prepare(`SELECT COALESCE(r.content,m.content) AS content, c.character_id FROM messages m
    LEFT JOIN assistant_regenerations r ON r.id=m.selected_regeneration_id AND r.message_id=m.id
    JOIN conversations c ON c.id=m.conversation_id WHERE m.id=? AND c.id=? AND c.owner_user_id=? AND m.role='assistant'`)
    .bind(input.messageId, input.conversationId, context.user!.userId).first<{ content: string; character_id: string }>();
  assert(message, 404, "MESSAGE_NOT_FOUND", "Message not found.");
  const chosen = await context.env.DB.prepare("SELECT voice_id FROM character_voices WHERE character_id=?").bind(message.character_id).first<{ voice_id: string }>();
  return synthesize(context, chosen?.voice_id ?? "official:Ryan", speechText(message.content));
}
export async function previewVoice(context: RequestContext, voiceId: string) {
  await ensureVoiceSchema(context.env);
  const voice = await voiceForUser(context, voiceId);
  if (voice?.preview_key && await context.env.ASSETS.head(voice.preview_key)) {
    return { audioUrl: await signedAsset(context, voice.preview_key) };
  }
  return synthesize(context, voiceId, PREVIEW_TEXT);
}
async function synthesize(context: RequestContext, voiceId: string, text: string) {
  assert(text.length > 0 && text.length <= 6000, 400, "VOICE_TEXT_TOO_LONG", "Read aloud supports messages up to 6,000 characters.");
  const voice = await voiceForUser(context, voiceId);
  const hash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${context.user!.userId}:${voiceId}:${text}`))), byte => byte.toString(16).padStart(2,"0")).join("");
  const key = `voice/cache/${hash}.mp3`;
  if (await context.env.ASSETS.head(key)) return { audioUrl: await signedAsset(context, key) };
  await acquireJob(context, `tts/${hash}`, 60);
  const generated = await infer(context.env, "text-to-speech", { text, language: "Auto", max_new_tokens: 4096,
    ...(voice ? { speaker_voice_embedding_file_url: await signedAsset(context, voice.embedding_key) } : { voice: voiceId.slice(9) }) });
  await saveProviderAsset(context, generated.audio?.url, key, "audio/mpeg");
  return { audioUrl: await signedAsset(context, key) };
}
