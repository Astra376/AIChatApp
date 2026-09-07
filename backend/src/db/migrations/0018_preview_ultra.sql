-- Only grants access while ULTRA_PREVIEW_ENABLED=true and live billing is unconfigured.
CREATE TABLE IF NOT EXISTS preview_ultra (
  user_id TEXT PRIMARY KEY,
  cadence TEXT NOT NULL,
  enabled INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
