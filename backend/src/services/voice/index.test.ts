import { describe, it, expect, vi, afterEach } from "vitest";
import { speakMessage, speechText, characterVoiceStatement, OFFICIAL_VOICES, createVoice, previewVoice } from "./index";
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

it("allows a community voice and scopes audio cache to the listening account", async () => {
  const urls: string[] = [];
  for (const userId of ["listener_a", "listener_b"]) {
    const ctx = context(sql => sql.includes("FROM messages") ? { content: "Same text", character_id: "character" } : null, true);
    ctx.user!.userId = userId;
    urls.push((await speakMessage(ctx, { conversationId: "c", messageId: "m" })).audioUrl.split("?")[0]);
  }
  expect(urls[0]).not.toBe(urls[1]);
  const ctx = context(() => ({ id: "community", owner_user_id: "someone_else", visibility: "public" }));
  await expect(characterVoiceStatement(ctx, "character", "community", "public")).resolves.toBeDefined();
});


it("blocks custom voice creation for Standard accounts before uploads or paid inference", async () => {
  const fetch = vi.fn(); vi.stubGlobal("fetch",fetch);
  const ctx = context(() => null);
  await expect(createVoice(ctx, {name:"My voice",description:"A warm thoughtful voice",public:false,requestKey:"request_1234567890"}))
    .rejects.toMatchObject({code:"ULTRA_REQUIRED"});
  expect(fetch).not.toHaveBeenCalled();
});
it("reuses the existing designed preview for every eligible listener without another TTS charge", async () => {
  const fetch=vi.fn(); vi.stubGlobal("fetch",fetch);
  const ctx=context(() => ({id:"custom",visibility:"public",preview_key:"voice/custom/preview.mp3"}),true);
  expect((await previewVoice(ctx,"custom")).audioUrl).toContain("voice%2Fcustom%2Fpreview.mp3");
  expect(fetch).not.toHaveBeenCalled();
});
it("does not expose another creator's private voice preview", async () => {
  const fetch=vi.fn(); vi.stubGlobal("fetch",fetch);
  await expect(previewVoice(context(()=>null,true),"private")).rejects.toMatchObject({code:"VOICE_NOT_FOUND"});
  expect(fetch).not.toHaveBeenCalled();
});
