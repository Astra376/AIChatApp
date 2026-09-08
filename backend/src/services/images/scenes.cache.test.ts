import { beforeEach, describe, expect, it, vi } from "vitest";
import { database } from "../groups/testDatabase";
import { conversationBackground } from "./scenes";
import { completeChatText } from "../../providers/openrouter";
import { generateImageWithOpenRouter, storeGeneratedImage } from "../../providers/openrouterImages";
vi.mock("../../providers/openrouter", () => ({completeChatText: vi.fn()}));
vi.mock("../../providers/openrouterImages", () => ({generateImageWithOpenRouter: vi.fn(), storeGeneratedImage: vi.fn()}));

function fixture() {
  const db = database();
  db.sqlite.exec("INSERT INTO conversations(id,owner_user_id,character_id,updated_at,started_at) VALUES('chat','user','astrid',1,1)");
  const assets = new Set<string>();
  db.env.ASSETS = {head: async (key: string) => assets.has(key) ? {key} : null} as any;
  db.env.R2_PUBLIC_BASE_URL = "https://assets.example";
  vi.mocked(generateImageWithOpenRouter).mockResolvedValue({ bytes: new Uint8Array([1]), mediaType: "image/jpeg", model: "test" } as any);
  vi.mocked(storeGeneratedImage).mockImplementation(async (_, key) => { assets.add(key); return `https://assets.example/${encodeURIComponent(key)}`; });
  const message = (position: number, text: string) => db.sqlite.prepare("INSERT INTO messages(id,conversation_id,position,role,content,created_at,updated_at) VALUES(?,'chat',?,'user',?,1,1)").run(`m${position}`, position, text);
  const scene = (id: string, position: number, quote: string) => JSON.stringify({changed: true, placeId: id, location: id, description: id, lighting: "day", weather: "clear", sourcePosition: position, sourceQuote: quote});
  return {...db, message, scene};
}
describe("durable scene cache", () => {
  beforeEach(() => vi.clearAllMocks());
  it("reuses an unchanged scene and a previously visited setting", async () => {
    const db = fixture();
    try {
      db.message(0, "We arrive at the cafe.");
      vi.mocked(completeChatText).mockResolvedValueOnce(db.scene("cafe", 0, "arrive at the cafe"));
      const cafe = await conversationBackground(db.context(), "chat");
      expect(await conversationBackground(db.context(), "chat")).toEqual(cafe);
      db.message(1, "Hello again."); vi.mocked(completeChatText).mockResolvedValueOnce('{"changed":false}');
      expect(await conversationBackground(db.context(), "chat")).toEqual(cafe);
      db.message(2, "We move into the garden."); vi.mocked(completeChatText).mockResolvedValueOnce(db.scene("garden", 2, "move into the garden"));
      expect((await conversationBackground(db.context(), "chat")).sceneKey).not.toBe(cafe.sceneKey);
      db.message(3, "We return to the cafe."); vi.mocked(completeChatText).mockResolvedValueOnce(db.scene("cafe", 3, "return to the cafe"));
      expect((await conversationBackground(db.context(), "chat")).sceneKey).toBe(cafe.sceneKey);
      expect(generateImageWithOpenRouter).toHaveBeenCalledTimes(2);
      expect(generateImageWithOpenRouter).toHaveBeenCalledWith(db.env, expect.objectContaining({prompt: expect.stringContaining("2D anime"), model: "black-forest-labs/flux.2-klein-4b"}));
    } finally { db.sqlite.close(); }
  });
  it("allows one image purchase while overlapping requests share the lease", async () => {
    const db = fixture();
    try {
      db.message(0, "We arrive at the cafe.");
      vi.mocked(completeChatText).mockResolvedValue(db.scene("cafe", 0, "arrive at the cafe"));
      const results = await Promise.allSettled([conversationBackground(db.context(), "chat"), conversationBackground(db.context(), "chat")]);
      expect(results.filter(r => r.status === "fulfilled")).toHaveLength(1);
      expect(generateImageWithOpenRouter).toHaveBeenCalledTimes(1);
    } finally { db.sqlite.close(); }
  });
  it("checks conversation ownership before generating or resolving artwork", async () => {
    const db = fixture();
    try {
      await expect(conversationBackground(db.context("other"), "chat")).rejects.toMatchObject({code: "CONVERSATION_NOT_FOUND"});
      expect(completeChatText).not.toHaveBeenCalled(); expect(generateImageWithOpenRouter).not.toHaveBeenCalled();
    } finally { db.sqlite.close(); }
  });
});
