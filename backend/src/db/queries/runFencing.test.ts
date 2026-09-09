import { DatabaseSync } from "node:sqlite";
import { describe, expect, it } from "vitest";
import type { Env } from "../../env";
import { claimConversationRun, finishStoppedConversationRun, insertMessage, insertRegeneration, releaseConversationRun, updateMessageSelection } from "./conversations";

function database() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(`
    CREATE TABLE conversations (id TEXT PRIMARY KEY, active_run_id TEXT, active_run_expires_at INTEGER,
      updated_at INTEGER DEFAULT 0, last_message_at INTEGER, version INTEGER DEFAULT 0);
    CREATE TABLE messages (id TEXT PRIMARY KEY, conversation_id TEXT, position INTEGER, role TEXT,
      content TEXT, edited INTEGER, created_at INTEGER, updated_at INTEGER, selected_regeneration_id TEXT);
    CREATE TABLE assistant_regenerations (id TEXT PRIMARY KEY, message_id TEXT, content TEXT, created_at INTEGER);
    INSERT INTO conversations (id) VALUES ('conversation');
  `);
  const env = { DB: { prepare(sql: string) {
    return { bind(...args: any[]) { return { async run() {
      const result = sqlite.prepare(sql).run(...args);
      return { success: true, meta: { changes: Number(result.changes) } };
    } }; } };
  }, async batch(statements: Array<{ run(): Promise<unknown> }>) {
    sqlite.exec("BEGIN");
    try {
      const results = [];
      for (const statement of statements) results.push(await statement.run());
      sqlite.exec("COMMIT");
      return results;
    } catch (error) {
      sqlite.exec("ROLLBACK");
      throw error;
    }
  } } } as unknown as Env;
  return { sqlite, env };
}
const message = { id: "assistant", conversation_id: "conversation", position: 1, role: "assistant" as const,
  content: "reply", edited: 0, created_at: 1, updated_at: 1, selected_regeneration_id: null };

describe("conversation cancellation fencing", () => {
  it("saves a visible partial reply before unlocking, without allowing late or duplicate writes", async () => {
    const { env, sqlite } = database();
    try {
      const now = Date.now();
      await claimConversationRun(env, "conversation", "old", now, now + 130_000);
      const snapshot = { messageId: "message_old", text: "visible partial", regenerate: false };
      await finishStoppedConversationRun(env, "conversation", "old", snapshot);
      expect(sqlite.prepare("SELECT content FROM messages").get()?.content).toBe("visible partial");
      expect(await claimConversationRun(env, "conversation", "new", now, now + 130_000)).toBe(true);
      await finishStoppedConversationRun(env, "conversation", "old", snapshot);
      expect(sqlite.prepare("SELECT active_run_id FROM conversations").get()?.active_run_id).toBe("new");
      expect(sqlite.prepare("SELECT COUNT(*) AS count FROM messages").get()?.count).toBe(1);
      await expect(insertMessage(env, { ...message, id: snapshot.messageId }, "old"))
        .rejects.toMatchObject({ code: "RUN_CANCELLED" });
    } finally { sqlite.close(); }
  });

  it("keeps a full reply already saved when a stop races finalization", async () => {
    const { env, sqlite } = database();
    try {
      const now = Date.now();
      await claimConversationRun(env, "conversation", "old", now, now + 130_000);
      await insertMessage(env, { ...message, id: "message_old", content: "complete reply" }, "old");
      await finishStoppedConversationRun(env, "conversation", "old", {
        messageId: "message_old", text: "complete", regenerate: false
      });
      expect(sqlite.prepare("SELECT content FROM messages").get()?.content).toBe("complete reply");
      expect(sqlite.prepare("SELECT COUNT(*) AS count FROM messages").get()?.count).toBe(1);
    } finally { sqlite.close(); }
  });

  it("saves stopped regenerations once, selects them, and preserves the original reply", async () => {
    const { env, sqlite } = database();
    try {
      const now = Date.now();
      await insertMessage(env, message);
      await claimConversationRun(env, "conversation", "old", now, now + 130_000);
      await finishStoppedConversationRun(env, "conversation", "old", {
        messageId: message.id, text: "new partial", regenerate: true
      });
      expect(sqlite.prepare("SELECT content FROM messages").get()?.content).toBe("reply");
      expect(sqlite.prepare("SELECT selected_regeneration_id FROM messages").get()?.selected_regeneration_id).toBe("regen_old");
      expect(sqlite.prepare("SELECT content FROM assistant_regenerations").get()?.content).toBe("new partial");
      expect(sqlite.prepare("SELECT active_run_id FROM conversations").get()?.active_run_id).toBeNull();
      await claimConversationRun(env, "conversation", "next", now, now + 130_000);
      await insertRegeneration(env, { id: "regen_next", message_id: message.id, content: "already complete", created_at: now }, "next");
      await finishStoppedConversationRun(env, "conversation", "next", {
        messageId: message.id, text: "already", regenerate: true
      });
      expect(sqlite.prepare("SELECT content FROM assistant_regenerations WHERE id = 'regen_next'").get()?.content).toBe("already complete");
    } finally { sqlite.close(); }
  });

  it("cannot use a stopped regeneration to edit a different conversation", async () => {
    const { env, sqlite } = database();
    try {
      const now = Date.now();
      sqlite.exec("INSERT INTO conversations (id) VALUES ('private')");
      await insertMessage(env, { ...message, conversation_id: "private" });
      await claimConversationRun(env, "conversation", "old", now, now + 130_000);
      await finishStoppedConversationRun(env, "conversation", "old", {
        messageId: message.id, text: "wrong conversation", regenerate: true
      });
      expect(sqlite.prepare("SELECT COUNT(*) AS count FROM assistant_regenerations").get()?.count).toBe(0);
      expect(sqlite.prepare("SELECT selected_regeneration_id FROM messages").get()?.selected_regeneration_id).toBeNull();
    } finally { sqlite.close(); }
  });

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
