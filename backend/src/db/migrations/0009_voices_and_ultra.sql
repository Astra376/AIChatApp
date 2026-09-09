CREATE TABLE IF NOT EXISTS subscriptions (
  subscription_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, customer_id TEXT NOT NULL,
  status TEXT NOT NULL, price_id TEXT NOT NULL, expires_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS subscriptions_user ON subscriptions(user_id);
CREATE TABLE IF NOT EXISTS voices (
  id TEXT PRIMARY KEY, owner_user_id TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL,
  visibility TEXT NOT NULL DEFAULT 'private', embedding_key TEXT NOT NULL,
  preview_key TEXT, created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS voices_owner ON voices(owner_user_id);
CREATE TABLE IF NOT EXISTS character_voices (character_id TEXT PRIMARY KEY, voice_id TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS voice_jobs (
  job_key TEXT PRIMARY KEY, user_id TEXT NOT NULL, started_at INTEGER NOT NULL
);
