import { createConversation, getConversationDetail, listConversations, markConversationRead } from "../services/conversations";
import { getCharacterMemory, updateCharacterMemory } from "../services/chat/memory";
import { json } from "../lib/response";
import { clampPageSize, parseCursor, parseJson, requireString } from "../lib/validation";
import type { RouteDefinition } from "./types";

export const conversationRoutes: RouteDefinition[] = [
  {
    method: "GET",
    path: "/v1/conversations",
    auth: true,
    handler: async (context) => {
      const page = await listConversations(
        context,
        parseCursor(context.url.searchParams.get("cursor")),
        clampPageSize(context.url.searchParams.get("pageSize"), 20)
      );
      return json(page);
    }
  },
  {
    method: "POST",
    path: "/v1/conversations",
    auth: true,
    handler: async (context) => {
      const body = await parseJson<{ characterId?: string; forceNew?: boolean }>(context.request);
      const conversation = await createConversation(
        context,
        requireString(body.characterId, "characterId"),
        body.forceNew === true
      );
      return json(conversation, { status: 201 });
    }
  },
  {
    method: "GET",
    path: "/v1/conversations/:conversationId",
    auth: true,
    handler: async (context) => json(await getConversationDetail(context, context.params.conversationId))
  },
  {
    method: "GET",
    path: "/v1/conversations/:conversationId/memory",
    auth: true,
    handler: async (context) => json(await getCharacterMemory(context, context.params.conversationId))
  },
  {
    method: "PATCH",
    path: "/v1/conversations/:conversationId/memory",
    auth: true,
    handler: async (context) => {
      const body = await parseJson<import("../services/chat/memory").MemoryEdit>(context.request);
      return json(await updateCharacterMemory(context, context.params.conversationId, body));
    }
  },
  {
    method: "POST",
    path: "/v1/conversations/:conversationId/read",
    auth: true,
    handler: async (context) => {
      await markConversationRead(context, context.params.conversationId);
      return json({}, { status: 200 });
    }
  }
];
