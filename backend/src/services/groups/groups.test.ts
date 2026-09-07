import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { describe, expect, it, afterEach, vi } from "vitest";
import type { Env, RequestContext } from "../../env";
import { createGroup, requireGroup, claimGroupSend, claimGroupContinuation, insertGroupReply, persistGroupReply,
  stopGroupRun, getGroup, deleteGroup, groupCharacters, updateGroupPresence } from "./storage";
import { parseGroupSpeakers, groupCharacterContext } from "./router";

function database() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec("PRAGMA foreign_keys = ON");
  for (const file of ["0001_initial.sql", "0012_group_chats.sql"]) sqlite.exec(readFileSync(new URL(`../../db/migrations/${file}`, import.meta.url), "utf8"));
  for (const id of ["user", "other", "creator"]) sqlite.prepare("INSERT INTO users VALUES (?, ?, ?, 1, 1)").run(id, id, `${id}@example.com`);
  for (const [id, visibility] of [["astrid", "public"], ["leo", "public"], ["private", "private"]]) {
    sqlite.prepare(`INSERT INTO characters (id,owner_user_id,name,tagline,description,system_prompt,visibility,last_active_at,created_at,updated_at)
      VALUES (?,'creator',?,'A friend','A character','Be yourself',?,1,1,1)`).run(id, id, visibility);
  }
  function statement(sql: string, args: any[] = []) {
    return {
      bind(...parameters: any[]) { return statement(sql, parameters); },
      async run() { const result = sqlite.prepare(sql).run(...args); return {success: true, meta: {changes: Number(result.changes)}}; },
      async first() { return sqlite.prepare(sql).get(...args) ?? null; },
      async all() { return {results: sqlite.prepare(sql).all(...args)}; }
    };
  }
  const env = {DB: {prepare: statement, async batch(statements: Array<ReturnType<typeof statement>>) {
    sqlite.exec("BEGIN");
    try { const results = []; for (const item of statements) results.push(await item.run()); sqlite.exec("COMMIT"); return results; }
    catch (error) { sqlite.exec("ROLLBACK"); throw error; }
  }}} as unknown as Env;
  const context = (userId = "user") => ({env, user: {userId}, request: new Request("https://example.com"), url: new URL("https://example.com"), params: {}} as RequestContext);
  return {sqlite, env, context};
}
afterEach(() => vi.useRealTimers());
const roster = [{id: "astrid", name: "Astrid", avatar_url: null, system_prompt: "Reserved and reflective", tagline: "A friend"},
  {id: "leo", name: "Leo", avatar_url: null, system_prompt: "Playful", tagline: "Another friend"}];

describe("group speaker routing", () => {
  it("accepts silence and context-selected speakers without forcing a round robin", () => {
    expect(parseGroupSpeakers('{"speakers":[]}', roster, "away")).toEqual([]);
    expect(parseGroupSpeakers('{"speakers":["leo","astrid"]}', roster, "user")).toEqual(["leo", "astrid"]);
    expect(() => parseGroupSpeakers('{"speakers":["leo","leo"]}', roster, "user")).toThrow();
    expect(() => parseGroupSpeakers('{"speakers":["leo","astrid"]}', roster, "typing")).toThrow();
    expect(() => parseGroupSpeakers('{"speakers":["intruder"]}', roster, "user")).toThrow();
    expect(() => parseGroupSpeakers('not json', roster, "user")).toThrow();
  });
  it("lets a character respond to another character while preserving speaker identity", () => {
    const messages = groupCharacterContext(roster[0], roster, [
      {id: "one", role: "user", character_id: null, content: "Hi friends"},
      {id: "two", role: "assistant", character_id: "leo", content: "Astrid, what do you think?"}
    ], "The user is named Sam.");
    expect(messages[0].content).toContain("Sam");
    expect(messages[0].content).toContain("never inventing");
    expect(messages[2].content).toContain('"speaker":"Leo"');
  });
});

describe("group storage and run fencing", () => {
  it("validates membership and protects all group reads and deletion by owner", async () => {
    const {context, sqlite} = database();
    try {
      await expect(createGroup(context(), "Friends", ["astrid", "private"])).rejects.toMatchObject({code: "CHARACTER_NOT_FOUND"});
      await expect(createGroup(context(), "Friends", ["astrid", "astrid"])).rejects.toMatchObject({code: "INVALID_GROUP_MEMBERS"});
      const group = await createGroup(context(), "Friends", ["astrid", "leo"]);
      await expect(getGroup(context("other"), group.id)).rejects.toMatchObject({code: "GROUP_NOT_FOUND"});
      await expect(deleteGroup(context("other"), group.id)).rejects.toMatchObject({code: "GROUP_NOT_FOUND"});
      await deleteGroup(context(), group.id);
      expect(sqlite.prepare("SELECT COUNT(*) AS count FROM group_members").get()?.count).toBe(0);
    } finally { sqlite.close(); }
  });
  it("deduplicates the exact send and rejects a second send while a run owns the lease", async () => {
    const {context, env, sqlite} = database();
    try {
      const created = await createGroup(context(), "Friends", ["astrid", "leo"]);
      const group = await requireGroup(context(), created.id);
      const first = await claimGroupSend(env, group, "user_message_1", "Hello");
      expect(first.duplicate).toBe(false);
      expect((await claimGroupSend(env, group, "user_message_1", "Hello")).duplicate).toBe(true);
      await expect(claimGroupSend(env, group, "user_message_1", "Different")).rejects.toMatchObject({code: "DUPLICATE_MESSAGE_ID"});
      await expect(claimGroupSend(env, group, "user_message_2", "Second")).rejects.toMatchObject({code: "GROUP_BUSY"});
      expect(sqlite.prepare("SELECT COUNT(*) AS count FROM group_messages").get()?.count).toBe(1);
      expect((await requireGroup(context(), group.id)).user_turn).toBe(1);
    } finally { sqlite.close(); }
  });
  it("preserves streamed text, stops exactly one run, and fences late writes after a new send", async () => {
    const {context, env, sqlite} = database();
    try {
      const created = await createGroup(context(), "Friends", ["astrid", "leo"]);
      const group = await requireGroup(context(), created.id);
      const first = await claimGroupSend(env, group, "user_message_1", "Hello");
      expect(await insertGroupReply(env, group, first.runId, "reply1", "leo")).toBe(true);
      expect(await persistGroupReply(env, group, first.runId, "reply1", "leo", "Hi", false, false)).toBe(true);
      await stopGroupRun(env, group.id, first.runId, {id: "reply1", content: "Hi Sam"});
      const second = await claimGroupSend(env, group, "user_message_2", "What's new?");
      await stopGroupRun(env, group.id, first.runId, {id: "reply1", content: "STALE"});
      expect(await persistGroupReply(env, group, first.runId, "reply1", "leo", "STALE", true, false)).toBe(false);
      expect((await requireGroup(context(), group.id)).active_run_id).toBe(second.runId);
      expect(sqlite.prepare("SELECT content, status FROM group_messages WHERE id = 'reply1'").get()).toMatchObject({content: "Hi Sam", status: "interrupted"});
    } finally { sqlite.close(); }
  });
  it("clears expired leases on reload and never returns an endless generating state", async () => {
    const {context, env, sqlite} = database();
    try {
      const created = await createGroup(context(), "Friends", ["astrid", "leo"]);
      const group = await requireGroup(context(), created.id);
      const first = await claimGroupSend(env, group, "user_message_1", "Hello");
      await insertGroupReply(env, group, first.runId, "reply1", "leo");
      await persistGroupReply(env, group, first.runId, "reply1", "leo", "Partial", false, false);
      sqlite.prepare("UPDATE chat_groups SET active_run_expires_at = 1 WHERE id = ?").run(group.id);
      const detail = await getGroup(context(), group.id);
      expect(detail.activeRunId).toBeNull();
      expect(detail.messages[1]).toMatchObject({content: "Partial", status: "interrupted"});
      expect((await claimGroupSend(env, group, "user_message_2", "Continue please")).duplicate).toBe(false);
    } finally { sqlite.close(); }
  });
  it("bounds automatic follow-ups to one per user turn and requires sustained typing", async () => {
    const {context, env, sqlite} = database();
    try {
      const created = await createGroup(context(), "Friends", ["astrid", "leo"]);
      let group = await requireGroup(context(), created.id);
      expect(await claimGroupContinuation(env, group, "typing")).toBeNull();
      const first = await claimGroupSend(env, group, "user_message_1", "Hello");
      await stopGroupRun(env, group.id, first.runId);
      await updateGroupPresence(context(), group.id, true);
      group = await requireGroup(context(), group.id);
      expect(await claimGroupContinuation(env, group, "typing")).toBeNull();
      sqlite.prepare("UPDATE chat_groups SET typing_started_at = ? WHERE id = ?").run(Date.now() - 35_000, group.id);
      const auto = await claimGroupContinuation(env, group, "typing");
      expect(auto).toBeTruthy();
      await stopGroupRun(env, group.id, auto!);
      sqlite.prepare("UPDATE chat_groups SET last_autonomy_at = 0, last_seen_at = 0 WHERE id = ?").run(group.id);
      expect(await claimGroupContinuation(env, group, "away")).toBeNull();
    } finally { sqlite.close(); }
  });
  it("allows one quiet follow-up only while the user is present, idle, and not typing", async () => {
    const {context, env, sqlite} = database();
    try {
      const created = await createGroup(context(), "Friends", ["astrid", "leo"]);
      let group = await requireGroup(context(), created.id);
      const sent = await claimGroupSend(env, group, "user_message_1", "Hello");
      await stopGroupRun(env, group.id, sent.runId);
      group = await requireGroup(context(), group.id);
      expect(await claimGroupContinuation(env, group, "quiet")).toBeNull();
      sqlite.prepare("UPDATE chat_groups SET updated_at = ? WHERE id = ?").run(Date.now() - 70_000, group.id);
      await updateGroupPresence(context(), group.id, true);
      expect(await claimGroupContinuation(env, group, "quiet")).toBeNull();
      await updateGroupPresence(context(), group.id, false);
      const quiet = await claimGroupContinuation(env, group, "quiet");
      expect(quiet).toBeTruthy();
      await stopGroupRun(env, group.id, quiet!);
      sqlite.prepare("UPDATE chat_groups SET last_autonomy_at = 0 WHERE id = ?").run(group.id);
      expect(await claimGroupContinuation(env, group, "quiet")).toBeNull();
    } finally { sqlite.close(); }
  });
  it("paginates stable message positions and blocks a now-private speaker", async () => {
    const {context, env, sqlite} = database();
    try {
      const created = await createGroup(context(), "Friends", ["astrid", "leo"]);
      const group = await requireGroup(context(), created.id);
      for (let index = 0; index < 7; index++) sqlite.prepare("INSERT INTO group_messages (id,group_id,position,role,content,created_at) VALUES (?, ?, ?, 'user', ?, 1)")
        .run(`m${index}`, group.id, index, `Message ${index}`);
      expect((await getGroup(context(), group.id, undefined, 3)).messages.map(message => message.position)).toEqual([4, 5, 6]);
      expect((await getGroup(context(), group.id, 4, 3)).messages.map(message => message.position)).toEqual([1, 2, 3]);
      sqlite.exec("UPDATE characters SET visibility='private' WHERE id='leo'");
      await expect(groupCharacters(env, group)).rejects.toMatchObject({code: "GROUP_MEMBER_UNAVAILABLE"});
    } finally { sqlite.close(); }
  });
});
