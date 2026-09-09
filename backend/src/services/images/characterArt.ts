import type { Env, RequestContext } from "../../env";
import { getCharacterById } from "../../db/queries/characters";
import { assert, AppError } from "../../lib/errors";
import { createId } from "../../lib/ids";
import { publicAssetUrl } from "../../lib/assets";
import { characterArtSettings, portraitStyle } from "../../providers/openrouterImages";
import { imageJobStatus, queueImage, type QueuedImageJob } from "./jobs";
import { upperBodyPrompt, expressionPrompt } from "./characterArtPrompts";

export const portraitEmotions = ["neutral", "joy", "sadness", "anger", "fear", "affection"] as const;
const schemas = new WeakMap<D1Database, Promise<void>>();
export async function ensureCharacterArtSchema(env: Env) {
  let pending = schemas.get(env.DB);
  if (!pending) {
    pending = env.DB.batch([
      env.DB.prepare(`CREATE TABLE IF NOT EXISTS character_body_art (
        character_id TEXT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
        emotion TEXT NOT NULL, source_url TEXT NOT NULL, attempt_id TEXT NOT NULL,
        image_url TEXT, job_json TEXT, status TEXT NOT NULL, updated_at INTEGER NOT NULL,
        PRIMARY KEY(character_id, emotion))`),
      env.DB.prepare("CREATE INDEX IF NOT EXISTS character_body_art_pending ON character_body_art(status, updated_at)")
    ]).then(() => undefined);
    schemas.set(env.DB, pending); pending.catch(() => schemas.delete(env.DB));
  }
  await pending;
}
async function visibleCharacter(context: RequestContext, id: string, ownerOnly = false) {
  const character = await getCharacterById(context.env, context.user!.userId, id);
  assert(character && (character.visibility !== "private" || character.owner_user_id === context.user!.userId), 404, "CHARACTER_NOT_FOUND", "Character not found.");
  if (ownerOnly) assert(character.owner_user_id === context.user!.userId, 403, "CHARACTER_OWNER_REQUIRED", "Only the creator can generate character artwork.");
  return character;
}
async function initializeArt(context: RequestContext, id: string, source: string | null, retry: boolean) {
  const prefix = publicAssetUrl(context.env.R2_PUBLIC_BASE_URL, `portraits/${context.user!.userId}/reference.jpg`).slice(0, -"reference.jpg".length);
  assert(typeof source === "string" && source.startsWith(prefix), 400, "PORTRAIT_REQUIRED", "Choose a generated or uploaded portrait first.");
  const filename = source.slice(prefix.length);
  assert(/^[a-zA-Z0-9_-]+\.jpg$/.test(filename) && await context.env.ASSETS.head(`portraits/${context.user!.userId}/${filename}`), 400, "PORTRAIT_REQUIRED", "Choose one of your saved portraits first.");
  await ensureCharacterArtSchema(context.env);
  // One transaction establishes the neutral dependency and every expression.
  // Each attempt has a durable ID before any paid provider call can be made.
  await context.env.DB.batch(portraitEmotions.map(emotion => context.env.DB.prepare(`
    INSERT INTO character_body_art(character_id,emotion,source_url,attempt_id,status,updated_at)
    VALUES (?,?,?,?,'generating',?) ON CONFLICT(character_id,emotion) DO UPDATE SET
      source_url=excluded.source_url, attempt_id=excluded.attempt_id, image_url=NULL,
      job_json=NULL, status='generating', updated_at=excluded.updated_at
    WHERE source_url != excluded.source_url OR (? = 1 AND status='failed')`)
    .bind(id, emotion, source, createId("body"), Date.now(), retry ? 1 : 0)));
}
interface ArtState { emotion: string; image_url: string | null; status: string; }
export async function readEmotionPortraits(context: RequestContext, id: string) {
  const character = await visibleCharacter(context, id);
  await ensureCharacterArtSchema(context.env);
  const read = () => context.env.DB.prepare("SELECT emotion,image_url,status FROM character_body_art WHERE character_id=? AND source_url=?")
    .bind(id, character.avatar_url ?? "").all<ArtState>();
  let result = await read();
  // Lazily replace the creator's old opaque artwork; a failed attempt is never
  // repurchased by polling. Other viewers cannot initiate paid generation.
  if (!result.results.length && character.owner_user_id === context.user!.userId && character.avatar_url) {
    try { await initializeArt(context, id, character.avatar_url, false); result = await read(); }
    catch (error) { if (!(error instanceof AppError && error.code === "PORTRAIT_REQUIRED")) throw error; }
  }
  const generating = result.results.some(row => row.status === "generating");
  if (generating) context.waitUntil?.(resumeEmotionPortraits(context.env, id));
  return {
    portraits: Object.fromEntries(result.results.filter(row => row.status === "ready" && row.image_url).map(row => [row.emotion, row.image_url])),
    generating, failed: result.results.some(row => row.status === "failed"),
    format: "transparent-upper-body-v1"
  };
}
export async function generateEmotionPortraits(context: RequestContext, id: string) {
  const character = await visibleCharacter(context, id, true);
  await initializeArt(context, id, character.avatar_url, true);
  if (context.waitUntil) context.waitUntil(resumeEmotionPortraits(context.env, id));
  else await resumeEmotionPortraits(context.env, id);
  return readEmotionPortraits(context, id);
}
interface ArtRow {
  character_id: string; emotion: string; source_url: string; attempt_id: string;
  job_json: string | null; updated_at: number; owner_user_id: string; description: string;
  neutral_url: string | null; neutral_status: string | null;
}
/** Poll durable jobs; expressions depend on one finished neutral upper-body image. */
export async function resumeEmotionPortraits(env: Env, characterId?: string) {
  await ensureCharacterArtSchema(env);
  const result = await env.DB.prepare(`SELECT e.*, c.owner_user_id,c.description,
      n.image_url AS neutral_url,n.status AS neutral_status
    FROM character_body_art e JOIN characters c ON c.id=e.character_id AND c.avatar_url=e.source_url
    LEFT JOIN character_body_art n ON n.character_id=e.character_id AND n.emotion='neutral' AND n.source_url=e.source_url
    WHERE e.status='generating' ${characterId ? "AND e.character_id=?" : ""}
      AND (e.emotion='neutral' OR n.status IN ('ready','failed'))
    ORDER BY e.updated_at ASC LIMIT 12`).bind(...(characterId ? [characterId] : [])).all<ArtRow>();
  for (let start = 0; start < result.results.length; start += 3) await Promise.all(result.results.slice(start, start + 3).map(async row => {
    try {
      if (row.emotion !== "neutral" && row.neutral_status === "failed") throw new AppError(502, "IMAGE_FAILED", "The neutral character artwork could not be created.");
      if (row.updated_at < Date.now() - 1_800_000) throw new AppError(502, "IMAGE_FAILED", "Character artwork generation expired. You can retry it.");
      let job: QueuedImageJob;
      if (row.job_json) job = JSON.parse(row.job_json) as QueuedImageJob;
      else {
        const sourceKey = decodeURIComponent(new URL(row.source_url).pathname.split("/").pop() ?? "");
        const saved = await env.ASSETS.head(sourceKey);
        const knownStyle = saved?.customMetadata?.style;
        const style = knownStyle === "realistic" || knownStyle === "stylized" ? knownStyle : portraitStyle(row.description || "");
        job = await queueImage(env, {
          image: { ...characterArtSettings(env, style), background: "transparent", aspectRatio: "2:3",
            prompt: row.emotion === "neutral" ? upperBodyPrompt : expressionPrompt(row.emotion),
            referenceImageUrl: row.emotion === "neutral" ? row.source_url : row.neutral_url! },
          outputKey: `character-art/${row.owner_user_id}/${row.attempt_id}_${row.emotion}.png`, style, fallback: false
        }, row.attempt_id);
        // Concurrent pollers may queue the same ID; the Durable Object purchases
        // once. An interrupted queue acknowledgement reuses that identical ID.
        await env.DB.prepare("UPDATE character_body_art SET job_json=? WHERE character_id=? AND emotion=? AND attempt_id=? AND status='generating'")
          .bind(JSON.stringify(job), row.character_id, row.emotion, row.attempt_id).run();
      }
      const status = await imageJobStatus(env, job);
      if (status.status === "failed") throw new AppError(502, "IMAGE_FAILED", "Character artwork generation failed.");
      if (status.status !== "completed" || !status.imageUrl) return;
      const key = decodeURIComponent(new URL(status.imageUrl).pathname.split("/").pop() ?? "");
      const expectedKey = `character-art/${row.owner_user_id}/${row.attempt_id}_${row.emotion}.png`;
      const saved = key === expectedKey ? await env.ASSETS.head(key) : null;
      if (saved?.customMetadata?.alpha !== "verified") throw new AppError(502, "IMAGE_FAILED", "The character artwork needs a transparent background.");
      await env.DB.prepare("UPDATE character_body_art SET image_url=?,status='ready',updated_at=? WHERE character_id=? AND emotion=? AND attempt_id=? AND status='generating'")
        .bind(status.imageUrl, Date.now(), row.character_id, row.emotion, row.attempt_id).run();
    } catch (error) {
      // Transport errors keep the same paid job. Failed output needs an explicit retry.
      if (!(error instanceof AppError && error.code === "IMAGE_FAILED")) return;
      await env.DB.prepare("UPDATE character_body_art SET status='failed',updated_at=? WHERE character_id=? AND emotion=? AND attempt_id=? AND status='generating'")
        .bind(Date.now(), row.character_id, row.emotion, row.attempt_id).run();
    }
  }));
}
