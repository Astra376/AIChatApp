import { expect, it } from "vitest";
import { encode } from "fast-png";
import { inspectTransparentPng } from "./transparentPng";
function png(alpha: (x: number, y: number) => number) {
  const data = new Uint8Array(24 * 36 * 4);
  for (let y = 0; y < 36; y++) for (let x = 0; x < 24; x++) {
    const i = (y * 24 + x) * 4;
    data.set([120, 80, 65, alpha(x, y)], i);
  }
  return encode({ width: 24, height: 36, data, channels: 4, depth: 8 });
}
it("accepts a real alpha cutout with opaque character pixels", () => {
  expect(inspectTransparentPng(png((x, y) => x > 4 && x < 19 && y > 2 ? 255 : 0))).toMatchObject({ width: 24, height: 36 });
});
it.each([255, 0, 100])("rejects unusable uniform alpha %s", alpha => {
  expect(() => inspectTransparentPng(png(() => alpha))).toThrow(/transparent background/);
});
it("rejects a solid image disguised as PNG and damaged PNG data", () => {
  expect(() => inspectTransparentPng(new Uint8Array([255, 216, 255, 1]))).toThrow();
  const corrupt = png((x, y) => x > 4 && y > 3 ? 255 : 0); corrupt[40] ^= 1;
  expect(() => inspectTransparentPng(corrupt)).toThrow();
});
it("rejects huge decoded allocations before decoding", () => {
  const huge = png((x, y) => x > 4 && y > 3 ? 255 : 0);
  new DataView(huge.buffer).setUint32(16, 100000);
  expect(() => inspectTransparentPng(huge)).toThrow();
});
