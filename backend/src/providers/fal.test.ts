import { afterEach, describe, expect, it, vi } from "vitest";
import type { Env } from "../env";
import { generateChatBackgroundWithFal, generatePortraitWithFal } from "./fal";

const MODEL = "fal-ai/nano-banana-2";
const QUEUE_URL = `https://queue.fal.run/${MODEL}`;
const STATUS_URL = `https://queue.fal.run/${MODEL}/requests/request-1/status`;
const RESPONSE_URL = `https://queue.fal.run/${MODEL}/requests/request-1`;

function env(): Env {
  return {
    FAL_API_KEY: "test-key",
    FAL_MODEL: MODEL,
    R2_PUBLIC_BASE_URL: "https://example.invalid"
  } as Env;
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("fal image provider", () => {
  it("uses the model schema and exact queue status/response fallback URLs", async () => {
    const requests: Array<{ url: string; init?: RequestInit }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input);
      requests.push({ url, init });
      if (url === QUEUE_URL) {
        return Response.json({ request_id: "request-1" });
      }
      if (url === STATUS_URL) {
        return Response.json({ status: "COMPLETED" });
      }
      if (url === RESPONSE_URL) {
        return Response.json({ images: [{ url: "https://v3.fal.media/files/generated.jpg" }] });
      }
      throw new Error(`Unexpected URL: ${url}`);
    }));

    await expect(generateChatBackgroundWithFal(env(), "a scene")).resolves.toBe(
      "https://v3.fal.media/files/generated.jpg"
    );

    expect(requests.map(({ url }) => url)).toEqual([QUEUE_URL, STATUS_URL, RESPONSE_URL]);
    expect(requests[0].init?.method).toBe("POST");
    expect(requests[1].init?.method).toBeUndefined();
    expect(requests[2].init?.method).toBeUndefined();
    expect(JSON.parse(String(requests[0].init?.body))).toEqual({
      prompt: "a scene",
      aspect_ratio: "16:9",
      output_format: "jpeg",
      num_images: 1,
      resolution: "1K"
    });
    expect(JSON.parse(String(requests[0].init?.body))).not.toHaveProperty("image_size");
    expect(new Headers(requests[0].init?.headers).get("Authorization")).toBe("Key test-key");
  });

  it("uses validated provider tracking URLs and preserves their query strings", async () => {
    const statusUrl = `${STATUS_URL}?logs=0`;
    const responseUrl = `${RESPONSE_URL}?source=queue`;
    const requests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request) => {
      const url = String(input);
      requests.push(url);
      if (url === QUEUE_URL) {
        return Response.json({
          request_id: "request-1",
          status_url: statusUrl,
          response_url: responseUrl
        });
      }
      if (url === statusUrl) {
        return Response.json({ status: "COMPLETED", response_url: responseUrl });
      }
      if (url === responseUrl) {
        return Response.json({ images: [{ url: "https://storage.googleapis.com/generated/image.jpg" }] });
      }
      throw new Error(`Unexpected URL: ${url}`);
    }));

    await expect(generateChatBackgroundWithFal(env(), "a scene")).resolves.toBe(
      "https://storage.googleapis.com/generated/image.jpg"
    );
    expect(requests).toEqual([QUEUE_URL, statusUrl, responseUrl]);
  });

  it("rejects an untrusted provider response URL without fetching it", async () => {
    const fetchMock = vi.fn(async () => Response.json({
      request_id: "request-1",
      response_url: "https://attacker.invalid/requests/request-1"
    }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(generateChatBackgroundWithFal(env(), "a scene")).rejects.toMatchObject({
      code: "FAL_QUEUE_ERROR"
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("rejects a tracking URL for a different fal model", async () => {
    const fetchMock = vi.fn(async () => Response.json({
      request_id: "request-1",
      response_url: "https://queue.fal.run/other/model/requests/request-1"
    }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(generateChatBackgroundWithFal(env(), "a scene")).rejects.toMatchObject({
      code: "FAL_QUEUE_ERROR"
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("maps malformed provider JSON to a stable diagnostic error", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    vi.stubGlobal("fetch", vi.fn(async () => new Response("not-json", {
      status: 200,
      headers: { "x-fal-request-id": "provider-request-malformed" }
    })));

    await expect(generateChatBackgroundWithFal(env(), "a scene")).rejects.toMatchObject({
      code: "FAL_QUEUE_ERROR"
    });
    expect(JSON.stringify(warn.mock.calls)).toContain("provider-request-malformed");
  });

  it("does not retry queue submissions that could duplicate a billed generation", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const fetchMock = vi.fn(async () => Response.json(
      { detail: "temporarily unavailable", error_type: "runner_server_error" },
      {
        status: 503,
        headers: { "x-fal-request-id": "provider-request-1" }
      }
    ));
    vi.stubGlobal("fetch", fetchMock);

    await expect(generateChatBackgroundWithFal(env(), "secret prompt")).rejects.toMatchObject({
      code: "FAL_QUEUE_ERROR"
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const diagnostics = JSON.stringify(warn.mock.calls);
    expect(diagnostics).toContain("provider-request-1");
    expect(diagnostics).toContain("runner_server_error");
    expect(diagnostics).not.toContain("test-key");
    expect(diagnostics).not.toContain("secret prompt");
  });

  it("surfaces completed provider errors without requesting a result", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const url = String(input);
      if (url === QUEUE_URL) return Response.json({ request_id: "request-1" });
      if (url === STATUS_URL) {
        return Response.json({
          status: "COMPLETED",
          error: "content rejected",
          error_type: "model_validation_error"
        });
      }
      throw new Error(`Unexpected URL: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(generateChatBackgroundWithFal(env(), "a scene")).rejects.toMatchObject({
      code: "FAL_FAILED"
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(JSON.stringify(warn.mock.calls)).toContain("model_validation_error");
  });

  it("bounds queue polling below the Worker external subrequest limit", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const url = String(input);
      if (url === QUEUE_URL) return Response.json({ request_id: "request-1" });
      if (url === STATUS_URL) return Response.json({ status: "IN_QUEUE" });
      throw new Error(`Unexpected URL: ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    const generation = generateChatBackgroundWithFal(env(), "a scene");
    const rejection = expect(generation).rejects.toMatchObject({ code: "FAL_TIMEOUT" });
    await vi.runAllTimersAsync();
    await rejection;

    expect(fetchMock.mock.calls.length).toBeLessThanOrEqual(36);
    expect(fetchMock.mock.calls.length).toBeLessThan(45);
  });

  it("requests square portraits through the same supported schema", async () => {
    const requestBodies: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input);
      if (url === QUEUE_URL) {
        requestBodies.push(String(init?.body));
        return Response.json({ request_id: "request-1" });
      }
      if (url === STATUS_URL) return Response.json({ status: "COMPLETED" });
      if (url === RESPONSE_URL) {
        return Response.json({ images: [{ url: "https://v3.fal.media/generated.jpg" }] });
      }
      throw new Error(`Unexpected URL: ${url}`);
    }));

    await generatePortraitWithFal(env(), "a portrait");

    expect(JSON.parse(requestBodies[0]).aspect_ratio).toBe("1:1");
  });
});

it("generates small previews and refines only the selected image at 1K", async () => {
  const requests: Array<{ url: string; body: any }> = [];
  vi.stubGlobal("fetch", vi.fn(async (input: string, init?: RequestInit) => {
    requests.push({ url: String(input), body: init?.body ? JSON.parse(String(init.body)) : null });
    if (init?.method === "POST") return Response.json({
      request_id: "request-1", status_url: STATUS_URL, response_url: `${RESPONSE_URL}/response`
    });
    if (String(input).endsWith("/status")) return Response.json({ status: "COMPLETED", response_url: RESPONSE_URL });
    return Response.json({ images: [{ url: "https://v3.fal.media/portrait.jpg" }] });
  }));
  await generatePortraitWithFal(env(), "portrait", true);
  await generatePortraitWithFal(env(), "enhance", false, "https://worker.example/preview.jpg");
  const submissions = requests.filter(request => request.body);
  expect(submissions[0].body.resolution).toBe("0.5K");
  expect(submissions[1].url).toBe(`${QUEUE_URL}/edit`);
  expect(submissions[1].body).toMatchObject({ resolution: "1K", image_urls: ["https://worker.example/preview.jpg"] });
});
