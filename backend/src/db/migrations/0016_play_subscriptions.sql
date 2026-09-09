-- Verified Google Play receipts. No products or external payment services are provisioned.
CREATE TABLE IF NOT EXISTS play_subscriptions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  purchase_token TEXT NOT NULL,
  product_id TEXT NOT NULL,
  status TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  verified_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS play_subscriptions_user ON play_subscriptions(user_id);
