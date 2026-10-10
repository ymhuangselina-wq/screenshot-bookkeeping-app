CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  feishu_open_id TEXT NOT NULL UNIQUE,
  tenant_key TEXT NOT NULL,
  name TEXT NOT NULL DEFAULT '',
  avatar_url TEXT,
  status TEXT NOT NULL DEFAULT 'active',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS oauth_credentials (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  access_token_cipher TEXT NOT NULL,
  refresh_token_cipher TEXT NOT NULL,
  access_expires_at INTEGER NOT NULL,
  refresh_expires_at INTEGER,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  device_name TEXT,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS invite_codes (
  id TEXT PRIMARY KEY,
  code_hash TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL DEFAULT 'active',
  bound_user_id TEXT REFERENCES users(id),
  expires_at INTEGER,
  created_at INTEGER NOT NULL,
  used_at INTEGER
);

CREATE TABLE IF NOT EXISTS books (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  app_token TEXT NOT NULL,
  table_id TEXT NOT NULL,
  name TEXT NOT NULL,
  table_name TEXT NOT NULL,
  source_url TEXT,
  is_current INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS books_one_current_per_user
ON books(user_id) WHERE is_current = 1;

CREATE TABLE IF NOT EXISTS field_mappings (
  book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
  semantic_key TEXT NOT NULL,
  field_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  field_type INTEGER NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  options_json TEXT NOT NULL DEFAULT '[]',
  PRIMARY KEY (book_id, semantic_key)
);

CREATE TABLE IF NOT EXISTS daily_usage (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  usage_date TEXT NOT NULL,
  used_count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, usage_date)
);

CREATE INDEX IF NOT EXISTS sessions_by_user ON sessions(user_id);
CREATE INDEX IF NOT EXISTS books_by_user ON books(user_id);
