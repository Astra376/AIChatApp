-- Separate verified transparent chat layers from legacy opaque expression portraits.
CREATE TABLE IF NOT EXISTS character_body_art (
  character_id TEXT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
  emotion TEXT NOT NULL, source_url TEXT NOT NULL, attempt_id TEXT NOT NULL,
  image_url TEXT, job_json TEXT, status TEXT NOT NULL, updated_at INTEGER NOT NULL,
  PRIMARY KEY(character_id, emotion)
);
CREATE INDEX IF NOT EXISTS character_body_art_pending ON character_body_art(status, updated_at);
