import { DatabaseSync } from "node:sqlite";
import { describe, expect, it } from "vitest";
import type { Env } from "../../env";
import { claimConversationRun, insertMessage, insertRegeneration, releaseConversationRun, updateMessageSelection } from "./conversations";

function database() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(`
    CREATE TABLE conversations (id TEXT PRIMARY KEY, active_run_id TEXT, active_run_expires_at INTEGER);
    CREATE TABLE messages (id TEXT PRIMARY KEY, conversation_id TEXT, position INTEGER, role TEXT,
      content TEXT, edited INTEGER, created_at INTEGER, updated_at INTEGER, selected_regeneration_id TEXT);
    CREATE TABLE assistant_regenerations (id TEXT PRIMARY KEY, message_id TEXT, content TEXT, created_at INTEGER);
    INSERT INTO conversations VALUES ('conversation', NULL, NULL);
  `);
  const env = { DB: { prepare(sql: string) {
    return { bind(...args: any[]) { return { async run() {
      const result = sqlite.prepare(sql).run(...args);
      return { success: true, meta: { changes: Number(result.changes) } };
    } }; } };
  } } } as unknown as Env;
  return { sqlite, env };
}
const message = { id: "assistant", conversation_id: "conversation", position: 1, role: "assistant" as const,
  content: "reply", edited: 0, created_at: 1, updated_at: 1, selected_regeneration_id: null };

describe("conversation cancellation fencing", () => {
  it("unlocks immediately and rejects late writes from the cancelled model", async () => {
    const { env, sqlite } = database();
    try {
      const now = Date.now();
      expect(await claimConversationRun(env, "conversation", "old", now, now + 130_000)).toBe(true);
      await releaseConversationRun(env, "conversation", "old");
      expect(await claimConversationRun(env, "conversation", "new", now, now + 130_000)).toBe(true);
      await expect(insertMessage(env, message, "old")).rejects.toMatchObject({ code: "RUN_CANCELLED" });
      await insertMessage(env, message, "new");
      await releaseConversationRun(env, "conversation", "old");
      expect(sqlite.prepare("SELECT active_run_id FROM conversations").get()?.active_run_id).toBe("new");
      expect(sqlite.prepare("SELECT COUNT(*) AS count FROM messages").get()?.count).toBe(1);
      await expect(insertRegeneration(env, { id: "regen", message_id: "assistant", content: "late", created_at: 2 }, "old"))
        .rejects.toMatchObject({ code: "RUN_CANCELLED" });
      await updateMessageSelection(env, { messageId: "assistant", selectedRegenerationId: "stale", updatedAt: 2 }, "old");
      expect(sqlite.prepare("SELECT selected_regeneration_id FROM messages").get()?.selected_regeneration_id).toBeNull();
    } finally { sqlite.close(); }
  });
});
