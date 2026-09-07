import type { Env } from "../env";
import { all, run } from "./client";

const readyByDatabase = new WeakMap<D1Database, Promise<void>>();

async function ensureConversationMemorySchemaImpl(env: Env): Promise<void> {
  await run(
    env.DB.prepare(
      `
      CREATE TABLE IF NOT EXISTS conversation_memories (
        conversation_id TEXT PRIMARY KEY,
        short_term TEXT NOT NULL DEFAULT '',
        long_term TEXT NOT NULL DEFAULT '',
        auto_long_term_entries TEXT NOT NULL DEFAULT '[]',
        last_consolidated_position INTEGER NOT NULL DEFAULT -1,
        revision INTEGER NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL,
        FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
      )
      `
    )
  );

  const columns = await all<{ name: string }>(
    env.DB.prepare("PRAGMA table_info(conversation_memories)")
  );
  const additions: Record<string, string> = {
    auto_long_term_entries: "TEXT NOT NULL DEFAULT '[]'",
    mid_term: "TEXT NOT NULL DEFAULT ''",
    scene_state: "TEXT",
    emotion_state: "TEXT",
    personality_state: "TEXT",
    psychology_state: "TEXT",
    invalidated_from_position: "INTEGER"
  };
  for (const [name, definition] of Object.entries(additions)) {
    if (columns.some(column => column.name === name)) continue;
    try { await run(env.DB.prepare(`ALTER TABLE conversation_memories ADD COLUMN ${name} ${definition}`)); }
    catch (error) {
      if (!(error instanceof Error) || !error.message.toLowerCase().includes("duplicate column")) throw error;
    }
  }
  // Invalidate in the same transaction as transcript edits/deletes. A chat
  // opened immediately after a rewind must never receive a removed memory.
  await env.DB.batch([
    env.DB.prepare(`CREATE TRIGGER IF NOT EXISTS memory_after_message_edit
      AFTER UPDATE OF content, selected_regeneration_id ON messages
      WHEN OLD.content <> NEW.content OR OLD.selected_regeneration_id IS NOT NEW.selected_regeneration_id
      BEGIN UPDATE conversation_memories SET
        invalidated_from_position = MIN(COALESCE(invalidated_from_position, NEW.position), NEW.position),
        revision = revision + 1 WHERE conversation_id = NEW.conversation_id; END`),
    env.DB.prepare(`CREATE TRIGGER IF NOT EXISTS memory_after_message_delete AFTER DELETE ON messages
      BEGIN UPDATE conversation_memories SET
        invalidated_from_position = MIN(COALESCE(invalidated_from_position, OLD.position), OLD.position),
        revision = revision + 1 WHERE conversation_id = OLD.conversation_id; END`),
    env.DB.prepare(`CREATE TRIGGER IF NOT EXISTS memory_after_variant_edit AFTER UPDATE OF content ON assistant_regenerations
      WHEN OLD.content <> NEW.content
      BEGIN UPDATE conversation_memories SET
        invalidated_from_position = MIN(COALESCE(invalidated_from_position,
          (SELECT position FROM messages WHERE id = NEW.message_id)),
          (SELECT position FROM messages WHERE id = NEW.message_id)), revision = revision + 1
        WHERE conversation_id = (SELECT conversation_id FROM messages WHERE id = NEW.message_id AND selected_regeneration_id = NEW.id); END`)
  ]);
}

export function ensureConversationMemorySchema(env: Env): Promise<void> {
  const existing = readyByDatabase.get(env.DB);
  if (existing) return existing;

  const pending = ensureConversationMemorySchemaImpl(env).catch((error) => {
    readyByDatabase.delete(env.DB);
    throw error;
  });
  readyByDatabase.set(env.DB, pending);
  return pending;
}
