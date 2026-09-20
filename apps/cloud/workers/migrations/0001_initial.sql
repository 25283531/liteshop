CREATE TABLE IF NOT EXISTS cloud_event (
  event_id TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  sequence INTEGER NOT NULL,
  schema_version INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  payload TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  received_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS event_sequence ON cloud_event(shop_id, device_id, sequence);
CREATE TABLE IF NOT EXISTS entity_owner (
  entity_id TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL,
  device_id TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS member_projection (
  member_id TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL,
  payload TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS card_projection (
  card_id TEXT PRIMARY KEY,
  member_id TEXT NOT NULL,
  shop_id TEXT NOT NULL,
  payload TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS points_projection (
  member_id TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL,
  payload TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS terminal_registry (
  device_id TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  last_seen INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS request_nonce (
  nonce TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL
);
