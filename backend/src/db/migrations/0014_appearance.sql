CREATE TABLE IF NOT EXISTS user_appearance(user_id TEXT PRIMARY KEY, settings TEXT NOT NULL DEFAULT '{}', updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS appearance_assets(id TEXT PRIMARY KEY, user_id TEXT NOT NULL, kind TEXT NOT NULL, asset_key TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS appearance_assets_owner ON appearance_assets(user_id,created_at);
CREATE TABLE IF NOT EXISTS appearance_jobs(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,started_at INTEGER NOT NULL,fingerprint TEXT NOT NULL);
