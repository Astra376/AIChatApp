import { json } from "../lib/response";
import { assert } from "../lib/errors";
import { clampPageSize, parseCursor, parseJson } from "../lib/validation";
import { continueGroup, sendGroupMessage, stopGroup } from "../services/groups";
import { createGroup, deleteGroup, getGroup, listGroups, updateGroupPresence } from "../services/groups/storage";
import type { RouteDefinition } from "./types";
export const groupRoutes: RouteDefinition[] = [
  {method: "GET", path: "/v1/groups", auth: true, handler: async context => json(await listGroups(context,
    parseCursor(context.url.searchParams.get("cursor")), clampPageSize(context.url.searchParams.get("pageSize"))))},
  {method: "POST", path: "/v1/groups", auth: true, handler: async context => {
    const body = await parseJson<{name?: unknown; characterIds?: unknown}>(context.request);
    assert(typeof body.name === "string" && body.name.trim().length >= 1 && body.name.trim().length <= 80,
      400, "INVALID_GROUP_NAME", "Give this group a name up to 80 characters.");
    return json(await createGroup(context, body.name.trim(), body.characterIds), {status: 201});
  }},
  {method: "GET", path: "/v1/groups/:groupId", auth: true, handler: async context => {
    const raw = context.url.searchParams.get("beforePosition");
    const before = raw === null ? undefined : Number(raw);
    assert(before === undefined || Number.isSafeInteger(before) && before >= 0, 400, "INVALID_CURSOR", "Invalid message position.");
    return json(await getGroup(context, context.params.groupId, before));
  }},
  {method: "DELETE", path: "/v1/groups/:groupId", auth: true, handler: async context => {
    await deleteGroup(context, context.params.groupId); return json({ok: true});
  }},
  {method: "POST", path: "/v1/groups/:groupId/messages/stream", auth: true, handler: async context => {
    const body = await parseJson<{userMessageId?: unknown; content?: unknown}>(context.request);
    assert(typeof body.userMessageId === "string" && /^[a-zA-Z0-9_-]{8,160}$/.test(body.userMessageId), 400, "INVALID_MESSAGE_ID", "Invalid message ID.");
    assert(typeof body.content === "string" && body.content.trim().length > 0 && body.content.length <= 12_000,
      400, "INVALID_MESSAGE", "Write a message up to 12,000 characters.");
    return sendGroupMessage(context, context.params.groupId, body.userMessageId, body.content.trim());
  }},
  {method: "POST", path: "/v1/groups/:groupId/continue/stream", auth: true, handler: async context => {
    const body = await parseJson<{reason?: unknown}>(context.request);
    assert(body.reason === "continue" || body.reason === "typing", 400, "INVALID_TRIGGER", "Invalid group continuation.");
    return continueGroup(context, context.params.groupId, body.reason);
  }},
  {method: "POST", path: "/v1/groups/:groupId/stop", auth: true, handler: async context => {
    const body = await parseJson<{runId?: unknown}>(context.request);
    assert(typeof body.runId === "string" && body.runId.length > 0 && body.runId.length <= 200, 400, "INVALID_RUN", "Invalid reply ID.");
    await stopGroup(context, context.params.groupId, body.runId); return json({ok: true});
  }},
  {method: "POST", path: "/v1/groups/:groupId/presence", auth: true, handler: async context => {
    const body = await parseJson<{typing?: unknown}>(context.request);
    assert(typeof body.typing === "boolean", 400, "INVALID_PRESENCE", "Invalid typing state.");
    await updateGroupPresence(context, context.params.groupId, body.typing); return json({ok: true});
  }}
];
