import { afterEach, expect, it, vi } from "vitest";
import type { Env, RequestContext } from "../../env";
import { generateImageWithOpenRouter, storeGeneratedImage, IMAGE_MODELS } from "../../providers/openrouterImages";
import { ImageGenerationJob } from "./jobs";
import { evaluateImage } from "./evaluation";
vi.mock("../../providers/openrouterImages", async original => ({
  ...await original<typeof import("../../providers/openrouterImages")>(), generateImageWithOpenRouter: vi.fn(), storeGeneratedImage: vi.fn()
}));
afterEach(() => vi.clearAllMocks());
function fixture() {
  const data = new Map<string, unknown>();
  const alarm = vi.fn();
  let lock = Promise.resolve();
  const state = { storage: { get: async (key: string) => data.get(key), put: async (key: string, value: unknown) => { data.set(key, value); },
    setAlarm: alarm, deleteAll: async () => data.clear() },
    blockConcurrencyWhile: (fn: () => Promise<void>) => { lock = lock.then(fn); return lock; } } as unknown as DurableObjectState;
  const object = new ImageGenerationJob(state, {} as Env);
  const input = { image: { model: IMAGE_MODELS.realistic, prompt: "a portrait" }, outputKey: "portraits/u/test.jpg", fallback: false };
  return { object, data, input, alarm };
}
it("claims a duplicate job once and exposes its completed image without another paid call", async () => {
  const { object, input, alarm } = fixture();
  const start = () => object.fetch(new Request("https://job/start", { method: "POST", body: JSON.stringify(input) }));
  await Promise.all([start(), start()]);
  expect(alarm).toHaveBeenCalledOnce();
  vi.mocked(generateImageWithOpenRouter).mockResolvedValue({bytes:new Uint8Array([255,216,255,1]),mediaType:"image/jpeg",model:IMAGE_MODELS.realistic,cost:.03});
  vi.mocked(storeGeneratedImage).mockResolvedValue("https://assets.example/result.jpg");
  await object.alarm();
  await start();
  expect(await (await object.fetch(new Request("https://job/status"))).json()).toMatchObject({status:"completed",cost:.03});
  expect(generateImageWithOpenRouter).toHaveBeenCalledOnce();
});
it("never resubmits a paid job if its isolate was interrupted while running", async () => {
  const { object, data, input } = fixture();
  data.set("job", { input, result: { status: "running" } });
  await object.alarm();
  expect(await (await object.fetch(new Request("https://job/status"))).json()).toMatchObject({status:"failed",error:"IMAGE_INTERRUPTED"});
  expect(generateImageWithOpenRouter).not.toHaveBeenCalled();
});
it("requires a live evaluation secret and fixed fixture id before any job can start", async () => {
  const fetch = vi.fn(); const token = "a".repeat(64);
  const context = {env:{IMAGE_EVALUATION_TOKEN:JSON.stringify({token,run:"test",expiresAt:Date.now()+600000}), IMAGE_JOBS:{get:()=>({fetch})}},
    params:{caseId:"photo_flux"},request:new Request("https://worker/internal/image-evaluation/photo_flux", {method:"POST"})} as unknown as RequestContext;
  await expect(evaluateImage(context)).rejects.toMatchObject({status:404});
  context.request = new Request(context.request.url,{method:"POST",headers:{Authorization:`Bearer ${token}`}});
  context.params.caseId="arbitrary_prompt";
  await expect(evaluateImage(context)).rejects.toMatchObject({status:404});
  context.env.IMAGE_EVALUATION_TOKEN=JSON.stringify({token,run:"test",expiresAt:Date.now()-1});
  context.params.caseId="photo_flux";
  await expect(evaluateImage(context)).rejects.toMatchObject({status:404});
  expect(fetch).not.toHaveBeenCalled();
});
it("lists the fixed comparison cases with a valid short-lived credential", async () => {
  const token = "a".repeat(64);
  const context = { env: { IMAGE_EVALUATION_TOKEN: JSON.stringify({ token, run: "comparison", expiresAt: Date.now() + 600000 }) },
    params: {}, request: new Request("https://worker/internal/image-evaluation", { headers: { Authorization: `Bearer ${token}` } }) } as unknown as RequestContext;
  const result = await evaluateImage(context);
  expect(result).toMatchObject({ run: "comparison" });
  expect("cases" in result && result.cases).toHaveLength(24);
});
