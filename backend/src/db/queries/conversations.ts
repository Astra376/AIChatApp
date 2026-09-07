import { AppError } from "../../lib/errors";
import type { Env } from "../../env";
import { all, first, run } from "../client";

export interface ConversationRecord {
  id: string;
  owner_user_id: string;
  character_id: string;
  updated_at: number;
  started_at: number;
  last_message_at: number | null;
  version: number;
  active_run_id: string | null;
  active_run_expires_at: number | null;
}

export interface ConversationSummaryRecord {
  id: string;
  character_id: string;
  character_name: string;
  character_avatar_url: string | null;
  updated_at: number;
  started_at: number;
  last_message_at: number | null;
  last_preview: string;
  unread_count: number;
  has_unread_badge: number;
}

export interface MessageRecord {
  id: string;
  conversation_id: string;
  position: number;
  role: "user" | "assistant";
  content: string;
  edited: number;
  created_at: number;
  updated_at: number;
  selected_regeneration_id: string | null;
}

export interface AssistantRegenerationRecord {
  id: string;
  message_id: string;
  content: string;
  created_at: number;
}

export async function listConversationSummaries(
  env: Env,
  userId: string,
  offset: number,
  limit: number
): Promise<ConversationSummaryRecord[]> {
  return all<ConversationSummaryRecord>(
    env.DB.prepare(
      `
      SELECT
        conversations.id,
        conversations.character_id,
        characters.name AS character_name,
        characters.avatar_url AS character_avatar_url,
        conversations.updated_at,
        conversations.started_at,
        conversations.last_message_at,
        COALESCE(selected_regenerations.content, latest_assistant_messages.content, '') AS last_preview,
        conversations.unread_count,
        conversations.has_unread_badge
      FROM conversations
      INNER JOIN characters ON characters.id = conversations.character_id
      LEFT JOIN messages AS latest_assistant_messages
        ON latest_assistant_messages.id = (
          SELECT messages.id
          FROM messages
          WHERE messages.conversation_id = conversations.id
            AND messages.role = 'assistant'
          ORDER BY messages.position DESC, messages.created_at DESC, messages.updated_at DESC, messages.id DESC
          LIMIT 1
        )
      LEFT JOIN assistant_regenerations AS selected_regenerations
        ON selected_regenerations.id = latest_assistant_messages.selected_regeneration_id
      WHERE conversations.owner_user_id = ?
      ORDER BY conversations.updated_at DESC
      LIMIT ? OFFSET ?
      `
    ).bind(userId, limit, offset)
  );
}

export async function getConversationSummaryById(
  env: Env,
  userId: string,
  conversationId: string
): Promise<ConversationSummaryRecord | null> {
  return first<ConversationSummaryRecord>(
    env.DB.prepare(
      `
      SELECT
        conversations.id,
        conversations.character_id,
        characters.name AS character_name,
        characters.avatar_url AS character_avatar_url,
        conversations.updated_at,
        conversations.started_at,
        conversations.last_message_at,
        COALESCE(selected_regenerations.content, latest_assistant_messages.content, '') AS last_preview,
        conversations.unread_count,
        conversations.has_unread_badge
      FROM conversations
      INNER JOIN characters ON characters.id = conversations.character_id
      LEFT JOIN messages AS latest_assistant_messages
        ON latest_assistant_messages.id = (
          SELECT messages.id
          FROM messages
          WHERE messages.conversation_id = conversations.id
            AND messages.role = 'assistant'
          ORDER BY messages.position DESC, messages.created_at DESC, messages.updated_at DESC, messages.id DESC
          LIMIT 1
        )
      LEFT JOIN assistant_regenerations AS selected_regenerations
        ON selected_regenerations.id = latest_assistant_messages.selected_regeneration_id
      WHERE conversations.owner_user_id = ? AND conversations.id = ?
      LIMIT 1
      `
    ).bind(userId, conversationId)
  );
}

export async function getConversationById(env: Env, conversationId: string): Promise<ConversationRecord | null> {
  return first<ConversationRecord>(
    env.DB.prepare("SELECT * FROM conversations WHERE id = ? LIMIT 1").bind(conversationId)
  );
}

export async function findConversationByOwnerAndCharacter(
  env: Env,
  ownerUserId: string,
  characterId: string
): Promise<ConversationRecord | null> {
  return first<ConversationRecord>(
    env.DB.prepare(
      `
      SELECT *
      FROM conversations
      WHERE owner_user_id = ? AND character_id = ?
      ORDER BY updated_at DESC, started_at DESC, id DESC
      LIMIT 1
      `
    ).bind(ownerUserId, characterId)
  );
}

export async function insertConversation(env: Env, input: {
  id: string;
  ownerUserId: string;
  characterId: string;
  now: number;
}): Promise<void> {
  await run(
    env.DB.prepare(
      `
      INSERT INTO conversations (
        id, owner_user_id, character_id, updated_at, started_at, last_message_at, version, active_run_id, active_run_expires_at
      )
      VALUES (?, ?, ?, ?, ?, NULL, 0, NULL, NULL)
      `
    ).bind(input.id, input.ownerUserId, input.characterId, input.now, input.now)
  );
}

export async function listMessages(env: Env, conversationId: string): Promise<MessageRecord[]> {
  return all<MessageRecord>(
    env.DB.prepare(
      "SELECT * FROM messages WHERE conversation_id = ? ORDER BY position ASC"
    ).bind(conversationId)
  );
}

// The model needs visible versions, not every discarded regeneration. Bound
// the database read as well as the prompt; durable history lives in memory.
export async function listContextMessages(env: Env, conversationId: string): Promise<MessageRecord[]> {
  const messages = await all<MessageRecord>(env.DB.prepare(`
    SELECT m.id, m.conversation_id, m.position, m.role,
      COALESCE(r.content, m.content) AS content, m.edited, m.created_at, m.updated_at,
      m.selected_regeneration_id
    FROM messages m
    LEFT JOIN assistant_regenerations r ON r.id = m.selected_regeneration_id AND r.message_id = m.id
    WHERE m.conversation_id = ?
    ORDER BY m.position DESC LIMIT 256
  `).bind(conversationId));
  return messages.reverse();
}

export async function listRegenerationsForConversation(
  env: Env,
  conversationId: string
): Promise<AssistantRegenerationRecord[]> {
  return all<AssistantRegenerationRecord>(
    env.DB.prepare(
      `
      SELECT assistant_regenerations.*
      FROM assistant_regenerations
      INNER JOIN messages ON messages.id = assistant_regenerations.message_id
      WHERE messages.conversation_id = ?
      ORDER BY assistant_regenerations.created_at ASC
      `
    ).bind(conversationId)
  );
}

export async function getMessageById(env: Env, messageId: string): Promise<MessageRecord | null> {
  return first<MessageRecord>(
    env.DB.prepare("SELECT * FROM messages WHERE id = ? LIMIT 1").bind(messageId)
  );
}

export async function insertMessage(env: Env, input: MessageRecord, runId?: string): Promise<void> {
  const result = await run(
    env.DB.prepare(
      `
      INSERT INTO messages (
        id, conversation_id, position, role, content, edited, created_at, updated_at, selected_regeneration_id
      )
      ${runId ? `SELECT ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (
        SELECT 1 FROM conversations WHERE id = ? AND active_run_id = ? AND active_run_expires_at > ?
      )` : "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"}
      `
    ).bind(
      input.id,
      input.conversation_id,
      input.position,
      input.role,
      input.content,
      input.edited,
      input.created_at,
      input.updated_at,
      input.selected_regeneration_id,
      ...(runId ? [input.conversation_id, runId, Date.now()] : [])
    )
  );
  if (runId && Number(result.meta.changes) === 0) throw new AppError(409, "RUN_CANCELLED", "The reply was stopped.");
}

export async function updateMessageContent(env: Env, input: {
  messageId: string;
  content: string;
  edited: boolean;
  updatedAt: number;
}): Promise<void> {
  await run(
    env.DB.prepare(
      `
      UPDATE messages
      SET content = ?, edited = ?, updated_at = ?
      WHERE id = ?
      `
    ).bind(input.content, input.edited ? 1 : 0, input.updatedAt, input.messageId)
  );
}

export async function updateMessageSelection(env: Env, input: {
  messageId: string;
  selectedRegenerationId: string | null;
  updatedAt: number;
  edited?: boolean;
}, runId?: string): Promise<void> {
  await run(
    env.DB.prepare(
      `
      UPDATE messages
      SET selected_regeneration_id = ?, updated_at = ?, edited = COALESCE(?, edited)
      WHERE id = ?
      ${runId ? `AND EXISTS (SELECT 1 FROM conversations WHERE conversations.id = messages.conversation_id
        AND active_run_id = ? AND active_run_expires_at > ?)` : ""}
      `
    ).bind(input.selectedRegenerationId, input.updatedAt, input.edited == null ? null : input.edited ? 1 : 0, input.messageId, ...(runId ? [runId, Date.now()] : []))
  );
}

export async function deleteMessagesAfter(env: Env, conversationId: string, position: number): Promise<void> {
  await run(
    env.DB.prepare(
      "DELETE FROM messages WHERE conversation_id = ? AND position > ?"
    ).bind(conversationId, position)
  );
}

export async function insertRegeneration(env: Env, input: AssistantRegenerationRecord, runId?: string): Promise<void> {
  const result = await run(
    env.DB.prepare(
      `
      INSERT INTO assistant_regenerations (id, message_id, content, created_at)
      ${runId ? `SELECT ?, ?, ?, ? WHERE EXISTS (
        SELECT 1 FROM conversations JOIN messages ON messages.conversation_id = conversations.id
        WHERE messages.id = ? AND active_run_id = ? AND active_run_expires_at > ?
      )` : "VALUES (?, ?, ?, ?)"}
      `
    ).bind(input.id, input.message_id, input.content, input.created_at, ...(runId ? [input.message_id, runId, Date.now()] : []))
  );
  if (runId && Number(result.meta.changes) === 0) throw new AppError(409, "RUN_CANCELLED", "The reply was stopped.");
}

export async function updateRegenerationContent(env: Env, regenerationId: string, content: string): Promise<void> {
  await run(
    env.DB.prepare(
      `
      UPDATE assistant_regenerations
      SET content = ?
      WHERE id = ?
      `
    ).bind(content, regenerationId)
  );
}

export async function updateConversationActivity(env: Env, conversationId: string, now: number): Promise<void> {
  await run(
    env.DB.prepare(
      `
      UPDATE conversations
      SET updated_at = ?, last_message_at = ?, version = version + 1
      WHERE id = ?
      `
    ).bind(now, now, conversationId)
  );
}

export async function claimConversationRun(
  env: Env,
  conversationId: string,
  runId: string,
  now: number,
  expiresAt: number
): Promise<boolean> {
  const result = await run(
    env.DB.prepare(
      `
      UPDATE conversations
      SET active_run_id = ?, active_run_expires_at = ?
      WHERE id = ?
        AND (
          active_run_id IS NULL
          OR active_run_expires_at IS NULL
          OR active_run_expires_at <= ?
        )
      `
    ).bind(runId, expiresAt, conversationId, now)
  );
  return Number(result.meta.changes ?? 0) > 0;
}

export async function releaseConversationRun(env: Env, conversationId: string, runId: string): Promise<void> {
  await run(
    env.DB.prepare(
      `
      UPDATE conversations
      SET active_run_id = NULL, active_run_expires_at = NULL
      WHERE id = ? AND active_run_id = ?
      `
    ).bind(conversationId, runId)
  );
}

export interface StoppedReplySnapshot {
  messageId: string;
  text: string;
  regenerate: boolean;
}

// A stop request can reach a different Worker before socket cancellation reaches
// the streaming Worker. Save the visible text and release its lease atomically.
export async function finishStoppedConversationRun(
  env: Env, conversationId: string, runId: string, partial?: StoppedReplySnapshot
): Promise<void> {
  if (!partial?.text.trim()) return releaseConversationRun(env, conversationId, runId);
  const now = Date.now();
  const statements: D1PreparedStatement[] = [];
  if (partial.regenerate) {
    const regenerationId = `regen_${runId}`;
    statements.push(env.DB.prepare(`
      INSERT OR IGNORE INTO assistant_regenerations (id, message_id, content, created_at)
      SELECT ?, m.id, ?, ? FROM messages m JOIN conversations c ON c.id = m.conversation_id
      WHERE c.id = ? AND c.active_run_id = ? AND c.active_run_expires_at > ?
        AND m.id = ? AND m.role = 'assistant'
        AND NOT EXISTS (SELECT 1 FROM messages later WHERE later.conversation_id = c.id AND later.position > m.position)
    `).bind(regenerationId, partial.text, now, conversationId, runId, now, partial.messageId));
    statements.push(env.DB.prepare(`
      UPDATE messages SET selected_regeneration_id = ?, updated_at = ?
      WHERE id = ? AND conversation_id = ?
        AND EXISTS (SELECT 1 FROM conversations WHERE id = ? AND active_run_id = ? AND active_run_expires_at > ?)
        AND EXISTS (SELECT 1 FROM assistant_regenerations WHERE id = ? AND message_id = messages.id)
    `).bind(regenerationId, now, partial.messageId, conversationId, conversationId, runId, now, regenerationId));
  } else {
    // IDs are derived from the accepted run; a snapshot cannot replace history.
    if (partial.messageId !== `message_${runId}`) {
      throw new AppError(400, "INVALID_STOPPED_REPLY", "The stopped reply does not match this run.");
    }
    statements.push(env.DB.prepare(`
      INSERT OR IGNORE INTO messages
        (id, conversation_id, position, role, content, edited, created_at, updated_at, selected_regeneration_id)
      SELECT ?, c.id, (SELECT COALESCE(MAX(position), -1) + 1 FROM messages WHERE conversation_id = c.id),
        'assistant', ?, 0, ?, ?, NULL FROM conversations c
      WHERE c.id = ? AND c.active_run_id = ? AND c.active_run_expires_at > ?
    `).bind(partial.messageId, partial.text, now, now, conversationId, runId, now));
  }
  statements.push(env.DB.prepare(`
    UPDATE conversations SET active_run_id = NULL, active_run_expires_at = NULL,
      updated_at = ?, last_message_at = ?, version = version + 1
    WHERE id = ? AND active_run_id = ?
  `).bind(now, now, conversationId, runId));
  await env.DB.batch(statements);
}
