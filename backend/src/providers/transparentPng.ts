import { decode, hasPngSignature } from "fast-png";
import { AppError } from "../lib/errors";

/** Validate pixels, not a filename: opaque/checkerboard PNGs must never cover the chat scene. */
export function inspectTransparentPng(bytes: Uint8Array) {
  const invalid = () => new AppError(502, "IMAGE_ALPHA_REQUIRED", "The character artwork did not have a usable transparent background. Please try again.");
  if (bytes.length < 33 || !hasPngSignature(bytes)) throw invalid();
  const header = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const width = header.getUint32(16), height = header.getUint32(20);
  // Check dimensions before allocating a decoded image inside the Worker.
  if (!width || !height || width > 3072 || height > 3072 || width * height > 4_800_000) throw invalid();
  if (![8, 16].includes(bytes[24]) || ![4, 6].includes(bytes[25])) throw invalid();
  try {
    const png = decode(bytes, { checkCrc: true });
    if (png.channels !== 2 && png.channels !== 4) throw invalid();
    const maximum = png.depth === 16 ? 65535 : 255;
    let clear = 0, solid = 0;
    for (let index = png.channels - 1; index < png.data.length; index += png.channels) {
      const alpha = png.data[index];
      if (alpha <= maximum * 0.02) clear++;
      if (alpha >= maximum * 0.95) solid++;
    }
    const pixels = width * height;
    if (clear / pixels < 0.10 || solid / pixels < 0.10) throw invalid();
    return { width, height, clearFraction: clear / pixels };
  } catch { throw invalid(); }
}
