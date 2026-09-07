import type { Env } from "../../env";
import { ensureNotificationSchema } from "../../db/ensureNotificationSchema";

function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
async function signature(env: Env, value: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.SESSION_HMAC_SECRET), {name: "HMAC", hash: "SHA-256"}, false, ["sign"]);
  return base64url(new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value))));
}
export async function unsubscribeToken(env: Env, userId: string): Promise<string> {
  const payload = base64url(new TextEncoder().encode(userId));
  return `${payload}.${await signature(env, `email-unsubscribe:${payload}`)}`;
}
export async function unsubscribeEmail(env: Env, token: string): Promise<boolean> {
  if (!env.SESSION_HMAC_SECRET || token.length > 500) return false;
  const [payload, provided, extra] = token.split(".");
  if (!payload || !provided || extra) return false;
  const expected = await signature(env, `email-unsubscribe:${payload}`);
  if (provided.length !== expected.length) return false;
  let difference = 0;
  for (let i = 0; i < expected.length; i++) difference |= provided.charCodeAt(i) ^ expected.charCodeAt(i);
  if (difference) return false;
  let userId: string;
  try { userId = new TextDecoder().decode(Uint8Array.from(atob(payload.replace(/-/g, "+").replace(/_/g, "/")), c => c.charCodeAt(0))); }
  catch { return false; }
  await ensureNotificationSchema(env);
  await env.DB.prepare(`INSERT INTO notification_settings (user_id, email_enabled) SELECT id, 0 FROM users WHERE id = ?
    ON CONFLICT(user_id) DO UPDATE SET email_enabled = 0`).bind(userId).run();
  return true;
}

export async function deliverNotificationEmails(env: Env, now = Date.now()): Promise<void> {
  // Missing provider configuration means no delivery, and no false "sent" timestamps.
  if (!env.RESEND_API_KEY?.trim() || !env.NOTIFICATION_EMAIL_FROM?.trim() || !env.SESSION_HMAC_SECRET?.trim()) return;
  await ensureNotificationSchema(env);
  const rows = await env.DB.prepare(`SELECT n.id, n.user_id, n.title, n.body, n.conversation_id, n.character_id, u.email
    FROM notifications n JOIN users u ON u.id = n.user_id
    LEFT JOIN notification_settings s ON s.user_id = n.user_id
    LEFT JOIN user_presence p ON p.user_id = n.user_id
    WHERE n.kind = 'chat' AND n.read_at IS NULL AND n.dismissed_at IS NULL AND n.emailed_at IS NULL
      AND COALESCE(s.email_enabled, 1) = 1 AND COALESCE(s.chat_messages_enabled, 1) = 1
      AND COALESCE(p.last_seen_at, u.last_login_at) < ? AND n.created_at > ?
      AND (n.email_attempted_at IS NULL OR n.email_attempted_at < ?)
      AND COALESCE(u.last_email_sent_at, 0) < ?
      AND n.id = (SELECT newest.id FROM notifications newest WHERE newest.user_id = n.user_id AND newest.kind = 'chat'
        AND newest.read_at IS NULL AND newest.dismissed_at IS NULL ORDER BY newest.created_at DESC LIMIT 1)
    ORDER BY n.created_at DESC LIMIT 10`)
    .bind(now - 3_600_000, now - 86_400_000, now - 3_600_000, now - 3 * 86_400_000)
    .all<{id: string; user_id: string; title: string; body: string; email: string; conversation_id: string | null; character_id: string | null}>();
  for (const item of rows.results ?? []) {
    const claimed = await env.DB.prepare(`UPDATE notifications SET email_attempted_at = ? WHERE id = ? AND emailed_at IS NULL
      AND (email_attempted_at IS NULL OR email_attempted_at < ?)`)
      .bind(now, item.id, now - 3_600_000).run();
    if (!claimed.meta.changes) continue;
    const unsubscribeBase = new URL("/v1/notifications/unsubscribe", env.R2_PUBLIC_BASE_URL).origin;
    const unsubscribeUrl = `${unsubscribeBase}/v1/notifications/unsubscribe?t=${await unsubscribeToken(env, item.user_id)}`;
    const appUrl = env.NOTIFICATION_APP_URL || "meek://activity";
    try {
      const response = await fetch("https://api.resend.com/emails", {
        method: "POST", headers: {
          Authorization: `Bearer ${env.RESEND_API_KEY}`, "Content-Type": "application/json", "Idempotency-Key": `notification/${item.id}`
        },
        body: JSON.stringify({
          from: env.NOTIFICATION_EMAIL_FROM, to: [item.email], subject: item.title,
          text: `${item.body}\n\nOpen Meek: ${appUrl}\n\nTurn off email notifications: ${unsubscribeUrl}`,
          headers: {"List-Unsubscribe": `<${unsubscribeUrl}>`, "List-Unsubscribe-Post": "List-Unsubscribe=One-Click"}
        }), signal: AbortSignal.timeout(10_000)
      });
      if (!response.ok) { await response.body?.cancel(); continue; }
      const result = await response.json() as { id?: string };
      if (!result.id) continue;
      await env.DB.batch([
        env.DB.prepare("UPDATE notifications SET emailed_at = ? WHERE id = ?").bind(now, item.id),
        env.DB.prepare("UPDATE users SET last_email_sent_at = ? WHERE id = ?").bind(now, item.user_id)
      ]);
    } catch {
      // Retry on a later cron; neither private message text nor addresses are logged.
    }
  }
}
