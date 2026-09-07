import { hasUltra } from "../billing";
import type { RequestContext } from "../../env";
import { publicAssetUrl } from "../../lib/assets";
import { AppError } from "../../lib/errors";
import { createId } from "../../lib/ids";
import { generateImageWithFallback, storeGeneratedImage, portraitModel, portraitStyle, IMAGE_MODELS, type PortraitStyle } from "../../providers/openrouterImages";

export async function generateCharacterPortrait(
  context: RequestContext, prompt: string, preview = false, sourceAvatarUrl: string | null = null
) {
  let referenceStyle: PortraitStyle | undefined;
  if (sourceAvatarUrl) {
    const prefix = publicAssetUrl(context.env.R2_PUBLIC_BASE_URL, `portraits/${context.user!.userId}/reference.jpg`).slice(0, -"reference.jpg".length);
    const filename = sourceAvatarUrl.startsWith(prefix) ? sourceAvatarUrl.slice(prefix.length) : "";
    const owned = /^[a-zA-Z0-9_-]+\.jpg$/.test(filename) ? await context.env.ASSETS.head(`portraits/${context.user!.userId}/${filename}`) : null;
    if (!owned) {
      throw new AppError(400, "INVALID_PORTRAIT", "Choose one of your generated portraits first.");
    }
    if (owned.customMetadata?.style === "stylized" || owned.customMetadata?.style === "realistic") referenceStyle = owned.customMetadata.style;
  }
  const style = referenceStyle ?? portraitStyle(prompt);
  const premium = !preview && await hasUltra(context.env, context.user!.userId);
  let model = portraitModel(context.env, style);
  if (preview && style === "stylized") model = context.env.OPENROUTER_PORTRAIT_PREVIEW_MODEL || IMAGE_MODELS.nano;
  else if (premium) model = context.env.OPENROUTER_PORTRAIT_PREMIUM_MODEL || IMAGE_MODELS.premium;
  // Uploaded and pre-migration portraits have no style metadata. Keep their
  // identity with the general reference editor rather than guessing an art style.
  if (sourceAvatarUrl && !referenceStyle && !premium) model = IMAGE_MODELS.nano;
  const image = await generateImageWithFallback(context.env, {
    model, preview, referenceImageUrl: sourceAvatarUrl ?? undefined,
    prompt: [
      "Square full-bleed character portrait that fills the entire image frame.",
      "Do not make a circular avatar, round crop, badge, medallion, border, or framed icon.",
      sourceAvatarUrl ? "Enhance this exact portrait at full resolution. Preserve the same face, identity, pose, composition, clothing and style." : "",
      prompt
    ].join("\n")
  });
  const key = `portraits/${context.user!.userId}/${createId("portrait")}.jpg`;
  const avatarUrl = await storeGeneratedImage(context.env, key, image, sourceAvatarUrl && !referenceStyle ? undefined : style);
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

  const image = await generateImageWithFallback(context.env, {
    model: context.env.OPENROUTER_BACKGROUND_MODEL || IMAGE_MODELS.nano,
    prompt, aspectRatio: "9:16"
  });
  const imageUrl = await storeGeneratedImage(context.env, key, image);
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
