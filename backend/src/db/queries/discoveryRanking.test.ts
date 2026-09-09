import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type { Env } from "../../env";
import { ensureUltraSchema } from "../../services/billing";
import { getPublicFeed, searchPublicCharacters } from "./characters";

async function fixture() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../migrations/0001_initial.sql", import.meta.url), "utf8"));
  function statement(sql: string, args: any[] = []): any {
    return {
      bind: (...values: any[]) => statement(sql, values),
      run: async () => ({ success: true, meta: { changes: Number(db.prepare(sql).run(...args).changes) } }),
      first: async () => db.prepare(sql).get(...args) ?? null,
      all: async () => ({ results: db.prepare(sql).all(...args) })
    };
  }
  const env = { DB: { prepare: statement, batch: async (items: any[]) => Promise.all(items.map(item => item.run())) },
    STRIPE_ULTRA_PRICE_ID: "price_ultra" } as unknown as Env;
  await ensureUltraSchema(env);
  const now = Date.now();
  for (const owner of ["normal", "paid", "expired", "viewer"]) {
    db.prepare("INSERT INTO users VALUES(?,?,?,1,1)").run(owner, owner, `${owner}@example.com`);
    db.prepare("INSERT INTO profiles VALUES(?,?,NULL,1,1)").run(owner, owner);
  }
  function character(id: string, owner: string, name = "Astrid", visibility = "public", description = "An adventurer") {
    db.prepare(`INSERT INTO characters(id,owner_user_id,name,tagline,description,system_prompt,visibility,last_active_at,created_at,updated_at)
      VALUES(?,?,?,'',?,'',?,?,?,?)`).run(id, owner, name, description, visibility, now, now, now);
  }
  function subscription(id: string, owner: string, expiry: number, price = "price_ultra") {
    db.prepare("INSERT INTO subscriptions VALUES(?,?,?,'active',?,?,?)").run(id, owner, `customer-${owner}`, price, expiry, now);
  }
  return { db, env, now, character, subscription };
}

describe("discovery ranking", () => {
  it("boosts active Ultra creators without duplicate cards or private-character exposure", async () => {
    const f = await fixture();
    try {
      f.character("a-normal", "normal"); f.character("z-paid", "paid"); f.character("private", "paid", "Astrid", "private");
      f.subscription("one", "paid", f.now + 100_000); f.subscription("two", "paid", f.now + 100_000);
      expect((await getPublicFeed(f.env, "viewer", 0, 20)).map(row => row.id)).toEqual(["z-paid", "a-normal"]);
      f.db.exec("UPDATE subscriptions SET expires_at=0");
      expect((await getPublicFeed(f.env, "viewer", 0, 20)).map(row => row.id)).toEqual(["a-normal", "z-paid"]);
    } finally { f.db.close(); }
  });
  it("keeps exact search matches ahead of boosted description matches and treats wildcard input literally", async () => {
    const f = await fixture();
    try {
      f.character("exact", "normal", "Astrid"); f.character("description", "paid", "Other", "public", "Astrid's friend");
      f.character("percent", "normal", "100% ready"); f.subscription("one", "paid", f.now + 100_000);
      expect((await searchPublicCharacters(f.env, "viewer", "Astrid", 0, 20)).map(row => row.id)).toEqual(["exact", "description"]);
      expect((await searchPublicCharacters(f.env, "viewer", "%", 0, 20)).map(row => row.id)).toEqual(["percent"]);
      expect((await searchPublicCharacters(f.env, "viewer", "Astrid", 1, 1)).map(row => row.id)).toEqual(["description"]);
    } finally { f.db.close(); }
  });
});
