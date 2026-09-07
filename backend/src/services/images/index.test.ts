import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Env, RequestContext } from "../../env";
import { generateImageWithFallback, storeGeneratedImage } from "../../providers/openrouterImages";
import { generateChatBackground, generateCharacterPortrait, uploadCharacterPortrait } from ".";

vi.mock("../../providers/openrouterImages", async importOriginal => ({
  ...await importOriginal<typeof import("../../providers/openrouterImages")>(),
  generateImageWithFallback: vi.fn(), storeGeneratedImage: vi.fn()
}));
const image = { bytes: new Uint8Array([255, 216, 255, 1]), mediaType: "image/jpeg" as const, model: "google/gemini-3.1-flash-image", cost: 0.04 };

describe("generateChatBackground", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("reuses an image stored by an earlier request with the same scene key", async () => {
    const head = vi.fn(async () => ({ key: "existing" }));
    const context = {
      env: {
        ASSETS: { head },
        R2_PUBLIC_BASE_URL: "https://worker.example/v1/assets"
      } as unknown as Env,
      user: { userId: "user_1" }
    } as RequestContext;

    const result = await generateChatBackground(context, "scene prompt", "initial:abc123");

    expect(result.imageUrl).toBe(
      "https://worker.example/v1/assets/chat-backgrounds%2Fuser_1%2Finitial_abc123.jpg"
    );
    expect(generateImageWithFallback).not.toHaveBeenCalled();
    expect(storeGeneratedImage).not.toHaveBeenCalled();
  });

  it("stores a newly generated image under the stable scene key", async () => {
    const head = vi.fn(async () => null);
    vi.mocked(generateImageWithFallback).mockResolvedValue(image);
    vi.mocked(storeGeneratedImage).mockResolvedValue("https://worker.example/background.jpg");
    const context = {
      env: {
        ASSETS: { head },
        R2_PUBLIC_BASE_URL: "https://worker.example/v1/assets"
      } as unknown as Env,
      user: { userId: "user_1" }
    } as RequestContext;

    const result = await generateChatBackground(context, "scene prompt", "scene:def456");

    expect(result.imageUrl).toBe("https://worker.example/background.jpg");
    expect(storeGeneratedImage).toHaveBeenCalledWith(
      context.env,
      "chat-backgrounds/user_1/scene_def456.jpg",
      image
    );
  });
});


describe("portrait identity and uploads", () => {
  it("enhances an owned low-resolution preview using its exact image reference", async () => {
    const context = { env: { ASSETS: { head: vi.fn(async () => ({key:"preview"})) }, R2_PUBLIC_BASE_URL: "https://worker.example/v1/assets", OPENROUTER_PORTRAIT_REALISTIC_MODEL: "black-forest-labs/flux.2-pro" },
      user: { userId: "user_1" } } as unknown as RequestContext;
    const source = "https://worker.example/v1/assets/portraits%2Fuser_1%2Fpreview.jpg";
    vi.mocked(generateImageWithFallback).mockResolvedValue(image);
    vi.mocked(storeGeneratedImage).mockResolvedValue("https://worker.example/refined.jpg");
    const result = await generateCharacterPortrait(context,"Keep this face",false,source);
    expect(result.avatarUrl).toBe("https://worker.example/refined.jpg");
    expect(generateImageWithFallback).toHaveBeenCalledWith(context.env,expect.objectContaining({ model: "google/gemini-3.1-flash-image", prompt: expect.stringContaining("Preserve the same face"), referenceImageUrl: source, preview: false }));
  });
  it("rejects another account's reference before any paid request", async () => {
    vi.clearAllMocks();
    const context = { env: { ASSETS: { head: vi.fn(async () => ({})) }, R2_PUBLIC_BASE_URL: "https://worker.example/v1/assets" },
      user: { userId: "user_1" } } as unknown as RequestContext;
    await expect(generateCharacterPortrait(context,"refine",false,"https://worker.example/v1/assets/portraits%2Fother%2Fpreview.jpg"))
      .rejects.toMatchObject({code:"INVALID_PORTRAIT"});
    expect(generateImageWithFallback).not.toHaveBeenCalled();
  });
  it("validates actual upload bytes instead of trusting the filename or MIME type", async () => {
    const put=vi.fn();
    const context={env:{ASSETS:{put},R2_PUBLIC_BASE_URL:"https://worker.example/v1/assets"},user:{userId:"user_1"},
      request:new Request("https://worker.example/upload",{method:"POST",body:"not an image",headers:{"Content-Type":"image/jpeg"}})} as unknown as RequestContext;
    await expect(uploadCharacterPortrait(context)).rejects.toMatchObject({code:"INVALID_PORTRAIT"});
    expect(put).not.toHaveBeenCalled();
  });
});


it("preserves the source image style when the selected portrait's refinement prompt is generic", async () => {
  vi.clearAllMocks();
  vi.mocked(generateImageWithFallback).mockResolvedValue(image);
  vi.mocked(storeGeneratedImage).mockResolvedValue("https://worker.example/refined.jpg");
  const context = { env: { ASSETS: { head: vi.fn(async () => ({ customMetadata: { style: "stylized" } })) },
    R2_PUBLIC_BASE_URL: "https://worker.example/v1/assets", OPENROUTER_PORTRAIT_STYLIZED_MODEL: "bytedance-seed/seedream-5-0-lite" },
    user: { userId: "user_1" } } as unknown as RequestContext;
  await generateCharacterPortrait(context, "Enhance this portrait", false, "https://worker.example/v1/assets/portraits%2Fuser_1%2Fpreview.jpg");
  expect(generateImageWithFallback).toHaveBeenCalledWith(context.env, expect.objectContaining({ model: "bytedance-seed/seedream-5-0-lite" }));
  expect(storeGeneratedImage).toHaveBeenCalledWith(context.env, expect.any(String), image, "stylized");
});
