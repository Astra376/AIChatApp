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

  it("passes bounded reasoning options while never forwarding private reasoning deltas", async () => {
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response(streamFromText([
      'data: {"choices":[{"delta":{"reasoning":"private analysis"}}]}', '',
      'data: {"choices":[{"delta":{"content":"My reply"}}]}', '', 'data: [DONE]', ''
    ].join("\n"))));
    vi.stubGlobal("fetch", fetchMock);
    const chunks: string[] = [];
    for await (const chunk of streamChatText(env, [], undefined, {reasoning: {enabled: true, effort: "low", exclude: true}, maxTokens: 2048})) chunks.push(chunk);
    expect(chunks).toEqual(["My reply"]);
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toMatchObject({reasoning: {enabled: true, effort: "low", exclude: true}, max_tokens: 2048});
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

  it("delivers content while the provider socket is still open", async () => {
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    vi.stubGlobal("fetch", vi.fn(async () => new Response(new ReadableStream({
      start(value) { controller = value; }
    }))));
    const iterator = streamChatText(env, [{ role: "user", content: "hi" }]);
    const first = iterator.next();
    await vi.waitFor(() => expect(controller).toBeDefined());
    controller.enqueue(new TextEncoder().encode('data: {"choices":[{"delta":{"content":"first"}}]}\n\n'));
    expect(await first).toEqual({ value: "first", done: false });
    const second = iterator.next();
    controller.enqueue(new TextEncoder().encode('data: {"choices":[{"delta":{"content":" second"}}]}\n\n'));
    expect(await second).toEqual({ value: " second", done: false });
    controller.enqueue(new TextEncoder().encode('data: [DONE]\n\n'));
    expect((await iterator.next()).done).toBe(true);
  });

  it("does not multiply HTTP failures into nested retry loops", async () => {
    const fetchMock = vi.fn(async () => new Response(
      JSON.stringify({ error: { code: 503, message: "unavailable" } }), { status: 503 }
    ));
    vi.stubGlobal("fetch", fetchMock);
    const operation = (async () => { for await (const _ of streamChatText(env, [])) { /* consume */ } })();
    await expect(operation).rejects.toMatchObject({ code: "MODEL_PROVIDER_UNAVAILABLE" });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("requests nonthinking, low latency streaming and preserves an explicit provider restriction", async () => {
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response(
      streamFromText('data: {"choices":[{"delta":{"content":"hello"}}]}\n\ndata: [DONE]\n\n')
    ));
    vi.stubGlobal("fetch", fetchMock);
    for await (const _ of streamChatText({ ...env, OPENROUTER_PROVIDERS: "venice" }, [])) { /* consume */ }
    const request = JSON.parse(String(fetchMock.mock.calls[0][1]?.body));
    expect(request).toMatchObject({
      stream: true,
      reasoning: { enabled: false },
      provider: { only: ["venice"], allow_fallbacks: false, sort: "latency" }
    });
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
    await vi.advanceTimersByTimeAsync(61_000);
    await rejection;
    expect(cancel).toHaveBeenCalledTimes(2);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("allows prefill before the first token without restarting a healthy response", async () => {
    vi.useFakeTimers();
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const fetchMock = vi.fn(async () => new Response(new ReadableStream({
      start(value) { controller = value; }
    })));
    vi.stubGlobal("fetch", fetchMock);
    const chunks: string[] = [];
    const operation = (async () => { for await (const text of streamChatText(env, [])) chunks.push(text); })();
    await vi.advanceTimersByTimeAsync(15_000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    controller.enqueue(new TextEncoder().encode('data: {"choices":[{"delta":{"content":"Ready"}}]}\n\ndata: [DONE]\n\n'));
    await operation;
    expect(chunks).toEqual(["Ready"]);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("reports a lost terminal event after partial text without generating a second answer", async () => {
    const fetchMock = vi.fn(async () => new Response(streamFromText(
      'data: {"choices":[{"delta":{"content":"Partial"}}]}\n\n'
    )));
    vi.stubGlobal("fetch", fetchMock);
    const operation = (async () => { for await (const _ of streamChatText(env, [])) { /* consume */ } })();
    await expect(operation).rejects.toMatchObject({ code: "MODEL_PROVIDER_UNAVAILABLE" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
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
    await vi.advanceTimersByTimeAsync(13_000);
    await rejection;
    expect(chunks).toEqual(["partial"]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("bounds the entire stream even if content keeps arriving", async () => {
    vi.useFakeTimers();
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    vi.stubGlobal("fetch", vi.fn(async () => new Response(new ReadableStream({
      start(value) { controller = value; }
    }))));
    const operation = (async () => { for await (const _ of streamChatText(env, [])) { /* consume */ } })();
    const rejection = expect(operation).rejects.toMatchObject({ code: "MODEL_PROVIDER_UNAVAILABLE" });
    await vi.advanceTimersByTimeAsync(1);
    for (let index = 0; index < 6; index++) {
      controller.enqueue(new TextEncoder().encode('data: {"choices":[{"delta":{"content":"word "}}]}\n\n'));
      await vi.advanceTimersByTimeAsync(10_000);
    }
    await rejection;
    expect(vi.getTimerCount()).toBe(0);
  });
});
