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

export function initDb(dbPath = ':memory:') {
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
  `);

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
