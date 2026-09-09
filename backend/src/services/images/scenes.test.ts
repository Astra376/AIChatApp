import { describe, expect, it } from "vitest";
import { parseVisualScene, visualSceneIdentity, type VisualScene } from "./scenes";
const previous: VisualScene = { placeId: "cafe", location: "The cafe", lighting: "sunset", weather: "clear", description: "A small cafe" };
const sources = [{position: 7, content: "We step into the moonlit garden."}];
const valid = {changed: true, placeId: "garden", location: "The garden", lighting: "moonlight", weather: "clear", description: "A moonlit garden", sourcePosition: 7, sourceQuote: "step into the moonlit garden"};
describe("grounded scene backgrounds", () => {
  it("preserves the scene across ordinary dialogue and provider failures", () => {
    expect(parseVisualScene('{"changed":false}', sources, previous)).toBe(previous);
    expect(parseVisualScene('not JSON', sources, previous)).toBe(previous);
    expect(parseVisualScene('{"changed":false}', sources)).toBeUndefined();
  });
  it("requires exact evidence at the stated source position", () => {
    expect(parseVisualScene(JSON.stringify(valid), sources, previous)?.placeId).toBe("garden");
    expect(parseVisualScene(JSON.stringify({...valid, sourcePosition: 8}), sources, previous)).toBe(previous);
    expect(parseVisualScene(JSON.stringify({...valid, sourceQuote: "we entered the castle"}), sources, previous)).toBe(previous);
  });
  it("reuses scene identity despite descriptive rewording, separates location and lighting", () => {
    expect(visualSceneIdentity("a", previous)).toBe(visualSceneIdentity("a", {...previous, description: "The same cozy cafe"}));
    expect(visualSceneIdentity("a", previous)).not.toBe(visualSceneIdentity("a", {...previous, lighting: "night"}));
    expect(visualSceneIdentity("a", previous)).not.toBe(visualSceneIdentity("a", {...previous, placeId: "bedroom"}));
  });
});
