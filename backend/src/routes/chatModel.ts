import { json } from "../lib/response";
import { parseJson } from "../lib/validation";
import { getChatModelPreferences, updateChatModelPreferences } from "../services/chat/modelPolicy";
import type { RouteDefinition } from "./types";

export const chatModelRoutes: RouteDefinition[] = [
  {
    method: "GET", path: "/v1/conversations/:conversationId/model", auth: true,
    handler: async context => json(await getChatModelPreferences(context, context.params.conversationId))
  },
  {
    method: "PATCH", path: "/v1/conversations/:conversationId/model", auth: true,
    handler: async context => json(await updateChatModelPreferences(context, context.params.conversationId,
      await parseJson<{ mode?: unknown; chatFont?: unknown }>(context.request)))
  }
];
