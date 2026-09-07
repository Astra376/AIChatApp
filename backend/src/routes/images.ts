import { evaluateImage } from "../services/images/evaluation";
import { generateCharacterPortrait, generateChatBackground, uploadCharacterPortrait } from "../services/images";
import { json } from "../lib/response";
import { optionalString, parseJson, requireString } from "../lib/validation";
import type { RouteDefinition } from "./types";

export const imageRoutes: RouteDefinition[] = [
  { method: "GET", path: "/internal/image-evaluation", handler: async context => json(await evaluateImage(context)) },
  { method: "POST", path: "/internal/image-evaluation/:caseId", handler: async context => json(await evaluateImage(context)) },
  { method: "GET", path: "/internal/image-evaluation/:caseId", handler: async context => json(await evaluateImage(context)) },
  { method: "POST", path: "/v1/images/upload-character-portrait", auth: true, handler: async context => json(await uploadCharacterPortrait(context)) },
  {
    method: "POST",
    path: "/v1/images/generate-character-portrait",
    auth: true,
    handler: async (context) => {
      const body = await parseJson<{ prompt?: string; preview?: boolean; sourceAvatarUrl?: string }>(context.request);
      return json(await generateCharacterPortrait(
        context, requireString(body.prompt, "prompt", 2_000), body.preview === true,
        optionalString(body.sourceAvatarUrl, "sourceAvatarUrl", 2_000)
      ));
    }
  },
  {
    method: "POST",
    path: "/v1/images/generate-chat-background",
    auth: true,
    handler: async (context) => {
      const body = await parseJson<{ prompt?: string; requestKey?: string }>(context.request);
      return json(await generateChatBackground(
        context,
        requireString(body.prompt, "prompt", 2_000),
        optionalString(body.requestKey, "requestKey", 200)
      ));
    }
  }
];
