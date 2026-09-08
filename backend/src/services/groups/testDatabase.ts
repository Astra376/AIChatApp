import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import type { Env, RequestContext } from "../../env";
export function database() {
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
  let transactionQueue = Promise.resolve<unknown>(undefined);
  const env = {DB: {prepare: statement, async batch(statements: Array<ReturnType<typeof statement>>) {
    const transaction = transactionQueue.then(async () => {
    sqlite.exec("BEGIN");
    try { const results = []; for (const item of statements) results.push(await item.run()); sqlite.exec("COMMIT"); return results; }
    catch (error) { sqlite.exec("ROLLBACK"); throw error; }
    });
    transactionQueue = transaction.catch(() => undefined);
    return transaction;
  }}} as unknown as Env;
  const context = (userId = "user") => ({env, user: {userId}, request: new Request("https://example.com"), url: new URL("https://example.com"), params: {}} as RequestContext);
  return {sqlite, env, context};
}
