import { afterEach, describe, expect, it, vi } from "vitest";
import { completeChatText, streamChatText } from "./openrouter";
import type { Env } from "../env";

const env = {
  OPENROUTER_API_KEY: "test-key",
  OPENROUTER_MODEL: "test-model"
} as Env;

function streamFromText(text: string): ReadableStream<Uint8Array> {
  const encoded = new TextEncoder().encode(text);
  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(encoded);
      controller.close();
    }
  });
}

describe("streamChatText", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("parses OpenRouter SSE chunks with CRLF line endings", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(
        streamFromText(
          [
            'data: {"choices":[{"delta":{"content":"hello"}}]}',
            "",
            'data: {"choices":[{"delta":{"content":" there"}}]}',
            "",
            "data: [DONE]",
            ""
          ].join("\r\n")
        ),
        { status: 200 }
      ))
    );

    const chunks: string[] = [];
    for await (const chunk of streamChatText(env, [{ role: "user", content: "hi" }])) {
      chunks.push(chunk);
    }

    expect(chunks).toEqual(["hello", " there"]);
  });

  it("requests configured model fallbacks in priority order", async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => new Response(
      JSON.stringify({ choices: [{ message: { content: "ready" } }] }),
      { status: 200 }
    ));
    vi.stubGlobal("fetch", fetchMock);

    const value = await completeChatText(
      { ...env, OPENROUTER_FALLBACK_MODELS: "fallback-one, fallback-two" },
      [{ role: "user", content: "hi" }]
    );

    expect(value).toBe("ready");
    const request = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body));
    expect(request.models).toEqual(["test-model", "fallback-one", "fallback-two"]);
    expect(request.provider).toEqual({ allow_fallbacks: true });
  });

  it("retries a transient provider response before streaming", async () => {
    // Keep real provider deadlines; only retry backoff is short.
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ error: { code: 503, message: "provider unavailable" } }),
        { status: 503 }
      ))
      .mockResolvedValueOnce(new Response(
        streamFromText('data: {"choices":[{"delta":{"content":"recovered"}}]}\n\ndata: [DONE]\n\n'),
        { status: 200 }
      ));
    vi.stubGlobal("fetch", fetchMock);

    const chunks: string[] = [];
    for await (const chunk of streamChatText(env, [{ role: "user", content: "hi" }])) {
      chunks.push(chunk);
    }

    expect(chunks).toEqual(["recovered"]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

describe("stalled stream recovery", () => {
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it("finishes at DONE even if the provider never closes its socket", async () => {
    const cancel = vi.fn();
    vi.stubGlobal("fetch", vi.fn(async () => new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('data: {"choices":[{"delta":{"content":"hello"}}]}\n\ndata: [DONE]\n\n'));
      }, cancel
    }))));
    const chunks: string[] = [];
    for await (const text of streamChatText(env, [{ role: "user", content: "hi" }])) chunks.push(text);
    expect(chunks).toEqual(["hello"]);
    expect(cancel).toHaveBeenCalled();
  });

  it("does not keep a lock alive on provider heartbeats without text", async () => {
    vi.useFakeTimers();
    const cancel = vi.fn();
    vi.stubGlobal("fetch", vi.fn(async () => new Response(new ReadableStream({
      start(controller) { controller.enqueue(new TextEncoder().encode(': heartbeat\n\n')); }, cancel
    }))));
    const operation = (async () => { for await (const _ of streamChatText(env, [])) { /* consume */ } })();
    const rejection = expect(operation).rejects.toMatchObject({ code: "MODEL_PROVIDER_UNAVAILABLE" });
    await vi.advanceTimersByTimeAsync(52_000);
    await rejection;
    expect(cancel).toHaveBeenCalledTimes(2);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("ends a stalled partial reply without starting another model response", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(async () => new Response(new ReadableStream({
      start(controller) { controller.enqueue(new TextEncoder().encode('data: {"choices":[{"delta":{"content":"partial"}}]}\n\n')); }
    })));
    vi.stubGlobal("fetch", fetchMock);
    const chunks: string[] = [];
    const operation = (async () => { for await (const text of streamChatText(env, [])) chunks.push(text); })();
    const rejection = expect(operation).rejects.toMatchObject({ code: "MODEL_PROVIDER_UNAVAILABLE" });
    await vi.advanceTimersByTimeAsync(26_000);
    await rejection;
    expect(chunks).toEqual(["partial"]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
