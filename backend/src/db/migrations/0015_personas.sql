CREATE TABLE IF NOT EXISTS user_personas (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  backstory TEXT NOT NULL DEFAULT '',
  appearance TEXT NOT NULL DEFAULT '',
  pronouns TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS user_personas_owner ON user_personas(user_id, updated_at DESC);
CREATE TABLE IF NOT EXISTS user_persona_preferences (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  persona_id TEXT REFERENCES user_personas(id) ON DELETE SET NULL
);
CREATE TABLE IF NOT EXISTS character_user_personas (
  character_id TEXT PRIMARY KEY REFERENCES characters(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  backstory TEXT NOT NULL DEFAULT '',
  appearance TEXT NOT NULL DEFAULT '',
  pronouns TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS conversation_personas (
  conversation_id TEXT PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
  mode TEXT NOT NULL CHECK(mode IN ('auto','account','personal')),
  persona_id TEXT REFERENCES user_personas(id) ON DELETE SET NULL
);
