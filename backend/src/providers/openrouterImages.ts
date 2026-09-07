import type { Env } from "../env";
import { AppError } from "../lib/errors";
import { publicAssetUrl } from "../lib/assets";
import { inspectTransparentPng } from "./transparentPng";

export const IMAGE_MODELS = {
  realistic: "black-forest-labs/flux.2-pro",
  stylized: "google/gemini-3.1-flash-image",
  background: "black-forest-labs/flux.2-klein-9b",
  nano: "google/gemini-3.1-flash-image",
  premium: "google/gemini-3-pro-image",
  transparent: "openai/gpt-image-1",
  transparentMini: "openai/gpt-image-1-mini",
  riverflow: "sourceful/riverflow-v2.5-pro"
} as const;
export type PortraitStyle = "realistic" | "stylized";
export interface ImageInput {
  model: string;
  prompt: string;
  referenceImageUrl?: string;
  aspectRatio?: "1:1" | "3:4" | "2:3" | "9:16";
  background?: "transparent" | "opaque";
  quality?: "low" | "medium" | "high";
  preview?: boolean;
  // An explicit size is used only by the bounded capability evaluation.
  size?: string;
}
export interface GeneratedImage {
  bytes: Uint8Array;
  mediaType: "image/png" | "image/jpeg" | "image/webp";
  model: string;
  cost: number | null;
  transparency?: { width: number; height: number; clearFraction: number };
}
export function portraitStyle(prompt: string): PortraitStyle {
  const positive = prompt.replace(/\b(?:no|not|without)\s+(?:anime|cartoon|illustration|photorealistic)\b/gi, "");
  if (/photorealis|photograph|live[- ]action/i.test(positive)) return "realistic";
  return /anime|manga|illustrat|cartoon|cel[- ]shad|watercolou?r|digital paint|comic|styliz|stylis|pixel art/i.test(positive) ? "stylized" : "realistic";
}
export function portraitModel(env: Env, style: PortraitStyle, expression = false): string {
  return (expression
    ? style === "stylized" ? env.OPENROUTER_EXPRESSION_STYLIZED_MODEL : env.OPENROUTER_EXPRESSION_REALISTIC_MODEL
    : style === "stylized" ? env.OPENROUTER_PORTRAIT_STYLIZED_MODEL : env.OPENROUTER_PORTRAIT_REALISTIC_MODEL) || IMAGE_MODELS.nano;
}
export function imageRequest(input: ImageInput): Record<string, unknown> {
  const body: Record<string, unknown> = {
    model: input.model, prompt: input.prompt, n: 1, aspect_ratio: input.aspectRatio ?? "1:1",
    // Image providers are independent of the text model's Venice restriction.
    provider: { sort: "price", allow_fallbacks: true }
  };
  if (input.model.includes("seedream-5-0-lite")) body.resolution = "2K";
  else if (input.model.startsWith("google/")) body.resolution = input.preview && input.model.includes("flash") ? "512" : "1K";
  if (input.model.startsWith("black-forest-labs/")) body.output_format = "jpeg";
  if (input.model.startsWith("sourceful/")) body.resolution = "1K";
  if (input.quality) body.quality = input.quality;
  if (input.background === "transparent") {
    // A PNG extension or a prompt saying "transparent" cannot create an alpha channel.
    if (!new Set<string>([IMAGE_MODELS.transparent, IMAGE_MODELS.transparentMini, IMAGE_MODELS.riverflow]).has(input.model)) {
      throw new AppError(503, "IMAGE_ALPHA_UNSUPPORTED", "The character artwork model must support transparent PNG images.");
    }
    body.background = "transparent";
    body.output_format = "png";
  } else if (input.background && input.model.startsWith("openai/")) body.background = input.background;
  // Confirmed by the live capability test: FLUX Pro accepts native 512px images.
  if (input.preview && input.model === IMAGE_MODELS.realistic) {
    delete body.aspect_ratio;
    body.size = "512x512";
  }
  if (input.size) { delete body.resolution; delete body.aspect_ratio; body.size = input.size; }
  if (input.referenceImageUrl) body.input_references = [{ type: "image_url", image_url: { url: input.referenceImageUrl } }];
  return body;
}
function mediaType(bytes: Uint8Array): GeneratedImage["mediaType"] {
  if (bytes.length >= 8 && bytes[0] === 137 && bytes[1] === 80 && bytes[2] === 78 && bytes[3] === 71 && bytes[4] === 13 && bytes[5] === 10) return "image/png";
  if (bytes.length >= 4 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) return "image/jpeg";
  if (bytes.length >= 12 && new TextDecoder().decode(bytes.slice(0, 4)) === "RIFF" && new TextDecoder().decode(bytes.slice(8, 12)) === "WEBP") return "image/webp";
  throw new AppError(502, "IMAGE_INVALID_OUTPUT", "The image provider returned an invalid image.");
}
export async function generateImageWithOpenRouter(env: Env, input: ImageInput): Promise<GeneratedImage> {
  if (!env.OPENROUTER_API_KEY?.trim()) throw new AppError(503, "IMAGE_CONFIGURATION", "Image generation is not configured.");
  const requestBody = imageRequest(input);
  let response: Response;
  try {
    response = await fetch("https://openrouter.ai/api/v1/images", {
      method: "POST", headers: { Authorization: `Bearer ${env.OPENROUTER_API_KEY}`, "Content-Type": "application/json", "X-Title": "Meek" },
      body: JSON.stringify(requestBody), signal: AbortSignal.timeout(input.background === "transparent" ? 150_000 : 90_000)
    });
  } catch {
    // Never automatically repeat a submitted paid request after an ambiguous timeout.
    throw new AppError(504, "IMAGE_TIMEOUT", "Image generation took too long. Please try again.");
  }
  if (!response.ok) {
    await response.body?.cancel();
    throw new AppError(response.status === 429 ? 429 : 502, `IMAGE_UPSTREAM_${response.status}`, "The image model is temporarily unavailable.");
  }
  const reader = response.body?.getReader();
  if (!reader) throw new AppError(502, "IMAGE_EMPTY", "The image provider returned no image.");
  const parts: Uint8Array[] = []; let length = 0;
  try {
    while (true) {
      const part = await reader.read(); if (part.done) break;
      length += part.value.length;
      if (length > 22_000_000) { await reader.cancel(); throw new AppError(502, "IMAGE_TOO_LARGE", "The generated image is too large."); }
      parts.push(part.value);
    }
  } catch (error) {
    if (error instanceof AppError) throw error;
    throw new AppError(504, "IMAGE_TIMEOUT", "Image generation took too long. Please try again.");
  } finally { reader.releaseLock(); }
  const body = new Uint8Array(length); let offset = 0;
  for (const part of parts) { body.set(part, offset); offset += part.length; }
  let result: { data?: Array<{ b64_json?: string }>; usage?: { cost?: number } };
  try { result = JSON.parse(new TextDecoder().decode(body)); }
  catch { throw new AppError(502, "IMAGE_INVALID_OUTPUT", "The image provider returned an invalid response."); }
  const encoded = result.data?.[0]?.b64_json;
  if (!encoded || encoded.length > 20_000_000) throw new AppError(502, "IMAGE_EMPTY", "The image provider returned no usable image.");
  let bytes: Uint8Array;
  try {
    const decoded = atob(encoded);
    bytes = new Uint8Array(decoded.length);
    for (let index = 0; index < decoded.length; index++) bytes[index] = decoded.charCodeAt(index);
  }
  catch { throw new AppError(502, "IMAGE_INVALID_OUTPUT", "The image provider returned an invalid image."); }
  const transparency = input.background === "transparent" ? inspectTransparentPng(bytes) : undefined;
  return { bytes, mediaType: mediaType(bytes), model: input.model, cost: typeof result.usage?.cost === "number" ? result.usage.cost : null, ...(transparency ? { transparency } : {}) };
}
export async function generateImageWithFallback(env: Env, input: ImageInput): Promise<GeneratedImage> {
  try { return await generateImageWithOpenRouter(env, input); }
  catch (error) {
    // Retry only an explicit provider rejection, keeping the original reference.
    if (input.background === "transparent" || !(error instanceof AppError) || !/^IMAGE_UPSTREAM_(400|404|422|500|502|503)$/.test(error.code) || input.model === IMAGE_MODELS.nano) throw error;
    return generateImageWithOpenRouter(env, { ...input, model: IMAGE_MODELS.nano, size: undefined });
  }
}
export async function storeGeneratedImage(env: Env, key: string, image: GeneratedImage, style?: PortraitStyle): Promise<string> {
  await env.ASSETS.put(key, image.bytes, {
    httpMetadata: { contentType: image.mediaType, cacheControl: "public, max-age=31536000, immutable" },
    customMetadata: { model: image.model, ...(style ? { style } : {}), ...(image.cost == null ? {} : { cost: String(image.cost) }),
      ...(image.transparency ? { alpha: "verified", width: String(image.transparency.width), height: String(image.transparency.height), clearFraction: String(image.transparency.clearFraction) } : {}) }
  });
  return publicAssetUrl(env.R2_PUBLIC_BASE_URL, key);
}
