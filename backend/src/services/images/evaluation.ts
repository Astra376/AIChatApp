import type { RequestContext } from "../../env";
import { assert } from "../../lib/errors";
import { publicAssetUrl } from "../../lib/assets";
import { imageEvaluationCasesForRun } from "./evaluationCases";
import { imageJobStatus, queueImage } from "./jobs";

// Enabled only by an expiring secret installed and removed by the owning CI job.
// This endpoint accepts fixed synthetic fixtures, never arbitrary prompts or account data.
export async function evaluateImage(context: RequestContext) {
  let auth: { token?: string; expiresAt?: number; run?: string } = {};
  try { auth = JSON.parse(context.env.IMAGE_EVALUATION_TOKEN || "{}"); } catch { }
  const received = context.request.headers.get("Authorization")?.replace(/^Bearer /, "") || "";
  assert(auth.token && auth.token.length >= 48 && auth.expiresAt && auth.expiresAt > Date.now() && auth.expiresAt < Date.now() + 3_600_000
    && auth.run && /^[a-zA-Z0-9_-]{1,64}$/.test(auth.run), 404, "NOT_FOUND", "Route not found.");
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(auth.token), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
  const proof = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(auth.token));
  assert(await crypto.subtle.verify("HMAC", key, proof, new TextEncoder().encode(received)), 404, "NOT_FOUND", "Route not found.");
  const imageEvaluationCases = imageEvaluationCasesForRun(auth.run);
  const outputFor = (id: string) => `portraits/image_evaluation/${auth.run}_${id}.${imageEvaluationCases.find(item => item.id === id)?.image.background === "transparent" ? "png" : "jpg"}`;
  if (!context.params.caseId) return { run: auth.run, cases: imageEvaluationCases.map(({ id, label, reference }) => ({ id, label, reference })) };
  const spec = imageEvaluationCases.find(item => item.id === context.params.caseId);
  assert(spec, 404, "NOT_FOUND", "Unknown evaluation case.");
  const outputKey = outputFor(spec.id);
  const id = `evaluation_${auth.run}_${spec.id}`;
  if (context.request.method === "POST") {
    const referenceKey = spec.reference ? outputFor(spec.reference) : undefined;
    if (referenceKey) assert(await context.env.ASSETS.head(referenceKey), 409, "REFERENCE_PENDING", "The reference is not ready.");
    await queueImage(context.env, {
      image: { ...spec.image, ...(referenceKey ? { referenceImageUrl: publicAssetUrl(context.env.R2_PUBLIC_BASE_URL, referenceKey) } : {}) },
      outputKey, fallback: false
    }, id);
  }
  return { id: spec.id, label: spec.label, reference: spec.reference, requestedModel: spec.image.model,
    ...await imageJobStatus(context.env, { provider: "openrouter", id }) };
}
