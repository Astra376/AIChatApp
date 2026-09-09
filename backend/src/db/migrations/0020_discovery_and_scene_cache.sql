CREATE TABLE IF NOT EXISTS discovery_snapshots (
  user_id TEXT NOT NULL,
  version TEXT NOT NULL,
  snapshot_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, version),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_discovery_user_created ON discovery_snapshots(user_id, created_at DESC);
CREATE TABLE IF NOT EXISTS discovery_embeddings (
  character_id TEXT PRIMARY KEY,
  model TEXT NOT NULL,
  source_text TEXT NOT NULL DEFAULT '',
  vector_json TEXT NOT NULL DEFAULT '[]',
  lease_until INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS chat_scene_backgrounds (
  conversation_id TEXT PRIMARY KEY,
  fingerprint TEXT NOT NULL DEFAULT '',
  scene_json TEXT NOT NULL DEFAULT '{}',
  known_json TEXT NOT NULL DEFAULT '[]',
  image_url TEXT NOT NULL DEFAULT '',
  scene_key TEXT NOT NULL DEFAULT '',
  lease_until INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);
