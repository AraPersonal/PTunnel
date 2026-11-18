import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

export function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(password, salt, 64).toString('hex');
  return `${salt}:${hash}`;
}

export function verifyPassword(password, stored) {
  if (!stored || !stored.includes(':')) return false;
  const [salt, expectedHash] = stored.split(':');
  const actualHash = crypto.scryptSync(password, salt, 64).toString('hex');
  return crypto.timingSafeEqual(Buffer.from(actualHash, 'hex'), Buffer.from(expectedHash, 'hex'));
}

export function initDb(dbPath = ':memory:', { adminUsernames = [] } = {}) {
  if (dbPath !== ':memory:') {
    fs.mkdirSync(path.dirname(dbPath), { recursive: true });
  }

  const db = new DatabaseSync(dbPath);

  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      uid TEXT PRIMARY KEY,
      username TEXT UNIQUE COLLATE NOCASE,
      display_name TEXT NOT NULL DEFAULT '',
      password_hash TEXT NOT NULL,
      subscription_token TEXT UNIQUE,
      total_bytes INTEGER NOT NULL DEFAULT 53687091200,
      used_bytes INTEGER NOT NULL DEFAULT 0,
      expires_at TEXT,
      plan_title TEXT NOT NULL DEFAULT 'Pro 50GB',
      vless_uuid TEXT NOT NULL,
      hysteria_password TEXT NOT NULL,
      channel TEXT NOT NULL DEFAULT 'prod',
      is_enabled INTEGER NOT NULL DEFAULT 1,
      last_seen_at TEXT,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
      updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
    );

    CREATE TABLE IF NOT EXISTS traffic_syncs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      uid TEXT NOT NULL,
      consumed_bytes INTEGER NOT NULL,
      node_host TEXT,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
    );

    CREATE TABLE IF NOT EXISTS reachability_reports (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      network TEXT NOT NULL,
      verdict TEXT NOT NULL,
      port INTEGER NOT NULL,
      box TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
    );

    -- Open operator registry: clients register what they see, admins only confirm/rename/merge.
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
      target TEXT NOT NULL DEFAULT '',
      detail TEXT NOT NULL DEFAULT '{}',
      created_at TEXT NOT NULL
    );
  `);

  // Roles arrived after the first deployments; add the column instead of recreating the table.
  const userColumns = db.prepare('PRAGMA table_info(users)').all();
  if (!userColumns.some((c) => c.name === 'role')) {
    db.exec("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'user'");
  }
  for (const username of adminUsernames) {
    db.prepare("UPDATE users SET role = 'admin' WHERE username = ? COLLATE NOCASE").run(username);
  }

  // Seed initial demo/test user if none exists
  const existing = db.prepare('SELECT COUNT(*) as count FROM users').get();
  if (existing.count === 0) {
    const defaultUserUid = 'usr_' + crypto.randomUUID().slice(0, 8);
    const defaultVless = crypto.randomUUID();
    const defaultHy2Pass = crypto.randomBytes(16).toString('hex');
    const defaultSubToken = crypto.randomBytes(16).toString('hex');
    const defaultPassHash = hashPassword('password123');
    const expires = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

    db.prepare(`
      INSERT INTO users (
        uid, username, display_name, password_hash, subscription_token,
        total_bytes, used_bytes, expires_at, plan_title, vless_uuid,
        hysteria_password, channel, is_enabled
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
    `).run(
      defaultUserUid,
      'test@ptunnel.net',
      'Test User',
      defaultPassHash,
      defaultSubToken,
      53687091200, // 50 GB
      0,
      expires,
      'Pro 50GB',
      defaultVless,
      defaultHy2Pass,
      'prod'
    );
  }

  return db;
}
