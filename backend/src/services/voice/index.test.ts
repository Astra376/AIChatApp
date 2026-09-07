import { describe, it, expect, vi, afterEach } from "vitest";
import { speakMessage, speechText, characterVoiceStatement, OFFICIAL_VOICES } from "./index";
import type { RequestContext } from "../../env";

afterEach(() => vi.unstubAllGlobals());
function context(first: (sql: string) => unknown, cacheHit = false): RequestContext {
  return { user: { userId: "owner" }, url: new URL("https://worker.example.com/v1/voices/speak"), env: {
    FAL_API_KEY: "test", SESSION_HMAC_SECRET: "test",
    DB: { prepare: (sql: string) => ({ bind: (..._args: unknown[]) => ({ first: async () => first(sql), run: async () => ({ meta: { changes: 1 } }) }) }), batch: async () => [] },
    ASSETS: { head: async () => cacheHit ? {} : null }
  }} as unknown as RequestContext;
}
describe("character voices", () => {
  it("uses the published provider voice IDs", () => expect(OFFICIAL_VOICES).toContain("Ryan"));
  it("does not speak another user's message or spend on it", async () => {
    const fetch = vi.fn(); vi.stubGlobal("fetch", fetch);
    await expect(speakMessage(context(() => null), { conversationId: "other", messageId: "message" })).rejects.toMatchObject({ code: "MESSAGE_NOT_FOUND" });
    expect(fetch).not.toHaveBeenCalled();
  });
  it("reads the selected reply and reuses cached speech without paid inference", async () => {
    const queries: string[] = [], fetch = vi.fn(); vi.stubGlobal("fetch", fetch);
    const ctx = context(sql => { queries.push(sql); return sql.includes("FROM messages") ? { content: "Hello", character_id: "character" } : null; }, true);
    const result = await speakMessage(ctx, { conversationId: "conversation", messageId: "message" });
    expect(queries[0]).toContain("COALESCE(r.content,m.content)");
    expect(queries[0]).toContain("c.owner_user_id=?");
    expect(result.audioUrl).toContain("/v1/voice-assets/");
    expect(result.audioUrl).toContain("signature=");
    expect(fetch).not.toHaveBeenCalled();
  });
  it("does not publish a private cloned voice through a public character", async () => {
    const ctx = context(() => ({ id: "private", owner_user_id: "owner", visibility: "private" }));
    await expect(characterVoiceStatement(ctx, "character", "private", "public")).rejects.toMatchObject({ code: "VOICE_PRIVATE" });
  });
  it("removes formatting and code from spoken text", () => expect(speechText('**Hello** *there*\n```secret code```')).toBe("Hello there"));
});
