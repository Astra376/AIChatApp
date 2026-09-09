import type { Env } from "../../env";
import { AppError } from "../../lib/errors";

// These checks run inside the same D1 transaction as the write. Checking a
// lease only in the service leaves a race with a newly accepted generation.
const unlockedOwner = `EXISTS (
  SELECT 1 FROM conversations c WHERE c.id = ? AND c.owner_user_id = ?
    AND (c.active_run_id IS NULL OR c.active_run_expires_at IS NULL OR c.active_run_expires_at <= ?)
)`;

function activityUpdate(env: Env, conversationId: string, now: number) {
  return env.DB.prepare(`UPDATE conversations
    SET updated_at = ?, last_message_at = ?, version = version + 1
    WHERE id = ? AND changes() > 0`).bind(now, now, conversationId);
}

export async function editMessageAtomically(
  env: Env, ownerId: string, conversationId: string, messageId: string, content: string, now: number
): Promise<boolean> {
  const result = await env.DB.batch([
    env.DB.prepare(`UPDATE assistant_regenerations SET content = ?
      WHERE id = (SELECT selected_regeneration_id FROM messages WHERE id = ? AND conversation_id = ? AND role = 'assistant')
        AND message_id = ? AND ${unlockedOwner}
    `).bind(content, messageId, conversationId, messageId, conversationId, ownerId, now),
    env.DB.prepare(`UPDATE messages SET
      content = CASE WHEN role = 'assistant' AND EXISTS (
        SELECT 1 FROM assistant_regenerations r WHERE r.id = messages.selected_regeneration_id AND r.message_id = messages.id
      ) THEN content ELSE ? END,
      edited = 1, updated_at = ?
      WHERE id = ? AND conversation_id = ? AND ${unlockedOwner}
    `).bind(content, now, messageId, conversationId, conversationId, ownerId, now),
    activityUpdate(env, conversationId, now)
  ]);
  return Number(result[1].meta.changes) > 0;
}

export async function rewindToMessageAtomically(
  env: Env, ownerId: string, conversationId: string, messageId: string, now: number
): Promise<number> {
  // Resolve the exact ID in the DELETE, rather than a client offset or a stale
  // position. Repeating this request leaves the same message as the endpoint.
  const result = await env.DB.batch([
    env.DB.prepare(`DELETE FROM messages
      WHERE conversation_id = ? AND position > (
        SELECT target.position FROM messages target WHERE target.id = ? AND target.conversation_id = ?
      ) AND ${unlockedOwner}
    `).bind(conversationId, messageId, conversationId, conversationId, ownerId, now),
    activityUpdate(env, conversationId, now),
    // Distinguish an already-rewound transcript from a write fenced out by a
    // competing generation or a removed target, within this same transaction.
    env.DB.prepare(`SELECT 1 AS allowed FROM messages target
      WHERE target.id = ? AND target.conversation_id = ? AND ${unlockedOwner}
    `).bind(messageId, conversationId, conversationId, ownerId, now)
  ]);
  if (!result[2].results?.length) {
    throw new AppError(409, "TRANSCRIPT_CHANGED", "The conversation changed before the rewind. Refresh and try again.");
  }
  return Number(result[0].meta.changes);
}

export async function selectRegenerationAtomically(
  env: Env, ownerId: string, conversationId: string, messageId: string, regenerationId: string | null, now: number
): Promise<boolean> {
  const result = await env.DB.batch([
    env.DB.prepare(`UPDATE messages SET selected_regeneration_id = ?, updated_at = ?
      WHERE id = ? AND conversation_id = ? AND role = 'assistant'
        AND NOT EXISTS (SELECT 1 FROM messages later WHERE later.conversation_id = messages.conversation_id AND later.position > messages.position)
        AND (? IS NULL OR EXISTS (SELECT 1 FROM assistant_regenerations r WHERE r.id = ? AND r.message_id = messages.id))
        AND ${unlockedOwner}
    `).bind(regenerationId, now, messageId, conversationId, regenerationId, regenerationId, conversationId, ownerId, now),
    activityUpdate(env, conversationId, now)
  ]);
  return Number(result[0].meta.changes) > 0;
}
