import { AppError } from "./errors";

const PUBLIC_ASSET_KEY_PATTERN =
  /^(?:chat-backgrounds|portraits|character-art)\/[a-zA-Z0-9_-]{1,128}\/[a-zA-Z0-9._-]{1,200}\.(?:jpe?g|png|webp)$/;

export const PUBLIC_ASSET_CACHE_CONTROL = "public, max-age=31536000, immutable";

export function requirePublicAssetKey(value: string): string {
  if (!PUBLIC_ASSET_KEY_PATTERN.test(value)) {
    throw new AppError(404, "ASSET_NOT_FOUND", "Asset not found.");
  }
  return value;
}

export function publicAssetUrl(baseUrl: string, key: string): string {
  requirePublicAssetKey(key);

  let parsed: URL;
  try {
    parsed = new URL(baseUrl);
  } catch {
    throw new AppError(500, "R2_BASE_URL_INVALID", "The public asset URL is not configured correctly.");
  }
  if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new AppError(500, "R2_BASE_URL_INVALID", "The public asset URL is not configured correctly.");
  }

  return `${parsed.toString().replace(/\/$/, "")}/${encodeURIComponent(key)}`;
}
