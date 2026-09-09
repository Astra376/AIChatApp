import type { Env } from "../../env";
import { all, first, run } from "../client";
import { ensureCharacterSchema } from "../ensureCharacterSchema";
import { activeUltraUserIdsSql, ensureUltraSchema } from "../../services/billing";

export interface CharacterRecord {
  id: string;
  owner_user_id: string;
  owner_display_name: string | null;
  name: string;
  tagline: string;
  greeting: string;
  description: string;
  system_prompt: string;
  definition_private: number;
  visibility: "public" | "unlisted" | "private";
  avatar_url: string | null;
  public_chat_count: number;
  like_count: number;
  liked_by_me: number;
  last_active_at: number;
  created_at: number;
  updated_at: number;
}

export interface PublicCharacterOwnerStats {
  character_count: number;
  interaction_count: number;
  like_count: number;
  created_at: number;
  updated_at: number;
}

export async function insertCharacter(env: Env, input: {
  id: string;
  ownerUserId: string;
  name: string;
  tagline: string;
  greeting: string;
  description: string;
  systemPrompt: string;
  definitionPrivate: boolean;
  visibility: "public" | "unlisted" | "private";
  avatarUrl: string | null;
  now: number;
}, extraStatements: D1PreparedStatement[] = []): Promise<void> {
  await ensureCharacterSchema(env);
  const statement = env.DB.prepare(
      `
      INSERT INTO characters (
        id, owner_user_id, name, tagline, greeting, description, system_prompt, definition_private, visibility,
        avatar_url, public_chat_count, like_count, last_active_at, created_at, updated_at
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)
      `
    ).bind(
      input.id,
      input.ownerUserId,
      input.name,
      input.tagline,
      input.greeting,
      input.description,
      input.systemPrompt,
      input.definitionPrivate ? 1 : 0,
      input.visibility,
      input.avatarUrl,
      input.now,
      input.now,
      input.now
    );
  if (extraStatements.length) await env.DB.batch([statement, ...extraStatements]);
  else await run(statement);
}

export async function updateCharacter(env: Env, input: {
  id: string;
  ownerUserId: string;
  name: string;
  tagline: string;
  greeting: string;
  description: string;
  systemPrompt: string;
  definitionPrivate: boolean;
  visibility: "public" | "unlisted" | "private";
  avatarUrl: string | null;
  now: number;
}, extraStatements: D1PreparedStatement[] = []): Promise<void> {
  await ensureCharacterSchema(env);
  const statement = env.DB.prepare(
      `
      UPDATE characters
      SET name = ?, tagline = ?, greeting = ?, description = ?, system_prompt = ?, definition_private = ?, visibility = ?, avatar_url = ?, updated_at = ?
      WHERE id = ? AND owner_user_id = ?
      `
    ).bind(
      input.name,
      input.tagline,
      input.greeting,
      input.description,
      input.systemPrompt,
      input.definitionPrivate ? 1 : 0,
      input.visibility,
      input.avatarUrl,
      input.now,
      input.id,
      input.ownerUserId
    );
  if (extraStatements.length) await env.DB.batch([statement, ...extraStatements]);
  else await run(statement);
}

export async function getCharacterById(env: Env, userId: string, characterId: string): Promise<CharacterRecord | null> {
  await ensureCharacterSchema(env);
  return first<CharacterRecord>(
    env.DB.prepare(
      `
      SELECT
        characters.*,
        profiles.display_name AS owner_display_name,
        CASE WHEN character_likes.user_id IS NULL THEN 0 ELSE 1 END AS liked_by_me
      FROM characters
      LEFT JOIN profiles ON profiles.user_id = characters.owner_user_id
      LEFT JOIN character_likes
        ON character_likes.character_id = characters.id
        AND character_likes.user_id = ?
      WHERE characters.id = ?
      LIMIT 1
      `
    ).bind(userId, characterId)
  );
}

export async function getOwnedCharacters(env: Env, userId: string, offset: number, limit: number): Promise<CharacterRecord[]> {
  await ensureCharacterSchema(env);
  return all<CharacterRecord>(
    env.DB.prepare(
      `
      SELECT
        characters.*,
        profiles.display_name AS owner_display_name,
        0 AS liked_by_me
      FROM characters
      LEFT JOIN profiles ON profiles.user_id = characters.owner_user_id
      WHERE owner_user_id = ?
      ORDER BY updated_at DESC
      LIMIT ? OFFSET ?
      `
    ).bind(userId, limit, offset)
  );
}

export async function getPublicCharactersByOwner(
  env: Env,
  viewerUserId: string,
  ownerUserId: string,
  offset: number,
  limit: number
): Promise<CharacterRecord[]> {
  await ensureCharacterSchema(env);
  return all<CharacterRecord>(
    env.DB.prepare(
      `
      SELECT
        characters.*,
        profiles.display_name AS owner_display_name,
        CASE WHEN character_likes.user_id IS NULL THEN 0 ELSE 1 END AS liked_by_me
      FROM characters
      LEFT JOIN profiles ON profiles.user_id = characters.owner_user_id
      LEFT JOIN character_likes
        ON character_likes.character_id = characters.id
        AND character_likes.user_id = ?
      WHERE characters.owner_user_id = ?
        AND characters.visibility = 'public'
      ORDER BY characters.updated_at DESC, characters.id DESC
      LIMIT ? OFFSET ?
      `
    ).bind(viewerUserId, ownerUserId, limit, offset)
  );
}

export async function getPublicCharacterOwnerStats(
  env: Env,
  ownerUserId: string
): Promise<PublicCharacterOwnerStats> {
  await ensureCharacterSchema(env);
  const result = await first<PublicCharacterOwnerStats>(
    env.DB.prepare(
      `
      SELECT
        COUNT(*) AS character_count,
        COALESCE(SUM(public_chat_count), 0) AS interaction_count,
        COALESCE(SUM(like_count), 0) AS like_count,
        COALESCE(MIN(created_at), 0) AS created_at,
        COALESCE(MAX(updated_at), 0) AS updated_at
      FROM characters
      WHERE owner_user_id = ?
        AND visibility = 'public'
      `
    ).bind(ownerUserId)
  );
  return result ?? {
    character_count: 0,
    interaction_count: 0,
    like_count: 0,
    created_at: 0,
    updated_at: 0
  };
}

export async function getLikedCharacters(env: Env, userId: string, offset: number, limit: number): Promise<CharacterRecord[]> {
  await ensureCharacterSchema(env);
  return all<CharacterRecord>(
    env.DB.prepare(
      `
      SELECT
        characters.*,
        profiles.display_name AS owner_display_name,
        1 AS liked_by_me
      FROM character_likes
      INNER JOIN characters ON characters.id = character_likes.character_id
      LEFT JOIN profiles ON profiles.user_id = characters.owner_user_id
      WHERE character_likes.user_id = ? AND characters.visibility = 'public'
      ORDER BY characters.updated_at DESC
      LIMIT ? OFFSET ?
      `
    ).bind(userId, limit, offset)
  );
}

// Keep relevance and public visibility authoritative. Entitlement is evaluated once
// inside the query, rather than one network/database lookup per character card.
function discoveryScore(): string {
  return `(CASE WHEN characters.owner_user_id IN (SELECT user_id FROM ultra_creators) THEN 3.0 ELSE 1.0 END) *
    (1.0 + MIN(characters.public_chat_count,10000)*0.003 + MIN(characters.like_count,10000)*0.01
      + 30.0/(1.0 + MAX(0,(? - characters.last_active_at)/86400000.0)))`;
}

export async function getPublicFeed(env: Env, userId: string, offset: number, limit: number): Promise<CharacterRecord[]> {
  await Promise.all([ensureCharacterSchema(env), ensureUltraSchema(env)]);
  const creators = activeUltraUserIdsSql(env);
  return all<CharacterRecord>(env.DB.prepare(`
    WITH ultra_creators AS (${creators.sql})
    SELECT characters.*, profiles.display_name AS owner_display_name,
      CASE WHEN character_likes.user_id IS NULL THEN 0 ELSE 1 END AS liked_by_me
    FROM characters
    LEFT JOIN profiles ON profiles.user_id = characters.owner_user_id
    LEFT JOIN character_likes ON character_likes.character_id = characters.id AND character_likes.user_id = ?
    WHERE characters.visibility = 'public'
    ORDER BY ${discoveryScore()} DESC, characters.last_active_at DESC, characters.id ASC
    LIMIT ? OFFSET ?
  `).bind(...creators.bindings, userId, Date.now(), limit, offset));
}

export async function searchPublicCharacters(
  env: Env, userId: string, query: string, offset: number, limit: number
): Promise<CharacterRecord[]> {
  await Promise.all([ensureCharacterSchema(env), ensureUltraSchema(env)]);
  const creators = activeUltraUserIdsSql(env);
  const literal = query.replace(/[\\%_]/g, value => `\\${value}`);
  const contains = `%${literal}%`;
  return all<CharacterRecord>(env.DB.prepare(`
    WITH ultra_creators AS (${creators.sql})
    SELECT characters.*, profiles.display_name AS owner_display_name,
      CASE WHEN character_likes.user_id IS NULL THEN 0 ELSE 1 END AS liked_by_me
    FROM characters
    LEFT JOIN profiles ON profiles.user_id = characters.owner_user_id
    LEFT JOIN character_likes ON character_likes.character_id = characters.id AND character_likes.user_id = ?
    WHERE characters.visibility = 'public' AND (
      characters.name LIKE ? ESCAPE '\\' OR characters.tagline LIKE ? ESCAPE '\\' OR characters.description LIKE ? ESCAPE '\\')
    ORDER BY CASE WHEN characters.name = ? COLLATE NOCASE THEN 3
      WHEN characters.name LIKE ? ESCAPE '\\' THEN 2
      WHEN characters.name LIKE ? ESCAPE '\\' THEN 1 ELSE 0 END DESC,
      ${discoveryScore()} DESC, characters.last_active_at DESC, characters.id ASC
    LIMIT ? OFFSET ?
  `).bind(...creators.bindings, userId, contains, contains, contains, query, `${literal}%`, contains, Date.now(), limit, offset));
}

export async function likeCharacter(env: Env, userId: string, characterId: string, now: number): Promise<void> {
  await ensureCharacterSchema(env);
  await env.DB.batch([
    env.DB.prepare(
      `
      UPDATE characters
      SET like_count = like_count + 1
      WHERE id = ? AND visibility = 'public'
        AND NOT EXISTS (
          SELECT 1 FROM character_likes WHERE user_id = ? AND character_id = ?
        )
      `
    ).bind(characterId, userId, characterId),
    env.DB.prepare(
      `
      INSERT OR IGNORE INTO character_likes (user_id, character_id, created_at)
      VALUES (?, ?, ?)
      `
    ).bind(userId, characterId, now)
  ]);
}

export async function unlikeCharacter(env: Env, userId: string, characterId: string): Promise<void> {
  await ensureCharacterSchema(env);
  await env.DB.batch([
    env.DB.prepare(
      `
      UPDATE characters
      SET like_count = CASE WHEN like_count > 0 THEN like_count - 1 ELSE 0 END
      WHERE id = ?
        AND EXISTS (
          SELECT 1 FROM character_likes WHERE user_id = ? AND character_id = ?
        )
      `
    ).bind(characterId, userId, characterId),
    env.DB.prepare("DELETE FROM character_likes WHERE user_id = ? AND character_id = ?").bind(userId, characterId)
  ]);
}

export async function incrementCharacterActivity(env: Env, characterId: string, now: number): Promise<void> {
  await ensureCharacterSchema(env);
  await run(
    env.DB.prepare(
      `
      UPDATE characters
      SET public_chat_count = public_chat_count + 1, last_active_at = ?
      WHERE id = ?
      `
    ).bind(now, characterId)
  );
}
