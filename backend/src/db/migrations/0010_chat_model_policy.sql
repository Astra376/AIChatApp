CREATE TABLE IF NOT EXISTS chat_model_preferences (
  conversation_id TEXT PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
  mode TEXT NOT NULL DEFAULT 'auto' CHECK (mode IN ('auto', 'standard', 'ultra')),
  chat_font TEXT NOT NULL DEFAULT 'default' CHECK (chat_font IN ('default', 'sans', 'serif', 'mono', 'rounded')),
  updated_at INTEGER NOT NULL
);

-- A single conditional INSERT reserves both daily allowance and turn spacing.
-- Group and direct chats share a user's allowance. Keep reservations when a
-- conversation is deleted so deleting/recreating chats cannot reset the quota.
CREATE TABLE IF NOT EXISTS chat_reasoning_reservations (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  scope_id TEXT NOT NULL,
  user_turn INTEGER NOT NULL,
  usage_day TEXT NOT NULL,
  model_tier TEXT NOT NULL CHECK (model_tier IN ('standard', 'ultra')),
  reserved_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, scope_id, user_turn)
);
CREATE INDEX IF NOT EXISTS chat_reasoning_daily_usage
  ON chat_reasoning_reservations(user_id, usage_day, model_tier);
