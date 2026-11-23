PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS plans (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  total_gb REAL NOT NULL DEFAULT 0,
  days INTEGER NOT NULL DEFAULT 0,
  price_toman INTEGER NOT NULL DEFAULT 0,
  is_active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS admins (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'admin' CHECK (role IN ('superadmin', 'admin')),
  is_active INTEGER NOT NULL DEFAULT 1,
  token_version INTEGER NOT NULL DEFAULT 1,
  last_login_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS users (
  uid TEXT PRIMARY KEY,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  display_name TEXT NOT NULL DEFAULT '',
  password_hash TEXT NOT NULL,
  subscription_token TEXT NOT NULL UNIQUE,
  is_enabled INTEGER NOT NULL DEFAULT 1,
  total_bytes INTEGER NOT NULL DEFAULT 0,
  used_bytes INTEGER NOT NULL DEFAULT 0,
  expires_at TEXT,
  plan_id INTEGER REFERENCES plans(id) ON DELETE SET NULL,
  plan_title TEXT NOT NULL DEFAULT '',
  note TEXT NOT NULL DEFAULT '',
  vless_uuid TEXT NOT NULL,
  vmess_uuid TEXT NOT NULL,
  trojan_password TEXT NOT NULL,
  ss_password TEXT NOT NULL,
  hysteria_password TEXT NOT NULL,
  xray_provisioned INTEGER NOT NULL DEFAULT 0,
  online_xray INTEGER NOT NULL DEFAULT 0,
  online_hysteria INTEGER NOT NULL DEFAULT 0,
  last_seen_at TEXT,
  last_ip TEXT,
  token_version INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_sub_token ON users(subscription_token);
CREATE INDEX IF NOT EXISTS idx_users_enabled ON users(is_enabled);

CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  admin_id INTEGER,
  admin_username TEXT,
  action TEXT NOT NULL,
  target_type TEXT,
  target_id TEXT,
  details TEXT,
  ip TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at);

CREATE TABLE IF NOT EXISTS servers (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  host TEXT NOT NULL,
  domain TEXT NOT NULL,
  location TEXT NOT NULL DEFAULT '',
  flag TEXT NOT NULL DEFAULT '',
  is_active INTEGER NOT NULL DEFAULT 1,
  api_base TEXT NOT NULL DEFAULT '',
  protocols TEXT NOT NULL DEFAULT '[]',
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- Usage measured by a second box, one row per measurement round. The composite key is what makes a
-- replayed report a no-op instead of a double charge.
CREATE TABLE IF NOT EXISTS usage_windows (
  box TEXT NOT NULL,
  window_id TEXT NOT NULL,
  bytes INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  PRIMARY KEY (box, window_id)
);

-- Open operator registry & Discovery (docs/design/0001)
CREATE TABLE IF NOT EXISTS operators (
  operator_key TEXT PRIMARY KEY,
  operator_tag TEXT NOT NULL,
  display_name TEXT NOT NULL DEFAULT '',
  network_type TEXT NOT NULL DEFAULT 'unknown',
  evidence TEXT NOT NULL DEFAULT '{}',
  status TEXT NOT NULL DEFAULT 'provisional',
  merged_into TEXT,
  aliases TEXT NOT NULL DEFAULT '[]',
  mismatch INTEGER NOT NULL DEFAULT 0,
  source TEXT NOT NULL DEFAULT 'client',
  first_seen TEXT NOT NULL,
  last_seen TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS operator_observations (
  operator_key TEXT NOT NULL,
  uid TEXT NOT NULL,
  network_key TEXT NOT NULL DEFAULT '',
  source TEXT NOT NULL DEFAULT 'client',
  created_at TEXT NOT NULL,
  PRIMARY KEY (operator_key, uid)
);

CREATE TABLE IF NOT EXISTS discovery_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uid TEXT NOT NULL,
  kind TEXT NOT NULL,
  key TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS preset_configs (
  config_id TEXT PRIMARY KEY,
  engine TEXT NOT NULL,
  transport TEXT NOT NULL,
  params TEXT NOT NULL,
  network_type TEXT NOT NULL DEFAULT 'any',
  is_seed INTEGER NOT NULL DEFAULT 0,
  is_alive INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  last_seen TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS preset_validations (
  operator_key TEXT NOT NULL,
  network_key TEXT NOT NULL,
  config_id TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'candidate',
  samples INTEGER NOT NULL DEFAULT 0,
  clients INTEGER NOT NULL DEFAULT 0,
  p50_latency_ms REAL,
  p50_throughput_kBps REAL,
  death_rate REAL NOT NULL DEFAULT 0,
  confidence REAL NOT NULL DEFAULT 0,
  server_corroborated INTEGER NOT NULL DEFAULT 0,
  source TEXT NOT NULL DEFAULT 'crowd',
  first_seen TEXT NOT NULL,
  last_seen TEXT NOT NULL,
  PRIMARY KEY (operator_key, network_key, config_id)
);

CREATE TABLE IF NOT EXISTS preset_samples (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  operator_key TEXT NOT NULL,
  network_key TEXT NOT NULL,
  config_id TEXT NOT NULL,
  uid TEXT NOT NULL,
  latency_ms REAL,
  throughput_kBps REAL,
  bytes INTEGER NOT NULL DEFAULT 0,
  duration_s REAL NOT NULL DEFAULT 0,
  verdict TEXT NOT NULL,
  connected_minutes REAL NOT NULL DEFAULT 0,
  deaths INTEGER NOT NULL DEFAULT 0,
  probe_method TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_samples_key ON preset_samples (operator_key, network_key, config_id, created_at);
CREATE INDEX IF NOT EXISTS idx_validations_network ON preset_validations (network_key, status);
CREATE INDEX IF NOT EXISTS idx_discovery_log_uid ON discovery_log (uid, kind, created_at);

CREATE TABLE IF NOT EXISTS preset_settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS domestic_rules (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL,
  value TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'admin',
  created_at TEXT NOT NULL,
  UNIQUE (kind, value)
);

CREATE TABLE IF NOT EXISTS admin_audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  target TEXT NOT NULL,
  detail TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL
);

