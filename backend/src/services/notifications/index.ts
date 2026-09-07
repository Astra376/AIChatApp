import type { Env, RequestContext } from "../../env";
import { ensureNotificationSchema } from "../../db/ensureNotificationSchema";
import { AppError } from "../../lib/errors";

export interface NotificationSettings {
  pushEnabled: boolean;
  emailEnabled: boolean;
  chatMessagesEnabled: boolean;
  followersEnabled: boolean;
  characterUpdatesEnabled: boolean;
}
export const DEFAULT_NOTIFICATION_SETTINGS: NotificationSettings = {
  pushEnabled: true, emailEnabled: true, chatMessagesEnabled: true,
  followersEnabled: true, characterUpdatesEnabled: true
};
const settingColumns: Record<keyof NotificationSettings, string> = {
  pushEnabled: "push_enabled", emailEnabled: "email_enabled", chatMessagesEnabled: "chat_messages_enabled",
  followersEnabled: "followers_enabled", characterUpdatesEnabled: "character_updates_enabled"
};

export async function getNotificationSettings(env: Env, userId: string): Promise<NotificationSettings> {
  await ensureNotificationSchema(env);
  const row = await env.DB.prepare("SELECT * FROM notification_settings WHERE user_id = ?").bind(userId).first<Record<string, number>>();
  return Object.fromEntries(Object.entries(settingColumns).map(([key, column]) => [key, row ? row[column] !== 0 : true])) as unknown as NotificationSettings;
}
export async function updateNotificationSettings(context: RequestContext, changes: Partial<NotificationSettings>) {
  await ensureNotificationSchema(context.env);
  if (!changes || typeof changes !== "object" || Array.isArray(changes)) throw new AppError(400, "INVALID_SETTINGS", "Invalid notification settings.");
  const entries = Object.entries(changes);
  if (entries.some(([key, value]) => !(key in settingColumns) || typeof value !== "boolean")) {
    throw new AppError(400, "INVALID_SETTINGS", "Notification settings must be true or false.");
  }
  if (entries.length) {
    const columns = entries.map(([key]) => settingColumns[key as keyof NotificationSettings]);
    await context.env.DB.prepare(`INSERT INTO notification_settings (user_id, ${columns.join(",")})
      VALUES (?, ${columns.map(() => "?").join(",")}) ON CONFLICT(user_id) DO UPDATE SET
      ${columns.map(column => `${column} = excluded.${column}`).join(",")}`)
      .bind(context.user!.userId, ...entries.map(([, value]) => value ? 1 : 0)).run();
  }
  return getNotificationSettings(context.env, context.user!.userId);
}

export async function updatePresence(context: RequestContext, conversationId: string | null) {
  await ensureNotificationSchema(context.env);
  const userId = context.user!.userId;
  if (conversationId && !await context.env.DB.prepare("SELECT id FROM conversations WHERE id = ? AND owner_user_id = ?")
    .bind(conversationId, userId).first()) throw new AppError(404, "CONVERSATION_NOT_FOUND", "Conversation not found.");
  const now = Date.now();
  const statements = [context.env.DB.prepare(`INSERT INTO user_presence (user_id, last_seen_at, conversation_id)
    VALUES (?, ?, ?) ON CONFLICT(user_id) DO UPDATE SET last_seen_at = excluded.last_seen_at, conversation_id = excluded.conversation_id`)
    .bind(userId, now, conversationId)];
  if (conversationId) {
    statements.push(context.env.DB.prepare("UPDATE notifications SET read_at = COALESCE(read_at, ?) WHERE user_id = ? AND conversation_id = ?")
      .bind(now, userId, conversationId));
    statements.push(context.env.DB.prepare("UPDATE conversations SET unread_count = 0, has_unread_badge = 0 WHERE id = ? AND owner_user_id = ?")
      .bind(conversationId, userId));
  }
  await context.env.DB.batch(statements);
}

interface NotificationRecord {
  id: string; kind: string; title: string; body: string; character_id: string | null;
  conversation_id: string | null; actor_user_id: string | null; avatar_url: string | null;
  event_count: number; created_at: number; updated_at: number; read_at: number | null;
}
export async function listNotifications(context: RequestContext, cursor: number, limit: number, since: number | null = null) {
  await ensureNotificationSchema(context.env);
  const result = await context.env.DB.prepare(`SELECT * FROM notifications WHERE user_id = ? AND dismissed_at IS NULL
    ${since !== null ? "AND updated_at > ? AND read_at IS NULL" : ""} ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?`)
    .bind(context.user!.userId, ...(since !== null ? [since] : []), limit, cursor).all<NotificationRecord>();
  return { items: (result.results ?? []).map(row => ({
    id: row.id, kind: row.kind, title: row.title, body: row.body, characterId: row.character_id,
    conversationId: row.conversation_id, actorUserId: row.actor_user_id, avatarUrl: row.avatar_url,
    count: row.event_count, createdAt: row.created_at, updatedAt: row.updated_at, read: row.read_at !== null
  })), nextCursor: result.results?.length === limit ? String(cursor + limit) : null };
}
export async function dismissNotifications(context: RequestContext, notificationId?: string) {
  await ensureNotificationSchema(context.env);
  await context.env.DB.prepare(`UPDATE notifications SET dismissed_at = ?, read_at = COALESCE(read_at, ?)
    WHERE user_id = ? ${notificationId ? "AND id = ?" : ""}`)
    .bind(Date.now(), Date.now(), context.user!.userId, ...(notificationId ? [notificationId] : [])).run();
}
export async function markNotificationRead(context: RequestContext, notificationId: string) {
  await ensureNotificationSchema(context.env);
  await context.env.DB.prepare("UPDATE notifications SET read_at = COALESCE(read_at, ?) WHERE user_id = ? AND id = ?")
    .bind(Date.now(), context.user!.userId, notificationId).run();
}

export async function getFollowState(context: RequestContext, userId: string) {
  await ensureNotificationSchema(context.env);
  const row = await context.env.DB.prepare(`SELECT COUNT(*) AS count,
    COALESCE(MAX(CASE WHEN follower_user_id = ? THEN 1 ELSE 0 END), 0) AS following,
    (SELECT COUNT(*) FROM user_follows WHERE follower_user_id = ?) AS following_count
    FROM user_follows WHERE followed_user_id = ?`).bind(context.user!.userId, userId, userId)
    .first<{ count: number; following: number; following_count: number }>();
  return { following: !!row?.following, followerCount: row?.count ?? 0, followingCount: row?.following_count ?? 0 };
}
export async function setFollow(context: RequestContext, userId: string, following: boolean) {
  await ensureNotificationSchema(context.env);
  const me = context.user!.userId;
  if (userId === me) throw new AppError(400, "INVALID_FOLLOW", "You cannot follow yourself.");
  const target = await context.env.DB.prepare("SELECT id FROM users WHERE id = ?").bind(userId).first();
  if (!target) throw new AppError(404, "PROFILE_NOT_FOUND", "Profile not found.");
  if (!following) {
    await context.env.DB.prepare("DELETE FROM user_follows WHERE follower_user_id = ? AND followed_user_id = ?").bind(me, userId).run();
    return getFollowState(context, userId);
  }
  const now = Date.now();
  const result = await context.env.DB.prepare("INSERT OR IGNORE INTO user_follows (follower_user_id, followed_user_id, created_at) VALUES (?, ?, ?)")
    .bind(me, userId, now).run();
  if (result.meta.changes && (await getNotificationSettings(context.env, userId)).followersEnabled) {
    const receipt = await context.env.DB.prepare("INSERT OR IGNORE INTO follow_notification_receipts (follower_user_id, followed_user_id, day) VALUES (?, ?, ?)")
      .bind(me, userId, Math.floor(now / 86_400_000)).run();
    if (!receipt.meta.changes) return getFollowState(context, userId);
    const actor = await context.env.DB.prepare("SELECT display_name, avatar_url FROM profiles WHERE user_id = ?")
      .bind(me).first<{display_name: string; avatar_url: string | null}>();
    const recent = await context.env.DB.prepare("SELECT COUNT(*) AS count FROM user_follows WHERE followed_user_id = ? AND created_at >= ?")
      .bind(userId, now - 3_600_000).first<{count: number}>();
    const count = recent?.count ?? 1;
    // First two followers are individual. A single rolling hourly item absorbs a burst.
    const grouped = count > 2;
    const key = grouped ? `followers:${userId}:${Math.floor(now / 3_600_000)}` : `follow:${userId}:${me}:${Math.floor(now / 86_400_000)}`;
    const title = grouped ? "New followers" : `${actor?.display_name ?? "Someone"} followed you`;
    const body = grouped ? `${count - 2} more people started following you.` : "Tap to view their profile.";
    await context.env.DB.prepare(`INSERT INTO notifications
      (id, user_id, kind, title, body, actor_user_id, avatar_url, event_count, created_at, updated_at, dedup_key)
      VALUES (?, ?, 'follower', ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(dedup_key) DO UPDATE SET body = excluded.body, event_count = notifications.event_count + 1,
        actor_user_id = excluded.actor_user_id, updated_at = excluded.updated_at, read_at = NULL, dismissed_at = NULL`)
      .bind(key, userId, title, body, me, actor?.avatar_url ?? null, grouped ? count - 2 : 1, now, now, key).run();
  }
  return getFollowState(context, userId);
}

export async function notifyCharacterPublished(env: Env, characterId: string): Promise<void> {
  await ensureNotificationSchema(env);
  const now = Date.now();
  // Followed creators are a direct interest signal. One event per character/user, even on republishing.
  await env.DB.prepare(`INSERT OR IGNORE INTO notifications
    (id, user_id, kind, title, body, character_id, actor_user_id, avatar_url, created_at, updated_at, dedup_key)
    SELECT 'published:' || c.id || ':' || f.follower_user_id, f.follower_user_id, 'character',
      c.name || ' is ready to chat', c.tagline, c.id, c.owner_user_id, c.avatar_url, ?, ?,
      'published:' || c.id || ':' || f.follower_user_id
    FROM characters c JOIN user_follows f ON f.followed_user_id = c.owner_user_id
    LEFT JOIN notification_settings s ON s.user_id = f.follower_user_id
    WHERE c.id = ? AND c.visibility = 'public' AND COALESCE(s.character_updates_enabled, 1) = 1
      AND (SELECT COUNT(*) FROM notifications n WHERE n.user_id = f.follower_user_id AND n.kind = 'character' AND n.created_at > ?) < 2`)
    .bind(now, now, characterId, now - 86_400_000).run();
}

export async function processRecommendations(env: Env, now = Date.now()): Promise<void> {
  await ensureNotificationSchema(env);
  // Only recommend new work by creators the user followed or previously chatted with, at most once a week.
  await env.DB.prepare(`INSERT OR IGNORE INTO notifications
    (id, user_id, kind, title, body, character_id, avatar_url, created_at, updated_at, dedup_key)
    SELECT 'recommend:' || p.user_id || ':' || c.id, p.user_id, 'recommendation', c.name || ' might be your next favorite',
      c.tagline, c.id, c.avatar_url, ?, ?, 'recommend:' || p.user_id || ':' || c.id
    FROM user_presence p JOIN characters c ON c.id = (
      SELECT candidate.id FROM characters candidate WHERE candidate.visibility = 'public' AND candidate.created_at > p.last_seen_at
        AND candidate.owner_user_id != p.user_id
        AND (EXISTS (SELECT 1 FROM user_follows f WHERE f.follower_user_id = p.user_id AND f.followed_user_id = candidate.owner_user_id)
          OR EXISTS (SELECT 1 FROM conversations cv JOIN characters old ON old.id = cv.character_id WHERE cv.owner_user_id = p.user_id AND old.owner_user_id = candidate.owner_user_id))
        AND NOT EXISTS (SELECT 1 FROM notifications prior WHERE prior.user_id = p.user_id AND prior.character_id = candidate.id)
      ORDER BY candidate.public_chat_count DESC, candidate.created_at DESC LIMIT 1)
    LEFT JOIN notification_settings s ON s.user_id = p.user_id
    WHERE p.last_seen_at < ? AND COALESCE(s.character_updates_enabled, 1) = 1
      AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.user_id = p.user_id AND n.kind = 'recommendation' AND n.created_at > ?)
    LIMIT 25`).bind(now, now, now - 3 * 86_400_000, now - 7 * 86_400_000).run();
}
