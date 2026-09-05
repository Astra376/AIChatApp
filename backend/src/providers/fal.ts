import type { Env } from "../env";
import { AppError } from "../lib/errors";
import { RequestDeadline } from "../lib/deadline";

interface FalQueueResponse {
  request_id?: string;
  status_url?: string;
  response_url?: string;
}

interface FalStatusResponse {
  status?: "IN_QUEUE" | "IN_PROGRESS" | "COMPLETED" | "FAILED";
  response_url?: string;
  error?: unknown;
  error_type?: unknown;
}

interface FalResultResponse {
  images?: Array<{ url: string }>;
}

interface FalErrorDetails {
  detail?: unknown;
  error?: unknown;
  error_type?: unknown;
  message?: unknown;
}

const FAL_QUEUE_ORIGIN = "https://queue.fal.run";
const FAL_STATUS_ATTEMPTS = 30;
const FAL_STATUS_POLL_INTERVAL_MS = 4_000;
// Leave room for authentication, R2 lookups and four image-download retries
// under the Workers free-plan subrequest limit.
const FAL_FETCH_BUDGET = 36;
const FAL_RESULT_FETCH_RESERVE = 4;
const FAL_GET_ATTEMPTS = 4;
const FAL_RETRYABLE_STATUSES = new Set([408, 429, 500, 502, 503, 504]);
const FAL_MODEL_PATTERN = /^[a-zA-Z0-9._-]+(?:\/[a-zA-Z0-9._-]+)+$/;

class FalFetchBudget {
  private used = 0;

  get remaining(): number {
    return FAL_FETCH_BUDGET - this.used;
  }

  consume(): void {
    if (this.remaining <= 0) {
      throw new AppError(504, "FAL_TIMEOUT", "Image generation timed out.");
    }
    this.used += 1;
  }
}

function diagnosticValue(value: unknown): string | undefined {
  if (typeof value === "string") return value.trim().slice(0, 300) || undefined;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return undefined;
}

async function readFalError(response: Response): Promise<{
  errorType?: string;
  detail?: string;
}> {
  try {
    const data = (await response.json()) as FalErrorDetails;
    return {
      errorType: diagnosticValue(data.error_type),
      detail: diagnosticValue(data.detail)
        ?? diagnosticValue(data.error)
        ?? diagnosticValue(data.message)
    };
  } catch {
    return {};
  }
}

async function throwFalHttpError(
  response: Response,
  input: {
    stage: "queue" | "status" | "result";
    code: "FAL_QUEUE_ERROR" | "FAL_STATUS_ERROR" | "FAL_RESULT_ERROR";
    message: string;
    requestId?: string;
  }
): Promise<never> {
  const providerError = await readFalError(response);
  console.warn("Fal request failed", {
    stage: input.stage,
    status: response.status,
    requestId: input.requestId,
    providerRequestId: response.headers.get("x-fal-request-id") ?? undefined,
    errorType: providerError.errorType,
    detail: providerError.detail
  });
  throw new AppError(502, input.code, input.message);
}

async function parseFalResponse<T>(
  response: Response,
  input: {
    stage: "queue" | "status" | "result";
    code: "FAL_QUEUE_ERROR" | "FAL_STATUS_ERROR" | "FAL_RESULT_ERROR";
    message: string;
    requestId?: string;
  }
): Promise<T> {
  try {
    return await response.json() as T;
  } catch {
    console.warn("Fal returned a malformed JSON response", {
      stage: input.stage,
      status: response.status,
      requestId: input.requestId,
      providerRequestId: response.headers.get("x-fal-request-id") ?? undefined
    });
    throw new AppError(502, input.code, input.message);
  }
}

async function fetchFal(
  url: string,
  init: RequestInit,
  input: {
    budget: FalFetchBudget;
    attempts: number;
    stage: "queue" | "status" | "result";
    requestId?: string;
    deadline: RequestDeadline;
  }
): Promise<Response> {
  let lastError: unknown;
  for (let attempt = 0; attempt < input.attempts; attempt += 1) {
    input.budget.consume();
    try {
      const response = await input.deadline.run(fetch(url, { ...init, signal: input.deadline.signal }));
      if (!FAL_RETRYABLE_STATUSES.has(response.status) || attempt === input.attempts - 1) {
        return response;
      }
      console.warn("Fal request will be retried", {
        stage: input.stage,
        status: response.status,
        requestId: input.requestId,
        providerRequestId: response.headers.get("x-fal-request-id") ?? undefined
      });
      await response.body?.cancel();
    } catch (error) {
      if (input.deadline.signal.aborted) throw new AppError(504, "FAL_TIMEOUT", "Image generation timed out. Please try again.");
      lastError = error;
      if (attempt === input.attempts - 1) {
        console.warn("Fal request failed before receiving a response", {
          stage: input.stage,
          requestId: input.requestId,
          error: error instanceof Error ? error.name : "UnknownError"
        });
        throw new AppError(502, `FAL_${input.stage.toUpperCase()}_ERROR`, "Image generation provider could not be reached.");
      }
    }
    await input.deadline.run(new Promise((resolve) => setTimeout(resolve, 300 * 2 ** attempt)));
  }
  throw lastError;
}

function requireFalConfiguration(env: Env, model: string): {
  apiKey: string;
  model: string;
} {
  const apiKey = env.FAL_API_KEY?.trim();
  const normalizedModel = model?.trim();
  if (!apiKey || !normalizedModel || !FAL_MODEL_PATTERN.test(normalizedModel)) {
    throw new AppError(500, "FAL_CONFIGURATION_ERROR", "Image generation is not configured.");
  }
  return { apiKey, model: normalizedModel };
}

function canonicalFalRequestUrl(model: string, requestId: string, operation: "status" | "result"): string {
  const encodedModel = model.split("/").map(encodeURIComponent).join("/");
  const requestUrl = `${FAL_QUEUE_ORIGIN}/${encodedModel}/requests/${encodeURIComponent(requestId)}`;
  return operation === "status" ? `${requestUrl}/status` : requestUrl;
}

function validatedFalRequestUrl(
  candidate: string | undefined,
  fallback: string,
  operation: "status" | "result",
  errorCode: "FAL_QUEUE_ERROR" | "FAL_STATUS_ERROR"
): string {
  if (!candidate) return fallback;

  let parsed: URL;
  try {
    parsed = new URL(candidate);
  } catch {
    throw new AppError(502, errorCode, "Image generation provider returned an invalid tracking URL.");
  }

  // Some queue routes track requests at the parent model (without /edit),
  // and providers may add/remove /response between status responses.
  const expectedPath = new URL(fallback).pathname.replace(/\/response$/, "");
  const parentPath = expectedPath.replace(/(\/[^/]+\/[^/]+)\/[^/]+(\/requests\/)/, "$1$2");
  const validPath = operation === "status"
    ? parsed.pathname === expectedPath || parsed.pathname === parentPath
    : [expectedPath, `${expectedPath}/response`, parentPath, `${parentPath}/response`].includes(parsed.pathname);
  if (
    parsed.origin !== FAL_QUEUE_ORIGIN
    || parsed.username
    || parsed.password
    || !validPath
  ) {
    throw new AppError(502, errorCode, "Image generation provider returned an invalid tracking URL.");
  }
  return parsed.toString();
}

function requireGeneratedImageUrl(value: unknown): string {
  if (typeof value !== "string") {
    throw new AppError(502, "FAL_RESULT_ERROR", "Image generation returned no image URL.");
  }
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "https:" || parsed.username || parsed.password) throw new Error("Unsafe URL");
    return parsed.toString();
  } catch {
    throw new AppError(502, "FAL_RESULT_ERROR", "Image generation returned an invalid image URL.");
  }
}

export async function generatePortraitWithFal(env: Env, prompt: string, preview = false, referenceImageUrl?: string): Promise<string> {
  return generateImageWithFal(env, {
    model: referenceImageUrl ? `${env.FAL_MODEL.replace(/\/edit$/, "")}/edit` : env.FAL_MODEL,
    prompt,
    aspectRatio: "1:1",
    resolution: preview ? "0.5K" : "1K",
    referenceImageUrl
  });
}

export async function generateChatBackgroundWithFal(env: Env, prompt: string): Promise<string> {
  return generateImageWithFal(env, {
    model: env.FAL_BACKGROUND_MODEL || env.FAL_MODEL,
    prompt,
    aspectRatio: "16:9",
    resolution: "1K"
  });
}

async function generateImageWithFal(
  env: Env,
  options: {
    model: string;
    prompt: string;
    aspectRatio: "1:1" | "16:9";
    resolution: "0.5K" | "1K";
    referenceImageUrl?: string;
  }
): Promise<string> {
  const configuration = requireFalConfiguration(env, options.model);
  const budget = new FalFetchBudget();
  const deadline = new RequestDeadline(140_000, 140_000);
  try {
    const queueResponse = await fetchFal(`${FAL_QUEUE_ORIGIN}/${configuration.model}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Key ${configuration.apiKey}`
      },
      body: JSON.stringify({
        prompt: options.prompt,
        aspect_ratio: options.aspectRatio,
        output_format: "jpeg",
        num_images: 1,
        resolution: options.resolution,
        ...(options.referenceImageUrl ? { image_urls: [options.referenceImageUrl] } : {})
      })
    }, {
      budget,
      deadline,
      attempts: 1,
      stage: "queue"
    });

    if (!queueResponse.ok) {
      await throwFalHttpError(queueResponse, {
        stage: "queue",
        code: "FAL_QUEUE_ERROR",
        message: "Image generation could not be queued."
      });
    }

    const queued = await deadline.run(parseFalResponse<FalQueueResponse>(queueResponse, {
      stage: "queue",
      code: "FAL_QUEUE_ERROR",
      message: "Image generation returned an invalid queue response."
    }));
    if (typeof queued?.request_id !== "string" || !queued.request_id.trim() || queued.request_id.length > 200) {
      throw new AppError(502, "FAL_QUEUE_ERROR", "Image generation did not return a request id.");
    }

    const fallbackStatusUrl = canonicalFalRequestUrl(configuration.model, queued.request_id, "status");
    const fallbackResponseUrl = canonicalFalRequestUrl(configuration.model, queued.request_id, "result");
    const statusUrl = validatedFalRequestUrl(
      queued.status_url,
      fallbackStatusUrl,
      "status",
      "FAL_QUEUE_ERROR"
    );
    let resultUrl = validatedFalRequestUrl(
      queued.response_url,
      fallbackResponseUrl,
      "result",
      "FAL_QUEUE_ERROR"
    );

    for (let attempt = 0; attempt < FAL_STATUS_ATTEMPTS; attempt += 1) {
      if (budget.remaining <= FAL_RESULT_FETCH_RESERVE) break;

      const statusResponse = await fetchFal(statusUrl, {
        headers: {
          Authorization: `Key ${configuration.apiKey}`
        }
      }, {
        budget,
      deadline,
        attempts: Math.min(FAL_GET_ATTEMPTS, budget.remaining - FAL_RESULT_FETCH_RESERVE),
        stage: "status",
        requestId: queued.request_id
      });

      if (!statusResponse.ok) {
        await throwFalHttpError(statusResponse, {
          stage: "status",
          code: "FAL_STATUS_ERROR",
          message: "Image generation status failed.",
          requestId: queued.request_id
        });
      }

      const status = await deadline.run(parseFalResponse<FalStatusResponse>(statusResponse, {
        stage: "status",
        code: "FAL_STATUS_ERROR",
        message: "Image generation returned an invalid status response.",
        requestId: queued.request_id
      }));
      if (status.status === "FAILED") {
        console.warn("Fal generation failed", {
          requestId: queued.request_id,
          errorType: diagnosticValue(status.error_type),
          detail: diagnosticValue(status.error)
        });
        throw new AppError(502, "FAL_FAILED", "Image generation failed.");
      }

      if (status.status === "COMPLETED") {
        if (status.error != null || status.error_type != null) {
          console.warn("Fal generation completed with an error", {
            requestId: queued.request_id,
            errorType: diagnosticValue(status.error_type),
            detail: diagnosticValue(status.error)
          });
          throw new AppError(502, "FAL_FAILED", "Image generation failed.");
        }
        resultUrl = validatedFalRequestUrl(
          status.response_url,
          resultUrl,
          "result",
          "FAL_STATUS_ERROR"
        );
        const resultResponse = await fetchFal(
          resultUrl,
          {
            headers: {
              Authorization: `Key ${configuration.apiKey}`
            }
          },
          {
            budget,
      deadline,
            attempts: Math.min(FAL_GET_ATTEMPTS, budget.remaining),
            stage: "result",
            requestId: queued.request_id
          }
        );
        if (!resultResponse.ok) {
          await throwFalHttpError(resultResponse, {
            stage: "result",
            code: "FAL_RESULT_ERROR",
            message: "Image generation result fetch failed.",
            requestId: queued.request_id
          });
        }
        const result = await deadline.run(parseFalResponse<FalResultResponse>(resultResponse, {
          stage: "result",
          code: "FAL_RESULT_ERROR",
          message: "Image generation returned an invalid result response.",
          requestId: queued.request_id
        }));
        return requireGeneratedImageUrl(result.images?.[0]?.url);
      }

      if (status.status !== "IN_QUEUE" && status.status !== "IN_PROGRESS") {
        throw new AppError(502, "FAL_STATUS_ERROR", "Image generation returned an invalid status.");
      }
      await deadline.run(new Promise((resolve) => setTimeout(resolve, FAL_STATUS_POLL_INTERVAL_MS)));
    }

    throw new AppError(504, "FAL_TIMEOUT", "Image generation timed out.");
  } finally {
    deadline.dispose();
  }
}
