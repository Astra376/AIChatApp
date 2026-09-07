CREATE INDEX IF NOT EXISTS idx_regenerations_message_created
  ON assistant_regenerations(message_id, created_at);

CREATE INDEX IF NOT EXISTS idx_conversations_owner_character_updated
  ON conversations(owner_user_id, character_id, updated_at DESC, started_at DESC, id DESC);
