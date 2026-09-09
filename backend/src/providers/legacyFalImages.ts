import type { Env } from "../env";
import { AppError } from "../lib/errors";

// Read-only compatibility for images purchased before the OpenRouter migration.
// No model submission or image generation exists on this path.
export async function pollLegacyFalImage(env: Env, job: { model?: string; requestId?: string }): Promise<string | undefined> {
  if (!job.model || job.model.split("/").some(segment => !segment || segment === "." || segment === "..") || !/^[a-zA-Z0-9._/-]+$/.test(job.model) || !job.requestId || !/^[a-zA-Z0-9_-]+$/.test(job.requestId)) {
    throw new AppError(502, "IMAGE_FAILED", "The old image request is invalid.");
  }
  const url = `https://queue.fal.run/${job.model}/requests/${job.requestId}`;
  const headers = { Authorization: `Key ${env.FAL_API_KEY}` };
  const status = await fetch(`${url}/status`, { headers, signal: AbortSignal.timeout(10_000) });
  if (!status.ok) throw new Error("Legacy image status unavailable");
  const state = await status.json() as { status?: string; error?: unknown };
  if (state.status === "FAILED" || state.error) throw new AppError(502, "IMAGE_FAILED", "The old image request failed.");
  if (state.status !== "COMPLETED") return;
  const response = await fetch(url, { headers, signal: AbortSignal.timeout(10_000) });
  if (!response.ok) throw new Error("Legacy image result unavailable");
  const result = await response.json() as { images?: Array<{ url?: string }> };
  const image = result.images?.[0]?.url;
  if (!image || !/^https:\/\/(?:[a-z0-9.-]+\.)?fal\.media\//i.test(image)) throw new AppError(502, "IMAGE_FAILED", "The old image result is invalid.");
  return image;
}
