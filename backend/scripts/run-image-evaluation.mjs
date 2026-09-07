import { readFile, mkdir, writeFile } from "node:fs/promises";
import { randomBytes } from "node:crypto";
import { spawnSync } from "node:child_process";
const manifest = JSON.parse(await readFile(new URL("../evaluation/run.json", import.meta.url), "utf8"));
const origin = "https://character-chat-worker.robloxproxy.workers.dev";
const output = new URL("../evaluation/results/", import.meta.url);
await mkdir(output, { recursive: true });
const token = randomBytes(32).toString("hex");
function wrangler(args, input = "") {
  const result = spawnSync(process.execPath, ["node_modules/wrangler/bin/wrangler.js", ...args], { input, encoding: "utf8", env: process.env });
  if (result.status !== 0) throw new Error(`Wrangler ${args[0]} ${args[1]} failed: ${result.stderr || result.stdout}`);
}
async function call(path, method = "GET") {
  for (let attempt = 0; attempt < 21; attempt++) {
    const response = await fetch(`${origin}/internal/image-evaluation${path}`, { method, headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(20_000) });
    if (response.status === 404 && attempt < 20) {
      await response.body?.cancel();
      // Each fixed case uses the same Durable Object ID, so retrying this
      // queue/status endpoint cannot submit another paid provider request.
      await new Promise(resolve => setTimeout(resolve, 2_000));
      continue;
    }
    if (!response.ok) throw new Error(`Evaluation returned ${response.status} for ${path}`);
    return response.json();
  }
}
const results = new Map();
let installed = false;
let transportFailed = false;
try {
  wrangler(["secret", "put", "IMAGE_EVALUATION_TOKEN"], JSON.stringify({ token, run: manifest.id, expiresAt: Date.now() + 1_800_000 }));
  installed = true;
  const { cases } = await call("");
  if (cases.length > manifest.maximumCalls || cases.length > 28) throw new Error("Evaluation exceeds its fixed call allowance");
  async function run(spec) {
    if (spec.reference && results.get(spec.reference)?.status !== "completed") {
      results.set(spec.id, { ...spec, status: "skipped", error: "REFERENCE_FAILED" }); return;
    }
    let result = await call(`/${spec.id}`, "POST");
    const expires = Date.now() + 200_000;
    while (!["completed", "failed"].includes(result.status) && Date.now() < expires) {
      await new Promise(resolve => setTimeout(resolve, 2_000));
      result = await call(`/${spec.id}`);
    }
    if (!["completed", "failed"].includes(result.status)) result = { ...result, status: "timed_out" };
    results.set(spec.id, result);
    console.log(`${spec.id}: ${result.status}; model=${result.model || result.requestedModel}; cost=${result.cost ?? "not reported"}; duration=${result.durationMs ?? "unknown"}ms${result.error ? `; ${result.error}` : ""}`);
    if (result.imageUrl) {
      const image = await fetch(result.imageUrl, { signal: AbortSignal.timeout(30_000) });
      if (!image.ok) throw new Error(`Could not download ${spec.id}`);
      await writeFile(new URL(`${spec.id}.image`, output), new Uint8Array(await image.arrayBuffer()));
    }
    await writeFile(new URL("results.json", output), JSON.stringify({ run: manifest.id, results: [...results.values()] }, null, 2));
  }
  const remaining = new Map(cases.map(item => [item.id, item]));
  while (remaining.size) {
    const layer = [...remaining.values()].filter(item => !item.reference || results.has(item.reference));
    if (!layer.length) throw new Error("Evaluation references contain a cycle or an unknown case");
    for (let index = 0; index < layer.length; index += 4) {
      const batch = await Promise.allSettled(layer.slice(index, index + 4).map(run));
      for (let i = 0; i < batch.length; i++) if (batch[i].status === "rejected") {
        transportFailed = true;
        const spec = layer[index + i]; results.set(spec.id, { ...spec, status: "failed", error: String(batch[i].reason) });
        console.log(`${spec.id}: evaluation transport failed`);
      }
    }
    for (const spec of layer) remaining.delete(spec.id);
  }
  if (transportFailed) throw new Error("The comparison is incomplete because evaluation transport failed; completed cases remain cached for a safe retry.");
} finally {
  await writeFile(new URL("results.json", output), JSON.stringify({ run: manifest.id, results: [...results.values()] }, null, 2));
  if (installed) wrangler(["secret", "delete", "IMAGE_EVALUATION_TOKEN"], "y\n");
}
