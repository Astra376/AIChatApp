import type { Env } from "../../env";
import { createId } from "../../lib/ids";
import { AppError } from "../../lib/errors";
import { json } from "../../lib/response";
import { publicAssetUrl } from "../../lib/assets";
import { generateImageWithFallback, generateImageWithOpenRouter, storeGeneratedImage, type ImageInput, type PortraitStyle } from "../../providers/openrouterImages";

export interface ImageJobInput { image: ImageInput; outputKey: string; style?: PortraitStyle; fallback: boolean; }
export interface ImageJobResult {
  status: "pending" | "running" | "completed" | "failed";
  imageUrl?: string; model?: string; cost?: number | null; error?: string; durationMs?: number; recovered?: boolean;
}
export interface QueuedImageJob { provider: "openrouter"; id: string; }
interface StoredJob { input: ImageJobInput; result: ImageJobResult; }

/** Native Durable Object alarms keep paid image calls alive beyond waitUntil's 30-second window. */
export class ImageGenerationJob {
  constructor(private readonly state: DurableObjectState, private readonly env: Env) {}
  private async recoverSavedImage(job: StoredJob): Promise<ImageJobResult | null> {
    const saved = await this.env.ASSETS.head(job.input.outputKey);
    if (!saved) return null;
    const cost = saved.customMetadata?.cost;
    const result: ImageJobResult = {
      status: "completed", imageUrl: publicAssetUrl(this.env.R2_PUBLIC_BASE_URL, job.input.outputKey),
      model: saved.customMetadata?.model || job.input.image.model,
      cost: cost != null && Number.isFinite(Number(cost)) ? Number(cost) : null, recovered: true
    };
    await this.state.storage.put("job", { ...job, result });
    return result;
  }
  async fetch(request: Request): Promise<Response> {
    if (request.method === "POST") {
      const input = await request.json() as ImageJobInput;
      await this.state.blockConcurrencyWhile(async () => {
        if (await this.state.storage.get("job")) return;
        await this.state.storage.put("job", { input, result: { status: "pending" } } satisfies StoredJob);
        await this.state.storage.setAlarm(Date.now() + 1);
      });
    }
    const job = await this.state.storage.get<StoredJob>("job");
    // A deployment can interrupt the tiny window between saving the image and
    // recording completion. Reuse that purchased output instead of losing it.
    if (job?.result.error === "IMAGE_INTERRUPTED") {
      const recovered = await this.recoverSavedImage(job);
      if (recovered) return json(recovered);
    }
    return json(job?.result ?? { status: "failed", error: "IMAGE_JOB_MISSING" });
  }
  async alarm(): Promise<void> {
    const job = await this.state.storage.get<StoredJob>("job");
    if (!job) return;
    if (job.result.status === "completed" || job.result.status === "failed") { await this.state.storage.deleteAll(); return; }
    if (job.result.status === "running") {
      if (await this.recoverSavedImage(job)) {
        await this.state.storage.setAlarm(Date.now() + 7 * 86_400_000);
        return;
      }
      // A restarted isolate cannot know whether its previous paid POST completed.
      // Mark it retryable for an explicit user action instead of buying a duplicate.
      await this.state.storage.put("job", { ...job, result: { status: "failed", error: "IMAGE_INTERRUPTED" } });
      await this.state.storage.setAlarm(Date.now() + 7 * 86_400_000);
      return;
    }
    await this.state.storage.put("job", { ...job, result: { status: "running" } });
    const started = Date.now();
    let result: ImageJobResult;
    try {
      const image = await (job.input.fallback ? generateImageWithFallback : generateImageWithOpenRouter)(this.env, job.input.image);
      const imageUrl = await storeGeneratedImage(this.env, job.input.outputKey, image, job.input.style);
      result = { status: "completed", imageUrl, model: image.model, cost: image.cost, durationMs: Date.now() - started };
    } catch (error) {
      result = { status: "failed", error: error instanceof AppError ? error.code : "IMAGE_JOB_FAILED", durationMs: Date.now() - started };
    }
    await this.state.storage.put("job", { ...job, result });
    await this.state.storage.setAlarm(Date.now() + 7 * 86_400_000);
  }
}
export async function queueImage(env: Env, input: ImageJobInput, id = createId("image")): Promise<QueuedImageJob> {
  const response = await env.IMAGE_JOBS.get(env.IMAGE_JOBS.idFromName(id)).fetch("https://image-job/start", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(input)
  });
  if (!response.ok) throw new AppError(502, "IMAGE_QUEUE_FAILED", "The image could not be queued.");
  return { provider: "openrouter", id };
}
export async function imageJobStatus(env: Env, job: QueuedImageJob): Promise<ImageJobResult> {
  const response = await env.IMAGE_JOBS.get(env.IMAGE_JOBS.idFromName(job.id)).fetch("https://image-job/status");
  if (!response.ok) throw new AppError(502, "IMAGE_STATUS_FAILED", "Image status is temporarily unavailable.");
  return response.json() as Promise<ImageJobResult>;
}
