import type { Env } from "../../env";
import { first, run } from "../client";

export interface ConversationMemoryRecord {
  conversation_id: string;
  short_term: string;
  long_term: string;
  auto_long_term_entries: string;
  mid_term: string;
  scene_state: string | null;
  emotion_state: string | null;
  personality_state: string | null;
  psychology_state: string | null;
  invalidated_from_position: number | null;
  last_consolidated_position: number;
  revision: number;
  updated_at: number;
}

export async function getConversationMemory(
  env: Env,
  conversationId: string
): Promise<ConversationMemoryRecord | null> {
  return first<ConversationMemoryRecord>(
    env.DB.prepare(
      "SELECT * FROM conversation_memories WHERE conversation_id = ? LIMIT 1"
    ).bind(conversationId)
  );
}

export async function createConversationMemoryIfMissing(
  env: Env,
  conversationId: string,
  now: number
): Promise<void> {
  await run(
    env.DB.prepare(
      `
      INSERT OR IGNORE INTO conversation_memories (
        conversation_id, short_term, long_term, auto_long_term_entries,
        last_consolidated_position, revision, updated_at
      )
      VALUES (?, '', '', '[]', -1, 0, ?)
      `
    ).bind(conversationId, now)
  );
}

export async function saveConversationMemory(env: Env, input: {
  conversationId: string; shortTerm?: string; longTerm?: string; midTerm?: string;
  sceneState?: string | null; emotionState?: string | null; personalityState?: string | null;
  psychologyState?: string | null; updatedAt: number;
}): Promise<void> {
  await createConversationMemoryIfMissing(env, input.conversationId, input.updatedAt);
  const columns: Record<string, unknown> = {short_term: input.shortTerm, long_term: input.longTerm, mid_term: input.midTerm,
    scene_state: input.sceneState, emotion_state: input.emotionState, personality_state: input.personalityState, psychology_state: input.psychologyState};
  const entries = Object.entries(columns).filter(([,value]) => value !== undefined);
  if (!entries.length) return;
  // Only a manual edit of the durable text converts automatic notes into
  // user-maintained notes. Editing a mood must not lose rewind provenance.
  await run(env.DB.prepare(`UPDATE conversation_memories SET ${entries.map(([name]) => `${name} = ?`).join(", ")},
    ${input.longTerm !== undefined ? "auto_long_term_entries = '[]'," : ""}
    revision = revision + 1, updated_at = ? WHERE conversation_id = ?`)
    .bind(...entries.map(([,value]) => value), input.updatedAt, input.conversationId));
}

export async function saveAutomaticConversationMemory(
  env: Env,
  input: {
    conversationId: string;
    shortTerm: string;
    longTerm: string;
    autoLongTermEntries: string;
    consolidatedPosition: number;
    expectedRevision: number;
    updatedAt: number;
    force: boolean;
    expectedConversationVersion: number;
    midTerm: string;
    sceneState: string | null;
    emotionState: string | null;
    personalityState: string | null;
    psychologyState: string | null;
  }
): Promise<boolean> {
  const result = await run(
    env.DB.prepare(
      `
      UPDATE conversation_memories
      SET
        short_term = ?,
        long_term = ?,
        auto_long_term_entries = ?,
        last_consolidated_position = ?,
        revision = revision + 1,
        updated_at = ?, mid_term = ?, scene_state = ?, emotion_state = ?, personality_state = ?, psychology_state = ?,
        invalidated_from_position = NULL
      WHERE conversation_id = ?
        AND revision = ?
        AND (? = 1 OR last_consolidated_position < ?)
        AND EXISTS (SELECT 1 FROM conversations c WHERE c.id = conversation_memories.conversation_id AND c.version = ?)
      `
    ).bind(
      input.shortTerm,
      input.longTerm,
      input.autoLongTermEntries,
      input.consolidatedPosition,
      input.updatedAt,
      input.midTerm,
      input.sceneState,
      input.emotionState,
      input.personalityState,
      input.psychologyState,
      input.conversationId,
      input.expectedRevision,
      input.force ? 1 : 0,
      input.consolidatedPosition,
      input.expectedConversationVersion
    )
  );
  return (result.meta.changes ?? 0) > 0;
}
