import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import { config } from './config.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

fs.mkdirSync(path.dirname(config.dbPath), { recursive: true, mode: 0o750 });

export const db = new Database(config.dbPath);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');
db.pragma('busy_timeout = 5000');

db.exec(fs.readFileSync(path.join(__dirname, 'schema.sql'), 'utf8'));

// schema.sql only ever runs CREATE TABLE IF NOT EXISTS, so an existing database needs its columns
// added explicitly. Guarded by pragma_table_info, which makes this safe to run on every boot.
const serverColumns = db
  .prepare("SELECT name FROM pragma_table_info('servers')")
  .all()
  .map((row) => row.name);
if (!serverColumns.includes('api_base')) {
  db.exec("ALTER TABLE servers ADD COLUMN api_base TEXT NOT NULL DEFAULT ''");
  console.log('[db] migrated servers.api_base');
}

db.prepare(
  `INSERT OR IGNORE INTO servers (id, name, host, domain, location, flag, protocols)
   VALUES (?, ?, ?, ?, ?, ?, ?)`
).run(
  'srv_main',
  'PTunnel Main',
  config.host,
  config.domain,
  '',
  '🌐',
  JSON.stringify([
    { tag: 'vless-reality', protocol: 'vless', name: 'Reality', port: 443 },
    { tag: 'hysteria2', protocol: 'hysteria2', name: 'Hysteria2', port: 443 },
    { tag: 'vmess-ws-tls', protocol: 'vmess', name: 'VMess WS', port: 2083 },
    { tag: 'trojan-tls', protocol: 'trojan', name: 'Trojan', port: 2053 },
    { tag: 'shadowsocks', protocol: 'shadowsocks', name: 'Shadowsocks', port: 8388 },
  ])
);

// Only fill an empty value: a replica must never overwrite the row it received from the primary.
if (config.publicApiBase) {
  db.prepare(
    "UPDATE servers SET api_base = ? WHERE id = 'srv_main' AND (api_base IS NULL OR api_base = '')"
  ).run(config.publicApiBase);
}

const insertPeer = db.prepare(
  `INSERT OR IGNORE INTO servers (id, name, host, domain, location, flag, protocols, api_base)
   VALUES (@id, @name, @host, @domain, @location, @flag, @protocols, @api_base)`
);
for (const peer of config.peerServers) {
  insertPeer.run({
    id: String(peer.id),
    name: String(peer.name || peer.id),
    host: String(peer.host),
    domain: String(peer.domain || ''),
    location: String(peer.location || peer.name || ''),
    flag: String(peer.flag || '🌐'),
    protocols: JSON.stringify(peer.protocols || []),
    api_base: String(peer.api_base || ''),
  });
}
if (config.peerServers.length) {
  console.log(`[db] published ${config.peerServers.length} peer server(s) in the location list`);
}

const setSetting = db.prepare('INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)');
setSetting.run('registration_enabled', 'false');
setSetting.run('support_url', '');
setSetting.run('announcement', '');
setSetting.run('app_min_version', '1');

const planCount = db.prepare('SELECT COUNT(*) AS c FROM plans').get().c;
if (planCount === 0) {
  db.prepare('INSERT INTO plans (name, total_gb, days, price_toman) VALUES (?, ?, ?, ?)')
    .run('Starter 50 GB / 30 days', 50, 30, 0);
}

const adminCount = db.prepare('SELECT COUNT(*) AS c FROM admins').get().c;
if (adminCount === 0 && config.seedAdminPassword) {
  const hash = bcrypt.hashSync(config.seedAdminPassword, config.bcryptRounds);
  db.prepare('INSERT INTO admins (username, password_hash, role) VALUES (?, ?, ?)')
    .run(config.seedAdminUsername, hash, 'superadmin');
  console.log(`[db] seeded superadmin "${config.seedAdminUsername}"`);
}

try {
  fs.chmodSync(config.dbPath, 0o640);
} catch {
  // ignore
}

export function getSetting(key, fallback = '') {
  const row = db.prepare('SELECT value FROM settings WHERE key = ?').get(key);
  return row ? row.value : fallback;
}

export function setSettingValue(key, value) {
  db.prepare(
    'INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value'
  ).run(key, String(value ?? ''));
}
