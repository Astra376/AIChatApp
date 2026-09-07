import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Env, RequestContext } from "../../env";
import { generateChatBackgroundWithFal, generatePortraitWithFal } from "../../providers/fal";
import { storeRemoteImageInR2 } from "../../providers/r2";
import { generateChatBackground, generateCharacterPortrait, uploadCharacterPortrait } from ".";

vi.mock("../../providers/fal", () => ({
  generateChatBackgroundWithFal: vi.fn(),
  generatePortraitWithFal: vi.fn()
}));

vi.mock("../../providers/r2", () => ({
  storeRemoteImageInR2: vi.fn()
}));

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
    expect(generateChatBackgroundWithFal).not.toHaveBeenCalled();
    expect(storeRemoteImageInR2).not.toHaveBeenCalled();
  });

  it("stores a newly generated image under the stable scene key", async () => {
    const head = vi.fn(async () => null);
    vi.mocked(generateChatBackgroundWithFal).mockResolvedValue("https://fal.example/result.jpg");
    vi.mocked(storeRemoteImageInR2).mockResolvedValue("https://worker.example/background.jpg");
    const context = {
      env: {
        ASSETS: { head },
        R2_PUBLIC_BASE_URL: "https://worker.example/v1/assets"
      } as unknown as Env,
      user: { userId: "user_1" }
    } as RequestContext;

    const result = await generateChatBackground(context, "scene prompt", "scene:def456");

    expect(result.imageUrl).toBe("https://worker.example/background.jpg");
    expect(storeRemoteImageInR2).toHaveBeenCalledWith(
      context.env,
      "chat-backgrounds/user_1/scene_def456.jpg",
      "https://fal.example/result.jpg"
    );
  });
});


describe("portrait identity and uploads", () => {
  it("enhances an owned low-resolution preview using its exact image reference", async () => {
    const context = { env: { ASSETS: { head: vi.fn(async () => ({key:"preview"})) }, R2_PUBLIC_BASE_URL: "https://worker.example/v1/assets" },
      user: { userId: "user_1" } } as unknown as RequestContext;
    const source = "https://worker.example/v1/assets/portraits%2Fuser_1%2Fpreview.jpg";
    vi.mocked(generatePortraitWithFal).mockResolvedValue("https://fal.example/refined.jpg");
    vi.mocked(storeRemoteImageInR2).mockResolvedValue("https://worker.example/refined.jpg");
    const result = await generateCharacterPortrait(context,"Keep this face",false,source);
    expect(result.avatarUrl).toBe("https://worker.example/refined.jpg");
    expect(generatePortraitWithFal).toHaveBeenCalledWith(context.env,expect.stringContaining("Preserve the same face"),false,source);
  });
  it("rejects another account's reference before any paid request", async () => {
    vi.clearAllMocks();
    const context = { env: { ASSETS: { head: vi.fn(async () => ({})) }, R2_PUBLIC_BASE_URL: "https://worker.example/v1/assets" },
      user: { userId: "user_1" } } as unknown as RequestContext;
    await expect(generateCharacterPortrait(context,"refine",false,"https://worker.example/v1/assets/portraits%2Fother%2Fpreview.jpg"))
      .rejects.toMatchObject({code:"INVALID_PORTRAIT"});
    expect(generatePortraitWithFal).not.toHaveBeenCalled();
  });
  it("validates actual upload bytes instead of trusting the filename or MIME type", async () => {
    const put=vi.fn();
    const context={env:{ASSETS:{put},R2_PUBLIC_BASE_URL:"https://worker.example/v1/assets"},user:{userId:"user_1"},
      request:new Request("https://worker.example/upload",{method:"POST",body:"not an image",headers:{"Content-Type":"image/jpeg"}})} as unknown as RequestContext;
    await expect(uploadCharacterPortrait(context)).rejects.toMatchObject({code:"INVALID_PORTRAIT"});
    expect(put).not.toHaveBeenCalled();
  });
});
