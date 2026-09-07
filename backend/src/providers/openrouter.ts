import type { Env } from "../env";
import { AppError } from "../lib/errors";
import { RequestDeadline } from "../lib/deadline";

interface OpenRouterMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

interface CompletionOptions {
  maxTokens?: number;
  temperature?: number;
}

export interface ChatGenerationOptions extends CompletionOptions {
  reasoning?: { enabled: boolean; effort?: "low" | "high"; exclude?: boolean };
}

interface OpenRouterErrorPayload {
  error?: {
    code?: number | string;
    message?: string;
    metadata?: {
      error_type?: string;
      provider_name?: string;
    };
  };
}

class OpenRouterFailure extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly retryable: boolean,
    readonly retryAfterMs = 0
  ) {
    super(message);
  }
}

const OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
const RETRYABLE_STATUSES = new Set([408, 429, 500, 502, 503, 504]);
const MAX_ATTEMPTS = 2;
const STREAM_IDLE_MS = 12_000;
const STREAM_TOTAL_MS = 60_000;

function configuredModels(env: Env): string[] {
  const candidates = [
    env.OPENROUTER_MODEL,
    ...(env.OPENROUTER_FALLBACK_MODELS ?? "").split(",")
  ];
  return [...new Set(candidates.map((model) => model.trim()).filter(Boolean))];
}

function modelSelection(env: Env): { model: string } | { models: string[] } {
  const models = configuredModels(env);
  if (models.length > 1) return { models };
  return { model: models[0] };
}

function chatProviderSelection(env: Env): Record<string, unknown> {
  const only = (env.OPENROUTER_PROVIDERS ?? "").split(",").map((name) => name.trim()).filter(Boolean);
  return {
    // Interactive chat should favor time to first token, rather than the
    // router's default price-weighted selection. Keep explicit provider policy
    // across retries instead of silently falling back to another provider.
    sort: "latency",
    ...(only.length ? { only, allow_fallbacks: false } : { allow_fallbacks: true })
  };
}

function requestHeaders(env: Env): Record<string, string> {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${env.OPENROUTER_API_KEY}`,
    "HTTP-Referer": "https://meek.chat",
    "X-Title": "Meek"
  };
}

function retryAfterMs(response: Response): number {
  const value = response.headers.get("Retry-After")?.trim();
  if (!value) return 0;
  const seconds = Number(value);
  if (Number.isFinite(seconds)) return Math.max(0, seconds * 1_000);
  const date = Date.parse(value);
  return Number.isFinite(date) ? Math.max(0, date - Date.now()) : 0;
}

async function parseFailure(response: Response): Promise<OpenRouterFailure> {
  let providerMessage = "Text generation failed.";
  let providerCode: number | string | undefined;
  let errorType: string | undefined;
  let providerName: string | undefined;
  try {
    const data = (await response.json()) as OpenRouterErrorPayload;
    providerMessage = data.error?.message?.trim() || providerMessage;
    providerCode = data.error?.code;
    errorType = data.error?.metadata?.error_type;
    providerName = data.error?.metadata?.provider_name;
  } catch {
    // The HTTP status is still enough to classify the failure.
  }

  console.warn("OpenRouter request failed", {
    status: response.status,
    providerCode,
    errorType,
    providerName
  });
  return new OpenRouterFailure(
    response.status,
    providerMessage,
    RETRYABLE_STATUSES.has(response.status),
    retryAfterMs(response)
  );
}

function publicError(error: unknown): AppError {
  if (error instanceof AppError) return error;
  if (error instanceof OpenRouterFailure) {
    const normalized = error.message.toLowerCase();
    if (
      error.status === 400 &&
      (normalized.includes("context") || normalized.includes("token"))
    ) {
      return new AppError(
        400,
        "MODEL_CONTEXT_LIMIT",
        "This conversation is too long for the selected model. Its saved memory is intact."
      );
    }
    if (error.status === 401 || error.status === 402 || error.status === 403) {
      return new AppError(
        503,
        "MODEL_CONFIGURATION_ERROR",
        "The AI service is temporarily unavailable. Please try again later."
      );
    }
  }
  return new AppError(
    503,
    "MODEL_PROVIDER_UNAVAILABLE",
    "The model provider is temporarily unavailable. Please try again."
  );
}

async function waitBeforeRetry(attempt: number, requestedDelay: number, signal?: AbortSignal) {
  const delayMs = Math.min(Math.max(requestedDelay, 250 * 2 ** attempt), 2_000);
  await new Promise<void>((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
      return;
    }
    const onAbort = () => {
      clearTimeout(timer);
      reject(signal?.reason ?? new DOMException("Aborted", "AbortError"));
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, delayMs);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

async function requestOpenRouter(
  env: Env,
  body: Record<string, unknown>,
  signal?: AbortSignal,
  deadline?: RequestDeadline
): Promise<Response> {
  // Retries belong to the caller, covering HTTP and empty SSE failures alike.
  // Nesting retries here multiplied two visible attempts into six requests.
  const pending = fetch(OPENROUTER_URL, {
    method: "POST",
    headers: requestHeaders(env),
    body: JSON.stringify(body),
    signal
  });
  const response = await (deadline ? deadline.run(pending) : pending);
  if (response.ok) return response;
  throw await (deadline ? deadline.run(parseFailure(response)) : parseFailure(response));
}

async function* readCompletionStream(response: Response, deadline: RequestDeadline): AsyncGenerator<string, void, void> {
  const body = response.body;
  if (!body) {
    throw new OpenRouterFailure(502, "The model returned an empty response.", true);
  }

  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let emittedContent = false;
  let finished = false;

  function parseEvent(event: string): string[] {
    const chunks: string[] = [];
    const dataLines = event
      .split("\n")
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).trim())
      .filter(Boolean);

    for (const data of dataLines) {
      if (data === "[DONE]") { finished = true; break; }
      let parsed: OpenRouterErrorPayload & {
        choices?: Array<{ delta?: { content?: string }; finish_reason?: string | null }>;
      };
      try {
        parsed = JSON.parse(data) as typeof parsed;
      } catch {
        continue;
      }
      if (parsed.error) {
        throw new OpenRouterFailure(
          Number(parsed.error.code) || 502,
          parsed.error.message || "The model provider ended the response.",
          !emittedContent
        );
      }
      const chunk = parsed.choices?.[0]?.delta?.content;
      if (chunk) {
        deadline.touch();
        emittedContent = true;
        chunks.push(chunk);
      }
      if (parsed.choices?.[0]?.finish_reason) { finished = true; break; }
    }
    return chunks;
  }

  try {
    while (true) {
      const { done, value } = await deadline.run(reader.read());
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = normalizeSseNewlines(buffer);
      const events = buffer.split("\n\n");
      buffer = events.pop() ?? "";
      for (const event of events) {
        for (const chunk of parseEvent(event)) yield chunk;
        if (finished) break;
      }
      if (finished) break;
    }
    buffer += decoder.decode();
    if (!finished && buffer.trim()) {
      for (const chunk of parseEvent(normalizeSseNewlines(buffer))) yield chunk;
    }
  } finally {
    void reader.cancel().catch(() => {});
    reader.releaseLock();
  }

  if (!emittedContent) {
    throw new OpenRouterFailure(502, "The model returned an empty response.", true);
  }
}

export async function* streamChatText(
  env: Env,
  messages: OpenRouterMessage[],
  signal?: AbortSignal,
  options: ChatGenerationOptions = {}
): AsyncGenerator<string, void, void> {
  let emittedAnyContent = false;
  const startedAt = Date.now();
  try {
    for (let streamAttempt = 0; streamAttempt < MAX_ATTEMPTS; streamAttempt += 1) {
      const remainingMs = STREAM_TOTAL_MS - (Date.now() - startedAt);
      if (remainingMs <= 0) throw new DOMException("The provider stopped responding.", "TimeoutError");
      const deadline = new RequestDeadline(options.reasoning?.enabled ? 25_000 : STREAM_IDLE_MS, remainingMs, signal);
      try {
        const response = await requestOpenRouter(env, {
          ...modelSelection(env),
          messages,
          max_tokens: options.maxTokens ?? 1000,
          temperature: options.temperature ?? 0.8,
          stream: true,
          reasoning: options.reasoning ?? { enabled: false },
          provider: chatProviderSelection(env)
        }, deadline.signal, deadline);
        for await (const chunk of readCompletionStream(response, deadline)) {
          emittedAnyContent = true;
          yield chunk;
        }
        return;
      } catch (error) {
        const canRestart =
          !signal?.aborted &&
          !emittedAnyContent &&
          streamAttempt === 0 &&
          (!(error instanceof OpenRouterFailure) || error.retryable);
        if (!canRestart) throw error;
        await waitBeforeRetry(streamAttempt, error instanceof OpenRouterFailure ? error.retryAfterMs : 0, signal);
      } finally {
        deadline.dispose();
      }
    }
  } catch (error) {
    if (signal?.aborted) throw error;
    throw publicError(error);
  }
}

export async function completeChatText(
  env: Env,
  messages: OpenRouterMessage[],
  options: CompletionOptions = {}
): Promise<string> {
  const deadline = new RequestDeadline(45_000, 45_000);
  try {
    for (let completionAttempt = 0; completionAttempt < MAX_ATTEMPTS; completionAttempt += 1) {
      try {
        const response = await requestOpenRouter(env, {
          ...modelSelection(env),
          messages,
          max_tokens: options.maxTokens ?? 2000,
          temperature: options.temperature ?? 0.2,
          stream: false,
          reasoning: { enabled: false },
          provider: { allow_fallbacks: true }
        }, deadline.signal, deadline);
        const data = (await deadline.run(response.json())) as OpenRouterErrorPayload & {
          choices?: Array<{ message?: { content?: string } }>;
        };
        if (data.error) {
          throw new OpenRouterFailure(
            Number(data.error.code) || 502,
            data.error.message || "Text generation failed.",
            true
          );
        }
        const content = data.choices?.[0]?.message?.content?.trim();
        if (!content) {
          throw new OpenRouterFailure(502, "The model returned an empty response.", true);
        }
        return content;
      } catch (error) {
        const canRetry =
          !deadline.signal.aborted &&
          completionAttempt === 0 &&
          (!(error instanceof OpenRouterFailure) || error.retryable);
        if (!canRetry) throw error;
        await waitBeforeRetry(completionAttempt, error instanceof OpenRouterFailure ? error.retryAfterMs : 0, deadline.signal);
      }
    }
    throw new OpenRouterFailure(502, "The model returned an empty response.", true);
  } catch (error) {
    throw publicError(error);
  } finally {
    deadline.dispose();
  }
}

function normalizeSseNewlines(value: string): string {
  return value.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
}
