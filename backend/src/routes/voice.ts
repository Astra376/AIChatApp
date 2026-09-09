import { json } from "../lib/response";
import { parseJson, requireString } from "../lib/validation";
import { assert } from "../lib/errors";
import { createVoice, getVoiceAsset, listVoices, previewVoice, setCharacterVoice, speakMessage } from "../services/voice";
import type { RouteDefinition } from "./types";
export const voiceRoutes: RouteDefinition[] = [
  { method: "GET", path: "/v1/voices", auth: true, handler: async context => json(await listVoices(context)) },
  { method: "POST", path: "/v1/voices", auth: true, handler: async context => {
    assert(Number(context.request.headers.get("Content-Length") ?? 0) <= 11_000_000, 413, "VOICE_SAMPLE_INVALID", "Sample must be under 10 MB.");
    const form = await context.request.formData();
    const sample = form.get("sample");
    return json(await createVoice(context, { name: requireString(form.get("name"), "name", 60),
      description: String(form.get("description") ?? "").trim().slice(0,1000),
      requestKey: requireString(form.get("requestKey"), "requestKey", 100),
      sample: sample instanceof File ? sample : undefined, public: form.get("public") === "true" }));
  } },
  { method: "POST", path: "/v1/voices/:id/preview", auth: true, handler: async context => json(await previewVoice(context, context.params.id)) },
  { method: "PATCH", path: "/v1/characters/:id/voice", auth: true, handler: async context => {
    const body = await parseJson<{ voiceId?: string }>(context.request);
    return json(await setCharacterVoice(context, context.params.id, requireString(body.voiceId, "voiceId", 250)));
  } },
  { method: "POST", path: "/v1/voices/speak", auth: true, handler: async context => {
    const body = await parseJson<{ conversationId?: string; messageId?: string }>(context.request);
    return json(await speakMessage(context, { conversationId: requireString(body.conversationId,"conversationId",200), messageId: requireString(body.messageId,"messageId",200) }));
  } },
  { method: "GET", path: "/v1/voice-assets/*key", handler: getVoiceAsset }
];
