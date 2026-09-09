CREATE TABLE IF NOT EXISTS chat_groups (
  id TEXT PRIMARY KEY,
  owner_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  version INTEGER NOT NULL DEFAULT 0,
  user_turn INTEGER NOT NULL DEFAULT 0,
  active_run_id TEXT,
  active_run_expires_at INTEGER,
  last_user_message_id TEXT,
  last_user_at INTEGER,
  last_seen_at INTEGER NOT NULL DEFAULT 0,
  typing_started_at INTEGER,
  typing_at INTEGER,
  last_autonomy_at INTEGER NOT NULL DEFAULT 0,
  last_autonomy_anchor_id TEXT,
  unread_count INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS group_members (
  group_id TEXT NOT NULL REFERENCES chat_groups(id) ON DELETE CASCADE,
  character_id TEXT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  PRIMARY KEY(group_id, character_id)
);
CREATE TABLE IF NOT EXISTS group_messages (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL REFERENCES chat_groups(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  role TEXT NOT NULL CHECK(role IN ('user','assistant')),
  character_id TEXT,
  content TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  run_id TEXT,
  status TEXT NOT NULL DEFAULT 'complete' CHECK(status IN ('streaming','complete','interrupted')),
  UNIQUE(group_id, position)
);
CREATE INDEX IF NOT EXISTS idx_groups_owner_updated ON chat_groups(owner_user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_groups_autonomy ON chat_groups(last_autonomy_at, last_seen_at);
CREATE INDEX IF NOT EXISTS idx_group_messages_position ON group_messages(group_id, position DESC);
CREATE INDEX IF NOT EXISTS idx_group_messages_run ON group_messages(group_id, run_id);

CREATE TABLE IF NOT EXISTS group_memories (
  group_id TEXT PRIMARY KEY REFERENCES chat_groups(id) ON DELETE CASCADE,
  memory_json TEXT NOT NULL DEFAULT '{}',
  last_user_turn INTEGER NOT NULL DEFAULT 0,
  active_run_id TEXT,
  updated_at INTEGER NOT NULL DEFAULT 0
);
