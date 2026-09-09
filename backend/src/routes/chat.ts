import {
  cancelAssistantRun,
  continueAssistantAndStream,
  editMessage,
  regenerateLatestAssistantAndStream,
  rewindConversation,
  selectRegeneration,
  sendMessageAndStream
} from "../services/chat";
import { noContent } from "../lib/response";
import { parseJson, requireString, requireUlid } from "../lib/validation";
import type { RouteDefinition } from "./types";

export const chatRoutes: RouteDefinition[] = [
  {
    method: "POST",
    path: "/v1/conversations/:conversationId/stop",
    auth: true,
    handler: async (context) => {
      const body = await parseJson<{ runId?: string; partialReply?: { messageId?: string; text?: string; regenerate?: boolean } }>(context.request);
      const partial = body.partialReply;
      await cancelAssistantRun(context, context.params.conversationId, requireString(body.runId, "runId", 200),
        partial ? {
          messageId: requireString(partial.messageId, "partialReply.messageId", 200),
          text: requireString(partial.text, "partialReply.text", 64_000),
          regenerate: partial.regenerate === true
        } : undefined);
      return noContent();
    }
  },
  {
    method: "POST",
    path: "/v1/conversations/:conversationId/messages/stream",
    auth: true,
    handler: async (context) => {
      const body = await parseJson<{ userMessageId?: string; content?: string }>(context.request);
      return sendMessageAndStream(
        context,
        context.params.conversationId,
        requireUlid(body.userMessageId, "userMessageId"),
        requireString(body.content, "content", 8_000)
      );
    }
  },
  {
    method: "POST",
    path: "/v1/conversations/:conversationId/continue/stream",
    auth: true,
    handler: async (context) => continueAssistantAndStream(context, context.params.conversationId)
  },
  {
    method: "PATCH",
    path: "/v1/messages/:messageId",
    auth: true,
    handler: async (context) => {
      const body = await parseJson<{ content?: string }>(context.request);
      await editMessage(context, context.params.messageId, requireString(body.content, "content", 8_000));
      return noContent();
    }
  },
  {
    method: "POST",
    path: "/v1/messages/:messageId/rewind",
    auth: true,
    handler: async (context) => {
      await rewindConversation(context, context.params.messageId);
      return noContent();
    }
  },
  {
    method: "POST",
    path: "/v1/messages/:messageId/regenerate/stream",
    auth: true,
    handler: async (context) => regenerateLatestAssistantAndStream(context, context.params.messageId)
  },
  {
    method: "POST",
    path: "/v1/messages/:messageId/select-regeneration",
    auth: true,
    handler: async (context) => {
      const body = await parseJson<{ regenerationId?: string | null }>(context.request);
      await selectRegeneration(
        context,
        context.params.messageId,
        typeof body.regenerationId === "string" ? body.regenerationId : null
      );
      return noContent();
    }
  }
];
