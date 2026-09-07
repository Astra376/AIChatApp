import { describe, expect, it } from "vitest";
import { ensureConversationStreamingSchema } from "./ensureConversationStreamingSchema";
import type { Env } from "../env";

class FakeStatement {
  constructor(
    private readonly database: FakeD1Database,
    private readonly sql: string
  ) {}

  async all() {
    if (this.sql !== "PRAGMA table_info(conversations)") {
      throw new Error(`Unexpected all query: ${this.sql}`);
    }
    return {
      results: this.database.columns.map((name) => ({ name }))
    };
  }

  async run() {
    if (this.sql.startsWith("CREATE INDEX IF NOT EXISTS")) {
      this.database.indexStatements.push(this.sql);
      return { meta: { changes: 0 } };
    }
    const match = this.sql.match(/^ALTER TABLE conversations ADD COLUMN ([a-z_]+)/);
    if (!match) {
      throw new Error(`Unexpected run query: ${this.sql}`);
    }
    this.database.columns.push(match[1]);
    this.database.alterStatements.push(this.sql);
    return { meta: { changes: 1 } };
  }
}

class FakeD1Database {
  columns = ["id", "owner_user_id", "character_id", "updated_at", "started_at", "last_message_at"];
  alterStatements: string[] = [];
  indexStatements: string[] = [];

  prepare(sql: string) {
    return new FakeStatement(this, sql.trim());
  }

  async batch(statements: FakeStatement[]) {
    return Promise.all(statements.map(statement => statement.run()));
  }
}

describe("ensureConversationStreamingSchema", () => {
  it("adds all conversation columns required by streaming finalization", async () => {
    const database = new FakeD1Database();
    const env = { DB: database } as unknown as Env;

    await ensureConversationStreamingSchema(env);

    expect(database.columns).toEqual([
      "id",
      "owner_user_id",
      "character_id",
      "updated_at",
      "started_at",
      "last_message_at",
      "version",
      "active_run_id",
      "active_run_expires_at",
      "unread_count",
      "has_unread_badge"
    ]);
    expect(database.alterStatements).toContain("ALTER TABLE conversations ADD COLUMN unread_count INTEGER NOT NULL DEFAULT 0");
    expect(database.alterStatements).toContain("ALTER TABLE conversations ADD COLUMN has_unread_badge INTEGER NOT NULL DEFAULT 0");
    expect(database.indexStatements).toHaveLength(2);
    await ensureConversationStreamingSchema(env);
    expect(database.indexStatements).toHaveLength(2);
  });
});
