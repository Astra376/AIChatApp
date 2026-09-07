import { hasUltra } from "../billing";
import type { RequestContext } from "../../env";
import { publicAssetUrl } from "../../lib/assets";
import { AppError } from "../../lib/errors";
import { createId } from "../../lib/ids";
import { generateChatBackgroundWithFal, generatePortraitWithFal } from "../../providers/fal";
import { storeRemoteImageInR2 } from "../../providers/r2";

export async function generateCharacterPortrait(
  context: RequestContext, prompt: string, preview = false, sourceAvatarUrl: string | null = null
) {
  if (sourceAvatarUrl) {
    const prefix = publicAssetUrl(context.env.R2_PUBLIC_BASE_URL, `portraits/${context.user!.userId}/`);
    const filename = sourceAvatarUrl.startsWith(prefix) ? sourceAvatarUrl.slice(prefix.length) : "";
    if (!/^[a-zA-Z0-9_-]+\.jpg$/.test(filename)
      || !await context.env.ASSETS.head(`portraits/${context.user!.userId}/${filename}`)) {
      throw new AppError(400, "INVALID_PORTRAIT", "Choose one of your generated portraits first.");
    }
  }
  const premium = !preview && context.env.FAL_ULTRA_MODEL && await hasUltra(context.env, context.user!.userId);
  const remoteUrl = await generatePortraitWithFal(
    premium ? { ...context.env, FAL_MODEL: context.env.FAL_ULTRA_MODEL! } : context.env,
    [
      "Square full-bleed character portrait that fills the entire image frame.",
      "Do not make a circular avatar, round crop, badge, medallion, border, or framed icon.",
      sourceAvatarUrl ? "Enhance this exact portrait at full resolution. Preserve the same face, identity, pose, composition, clothing and style." : "",
      prompt
    ].join("\n"),
    preview,
    sourceAvatarUrl ?? undefined
  );
  const key = `portraits/${context.user!.userId}/${createId("portrait")}.jpg`;
  const avatarUrl = await storeRemoteImageInR2(context.env, key, remoteUrl);
  return { avatarUrl };
}

export async function generateChatBackground(
  context: RequestContext,
  prompt: string,
  requestKey: string | null = null
) {
  const stableName = requestKey
    ?.replace(/[^a-zA-Z0-9._-]/g, "_")
    .replace(/^[_\.]+|[_\.]+$/g, "")
    .slice(0, 180);
  const key = stableName
    ? `chat-backgrounds/${context.user!.userId}/${stableName}.jpg`
    : `chat-backgrounds/${context.user!.userId}/${createId("background")}.jpg`;

  if (stableName && await context.env.ASSETS.head(key)) {
    return { imageUrl: publicAssetUrl(context.env.R2_PUBLIC_BASE_URL, key) };
  }

  const remoteUrl = await generateChatBackgroundWithFal(context.env, prompt);
  const imageUrl = await storeRemoteImageInR2(context.env, key, remoteUrl);
  return { imageUrl };
}

export async function uploadCharacterPortrait(context: RequestContext) {
  const maxBytes = 10 * 1024 * 1024;
  if (Number(context.request.headers.get("Content-Length")) > maxBytes) throw new AppError(413,"PORTRAIT_TOO_LARGE","Choose an image smaller than 10 MB.");
  const reader = context.request.body?.getReader();
  if (!reader) throw new AppError(400,"PORTRAIT_REQUIRED","Choose an image first.");
  const parts: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const chunk = await reader.read(); if (chunk.done) break;
      size += chunk.value.byteLength;
      if (size > maxBytes) { await reader.cancel(); throw new AppError(413,"PORTRAIT_TOO_LARGE","Choose an image smaller than 10 MB."); }
      parts.push(chunk.value);
    }
  } finally { reader.releaseLock(); }
  const data = new Uint8Array(size); let offset = 0; for (const part of parts) { data.set(part,offset); offset += part.byteLength; }
  if (data.length < 4 || data[0] !== 0xff || data[1] !== 0xd8 || data[2] !== 0xff) throw new AppError(400,"INVALID_PORTRAIT","Choose a valid JPEG image.");
  const key = `portraits/${context.user!.userId}/${createId("upload")}.jpg`;
  await context.env.ASSETS.put(key,data,{ httpMetadata: { contentType:"image/jpeg", cacheControl:"public, max-age=31536000, immutable" } });
  return { avatarUrl: publicAssetUrl(context.env.R2_PUBLIC_BASE_URL,key) };
}
