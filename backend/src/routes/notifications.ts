import { json } from "../lib/response";
import { clampPageSize, parseCursor, parseJson } from "../lib/validation";
import { AppError } from "../lib/errors";
import type { RouteDefinition } from "./types";
import { dismissNotifications, getFollowState, getNotificationSettings, listNotifications, markNotificationRead,
  setFollow, updateNotificationSettings, updatePresence, type NotificationSettings } from "../services/notifications";
import { unsubscribeEmail } from "../services/notifications/email";

export const notificationRoutes: RouteDefinition[] = [
  {method: "GET", path: "/v1/notifications/unsubscribe", handler: async context => {
    if (!await unsubscribeEmail(context.env, context.url.searchParams.get("t") ?? "")) throw new AppError(400, "INVALID_LINK", "This unsubscribe link is invalid.");
    return new Response("Email notifications are now turned off. You can turn them on again in Meek Settings.", {headers: {"Content-Type": "text/plain; charset=utf-8"}});
  }},
  {method: "POST", path: "/v1/notifications/unsubscribe", handler: async context => {
    if (!await unsubscribeEmail(context.env, context.url.searchParams.get("t") ?? "")) throw new AppError(400, "INVALID_LINK", "This unsubscribe link is invalid.");
    return json({ok: true});
  }},
  {method: "GET", path: "/v1/notifications", auth: true, handler: async context => {
    const rawSince = context.url.searchParams.get("since");
    const since = rawSince === null ? null : Number(rawSince);
    if (since !== null && (!Number.isFinite(since) || since < 0)) throw new AppError(400, "INVALID_CURSOR", "Invalid notification timestamp.");
    return json(await listNotifications(context, parseCursor(context.url.searchParams.get("cursor")), clampPageSize(context.url.searchParams.get("pageSize")), since));
  }},
  {method: "DELETE", path: "/v1/notifications", auth: true, handler: async context => { await dismissNotifications(context); return json({ok: true}); }},
  {method: "DELETE", path: "/v1/notifications/:id", auth: true, handler: async context => { await dismissNotifications(context, context.params.id); return json({ok: true}); }},
  {method: "POST", path: "/v1/notifications/:id/read", auth: true, handler: async context => { await markNotificationRead(context, context.params.id); return json({ok: true}); }},
  {method: "GET", path: "/v1/notification-settings", auth: true, handler: async context => json(await getNotificationSettings(context.env, context.user!.userId))},
  {method: "PATCH", path: "/v1/notification-settings", auth: true, handler: async context => json(await updateNotificationSettings(context, await parseJson<Partial<NotificationSettings>>(context.request)))},
  {method: "POST", path: "/v1/presence", auth: true, handler: async context => {
    const body = await parseJson<{conversationId?: unknown}>(context.request);
    if (body.conversationId !== null && body.conversationId !== undefined && (typeof body.conversationId !== "string" || body.conversationId.length > 200)) throw new AppError(400, "INVALID_CONVERSATION", "Invalid conversation.");
    await updatePresence(context, body.conversationId as string | null ?? null); return json({ok: true});
  }},
  {method: "GET", path: "/v1/profiles/:userId/follow", auth: true, handler: async context => json(await getFollowState(context, context.params.userId))},
  {method: "POST", path: "/v1/profiles/:userId/follow", auth: true, handler: async context => json(await setFollow(context, context.params.userId, true))},
  {method: "DELETE", path: "/v1/profiles/:userId/follow", auth: true, handler: async context => json(await setFollow(context, context.params.userId, false))}
];
