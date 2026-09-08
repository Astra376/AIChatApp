import { describe, expect, it } from "vitest";
import { buildDiscovery, interestWeight, discover, recommendedPage } from "./recommendations";
import type { CharacterRecord } from "../../db/queries/characters";
import { database } from "../groups/testDatabase";

const now = 1_800_000_000_000;
const signal = { character_id: "a", turns: 50, days: 8, sessions: 10, last_at: now, liked: 1 };
function character(id: string, text: string, owner = id): CharacterRecord {
  return { id, name: id, tagline: text, description: text, system_prompt: "private secret", owner_user_id: owner,
    public_chat_count: 10, like_count: 2, updated_at: now, created_at: now, last_active_at: now,
    visibility: "public" } as CharacterRecord;
}

describe("personal discovery", () => {
  it("uses sustained activity and return days rather than raw visit count", () => {
    expect(interestWeight(signal, now)).toBeGreaterThan(interestWeight({ ...signal, turns: 0, days: 0, sessions: 0, liked: 0 }, now));
    expect(interestWeight(signal, now)).toBeGreaterThan(interestWeight({ ...signal, days: 1, sessions: 1 }, now));
    expect(interestWeight({ ...signal, turns: 100 }, now)).toBeLessThan(interestWeight(signal, now) * 2);
    expect(interestWeight(signal, now + 90 * 86400000)).toBeLessThan(interestWeight(signal, now));
  });
  it("ranks relevant characters, diversifies creators, preserves useful categories", () => {
    const catalog = [character("other", "cyberpunk android"), character("a", "magic fantasy elf", "same"), character("b", "magic fantasy elf", "same"), character("c", "magic fantasy elf", "different")];
    const first = buildDiscovery(catalog, [signal], new Map(), now);
    expect(first.ids.indexOf("c")).toBeLessThan(first.ids.indexOf("other"));
    expect(first.ids.slice(0, 2)).toContain("c");
    expect(first.categories.some(c => c.id === "fantasy")).toBe(true);
    const next = buildDiscovery(catalog, [{ ...signal, turns: 60 }], new Map(), now + 3600000, first);
    expect(next.categories.map(c => c.id)).toEqual(first.categories.map(c => c.id));
    expect(new Set(next.ids).size).toBe(catalog.length);
  });
  it("rejects appearance words as categories and refreshes the previous taxonomy", async () => {
    const catalog = [character("a", "green eyes gentle pirate"), character("b", "green eyes pirate"),
      character("c", "green eyes seafaring"), character("d", "robot"), character("e", "wizard"), character("f", "detective")];
    const result = buildDiscovery(catalog, [signal], new Map(), now);
    expect(result.categories.some(c => c.title === "Green" || c.id.startsWith("theme-"))).toBe(false);
    expect(result.categories.some(c => c.id === "pirates")).toBe(true);
    const db = database();
    try {
      await discover(db.context());
      const row = db.sqlite.prepare("SELECT * FROM discovery_snapshots LIMIT 1").get() as any;
      const stale = JSON.parse(row.snapshot_json);
      delete stale.taxonomyVersion;
      stale.categories.push({id: "theme-green", title: "Green", ids: stale.ids});
      db.sqlite.prepare("UPDATE discovery_snapshots SET snapshot_json=?").run(JSON.stringify(stale));
      expect((await discover(db.context())).categories.some(c => c.id === "theme-green")).toBe(false);
    } finally { db.sqlite.close(); }
  });
  it("uses semantic relatedness when vocabulary differs", () => {
    const catalog = [character("unrelated", "robot motor"), character("a", "mage forest"), character("semantic", "sorceress woodland")];
    const data = buildDiscovery(catalog, [signal], new Map(), now, undefined,
      new Map([["a", [1, 0]], ["semantic", [.99, .01]], ["unrelated", [0, 1]]]));
    expect(data.ids.indexOf("semantic")).toBeLessThan(data.ids.indexOf("unrelated"));
  });
  it("persists category snapshots, binds pagination to user and version, filters new privacy changes", async () => {
    const db = database();
    try {
      const first = await discover(db.context());
      const again = await discover(db.context());
      expect(again).toEqual(first);
      const page = await recommendedPage(db.context(), null, 1);
      expect(page.items).toHaveLength(1);
      expect(page.nextCursor).toContain(first.version);
      await expect(recommendedPage(db.context("other"), page.nextCursor, 1)).rejects.toMatchObject({ code: "DISCOVERY_EXPIRED" });
      const remaining = await recommendedPage(db.context(), page.nextCursor, 1);
      expect(remaining.items[0].id).not.toBe(page.items[0].id);
      db.sqlite.prepare("UPDATE characters SET visibility='private' WHERE id=?").run(remaining.items[0].id);
      expect((await recommendedPage(db.context(), page.nextCursor, 1)).items).toEqual([]);
    } finally { db.sqlite.close(); }
  });
});
