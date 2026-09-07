import { afterEach, expect, it, vi } from "vitest";
import type { Env } from "../env";
import { generateImageWithFallback, generateImageWithOpenRouter, imageRequest, IMAGE_MODELS, portraitStyle, storeGeneratedImage } from "./openrouterImages";
const env = { OPENROUTER_API_KEY: "test-key", OPENROUTER_PROVIDERS: "venice" } as Env;
const jpeg = btoa(String.fromCharCode(255, 216, 255, 1));
const result = () => new Response(JSON.stringify({ data: [{ b64_json: jpeg, media_type: "image/png" }], usage: { cost: 0.035 } }));
afterEach(() => vi.unstubAllGlobals());
it("uses the dedicated image endpoint and provider-independent routing with image references", async () => {
  const fetch = vi.fn(async () => result()); vi.stubGlobal("fetch", fetch);
  const image = await generateImageWithOpenRouter(env, { model: IMAGE_MODELS.realistic, prompt: "portrait", referenceImageUrl: "https://assets.example/reference.jpg" });
  expect(fetch).toHaveBeenCalledOnce();
  const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
  expect(url).toBe("https://openrouter.ai/api/v1/images");
  expect(JSON.parse(init.body as string)).toMatchObject({ model: IMAGE_MODELS.realistic, provider: { sort: "price" }, input_references: [{ type: "image_url", image_url: { url: "https://assets.example/reference.jpg" } }] });
  expect(init.body).not.toContain("venice");
  expect(image.mediaType).toBe("image/jpeg"); expect(image.cost).toBe(0.035);
});
it("uses supported resolutions and a 512px Nano preview", () => {
  expect(imageRequest({ model: IMAGE_MODELS.stylized, prompt: "anime", preview: true })).toMatchObject({ resolution: "512" });
  expect(imageRequest({ model: IMAGE_MODELS.nano, prompt: "preview", preview: true })).toMatchObject({ resolution: "512" });
  expect(imageRequest({ model: IMAGE_MODELS.realistic, prompt: "photo" })).not.toHaveProperty("resolution");
  expect(imageRequest({ model: IMAGE_MODELS.realistic, prompt: "photo", preview: true })).toMatchObject({ size: "512x512" });
  expect(portraitStyle("Professional anime illustration")).toBe("stylized");
  expect(portraitStyle("Photorealistic portrait, no anime")).toBe("realistic");
});
it("falls back only after an explicit model rejection, preserving the identity reference", async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response("missing", { status: 404 })).mockResolvedValueOnce(result()); vi.stubGlobal("fetch", fetch);
  const image = await generateImageWithFallback(env, { model: IMAGE_MODELS.background, prompt: "scene", referenceImageUrl: "https://assets.example/reference.jpg" });
  expect(image.model).toBe(IMAGE_MODELS.nano);
  expect(JSON.parse(fetch.mock.calls[1][1].body)).toMatchObject({ model: IMAGE_MODELS.nano, input_references: [{ image_url: { url: "https://assets.example/reference.jpg" } }] });
});
it("does not silently purchase another image after an ambiguous timeout", async () => {
  const fetch = vi.fn().mockRejectedValue(new DOMException("Timed out", "TimeoutError")); vi.stubGlobal("fetch", fetch);
  await expect(generateImageWithFallback(env, { model: IMAGE_MODELS.realistic, prompt: "photo" })).rejects.toMatchObject({ code: "IMAGE_TIMEOUT" });
  expect(fetch).toHaveBeenCalledOnce();
});
it("rejects missing and non-image output without trusting the MIME label", async () => {
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({data:[]})))
    .mockResolvedValueOnce(new Response(JSON.stringify({data:[{b64_json:btoa("<html>error</html>"),media_type:"image/png"}]})));
  vi.stubGlobal("fetch", fetch);
  for (const code of ["IMAGE_EMPTY", "IMAGE_INVALID_OUTPUT"]) await expect(generateImageWithOpenRouter(env, {model:IMAGE_MODELS.nano,prompt:"portrait"})).rejects.toMatchObject({code});
});
it("writes bytes directly to R2 and records style/model metadata for future expressions", async () => {
  const put = vi.fn();
  await storeGeneratedImage({ ASSETS: { put }, R2_PUBLIC_BASE_URL: "https://worker.example/v1/assets" } as unknown as Env,
    "portraits/u/p.jpg", {bytes:new Uint8Array([255,216,255,1]),mediaType:"image/jpeg",model:IMAGE_MODELS.stylized,cost:0.035}, "stylized");
  expect(put).toHaveBeenCalledWith("portraits/u/p.jpg",expect.any(Uint8Array),expect.objectContaining({customMetadata:{style:"stylized",model:IMAGE_MODELS.stylized,cost:"0.035"}}));
});
it("requires native PNG alpha and does not substitute an opaque fallback", async () => {
  expect(imageRequest({ model: IMAGE_MODELS.transparent, prompt: "body", background: "transparent", aspectRatio: "2:3" })).toMatchObject({ background: "transparent", output_format: "png", aspect_ratio: "2:3" });
  const fetch = vi.fn().mockResolvedValue(new Response("unsupported", { status: 404 })); vi.stubGlobal("fetch", fetch);
  await expect(generateImageWithFallback(env, { model: IMAGE_MODELS.transparent, prompt: "body", background: "transparent" })).rejects.toMatchObject({ code: "IMAGE_UPSTREAM_404" });
  expect(fetch).toHaveBeenCalledOnce();
  await expect(generateImageWithOpenRouter(env, { model: IMAGE_MODELS.nano, prompt: "body", background: "transparent" })).rejects.toMatchObject({ code: "IMAGE_ALPHA_UNSUPPORTED" });
  expect(fetch).toHaveBeenCalledOnce();
});
