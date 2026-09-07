import type { Env } from "../env";

const ready = new WeakMap<D1Database, Promise<void>>();
const statements = [
  `CREATE TABLE IF NOT EXISTS notification_settings (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  push_enabled INTEGER NOT NULL DEFAULT 1,
  email_enabled INTEGER NOT NULL DEFAULT 1,
  chat_messages_enabled INTEGER NOT NULL DEFAULT 1,
  followers_enabled INTEGER NOT NULL DEFAULT 1,
  character_updates_enabled INTEGER NOT NULL DEFAULT 1
)`,
  `CREATE TABLE IF NOT EXISTS user_presence (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  last_seen_at INTEGER NOT NULL,
  conversation_id TEXT
)`,
  `CREATE TABLE IF NOT EXISTS notifications (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  character_id TEXT,
  conversation_id TEXT,
  actor_user_id TEXT,
  avatar_url TEXT,
  event_count INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  read_at INTEGER,
  dismissed_at INTEGER,
  emailed_at INTEGER,
  email_attempted_at INTEGER,
  dedup_key TEXT NOT NULL UNIQUE
)`,
  `CREATE INDEX IF NOT EXISTS idx_notifications_owner ON notifications(user_id, updated_at DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_notifications_email ON notifications(emailed_at, created_at)`,
  `CREATE TABLE IF NOT EXISTS offline_deliveries (
  conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  anchor_message_id TEXT NOT NULL,
  stage INTEGER NOT NULL,
  state TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL,
  message_id TEXT,
  PRIMARY KEY(conversation_id, anchor_message_id, stage)
)`,
  `CREATE TABLE IF NOT EXISTS user_follows (
  follower_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  followed_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(follower_user_id, followed_user_id)
)`,
  `CREATE INDEX IF NOT EXISTS idx_follows_creator ON user_follows(followed_user_id, created_at DESC)`,
  `CREATE TABLE IF NOT EXISTS follow_notification_receipts (
  follower_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  followed_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day INTEGER NOT NULL,
  PRIMARY KEY(follower_user_id, followed_user_id, day)
)`
];

export function ensureNotificationSchema(env: Env): Promise<void> {
  const existing = ready.get(env.DB);
  if (existing) return existing;
  const pending = env.DB.batch(statements.map(sql => env.DB.prepare(sql))).then(() => undefined).catch(error => {
    ready.delete(env.DB);
    throw error;
  });
  ready.set(env.DB, pending);
  return pending;
}
