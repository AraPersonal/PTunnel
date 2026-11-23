import { config } from './config.js';
import { db } from './db.js';
import { isUsable, nowIso, timingSafeEqualString } from './util.js';

async function statsRequest(pathname, options = {}) {
  const response = await fetch(`${config.hysteriaStatsUrl}${pathname}`, {
    ...options,
    headers: {
      Authorization: config.hysteriaStatsSecret,
      ...(options.headers || {}),
    },
    signal: AbortSignal.timeout(6000),
  });
  if (!response.ok) {
    throw new Error(`Hysteria stats API returned HTTP ${response.status}`);
  }
  const text = await response.text();
  if (!text) return {};
  return JSON.parse(text);
}

export async function getTraffic() {
  const data = await statsRequest('/traffic?clear=1');
  const result = new Map();
  for (const [username, value] of Object.entries(data || {})) {
    const bytes = (Number(value?.tx) || 0) + (Number(value?.rx) || 0);
    result.set(username, bytes);
  }
  return result;
}

export async function getOnline() {
  const data = await statsRequest('/online');
  const result = new Map();
  if (Array.isArray(data)) {
    for (const item of data) {
      if (typeof item === 'string') result.set(item, 1);
      else if (item && typeof item === 'object') {
        result.set(item.id || item.username || item.addr, item.count ?? 1);
      }
    }
  } else if (data && typeof data === 'object') {
    for (const [key, value] of Object.entries(data)) {
      result.set(key, typeof value === 'number' ? value : 1);
    }
  }
  return result;
}

export async function kick(usernames) {
  const list = (usernames || []).filter(Boolean);
  if (list.length === 0) return;
  await statsRequest('/kick', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(list),
  });
}

export function authenticate(authString, addr) {
  const raw = String(authString || '');
  const separator = raw.indexOf(':');
  if (separator < 1) return null;
  const username = raw.slice(0, separator).trim();
  const password = raw.slice(separator + 1);

  const user = db
    .prepare('SELECT * FROM users WHERE username = ? COLLATE NOCASE')
    .get(username);
  if (!user) return null;
  if (!timingSafeEqualString(password, user.hysteria_password)) return null;
  if (!isUsable(user)) return null;

  db.prepare('UPDATE users SET last_seen_at = ?, last_ip = ?, updated_at = ? WHERE uid = ?')
    .run(nowIso(), String(addr || '').slice(0, 64), nowIso(), user.uid);

  return { ok: true, id: user.username };
}
