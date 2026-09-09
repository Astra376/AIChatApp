CREATE TABLE IF NOT EXISTS group_personas (
  group_id TEXT PRIMARY KEY REFERENCES chat_groups(id) ON DELETE CASCADE,
  mode TEXT NOT NULL CHECK(mode IN ('auto','account','personal')),
  persona_id TEXT REFERENCES user_personas(id) ON DELETE SET NULL
);
