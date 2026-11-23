import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { config } from './config.js';
import { db } from './db.js';
import { nowIso, parseJsonSafely } from './util.js';

const execFileAsync = promisify(execFile);

let provisionChain = Promise.resolve();

export function enqueueProvision(fn) {
  const next = provisionChain.then(fn, fn);
  provisionChain = next.catch(() => {});
  return next;
}

async function xrayApi(args, timeoutMs = 8000) {
  const { stdout, stderr } = await execFileAsync(config.xrayBin, args, {
    timeout: timeoutMs,
    maxBuffer: 2 * 1024 * 1024,
  });
  return { stdout: String(stdout || ''), stderr: String(stderr || '') };
}

export async function inboundUser(tag, email) {
  const { stdout } = await xrayApi([
    'api',
    'inbounduser',
    `--server=${config.xrayApiAddr}`,
    `-tag=${tag}`,
    `-email=${email}`,
  ]);
  const parsed = parseJsonSafely(stdout);
  const users = parsed && Array.isArray(parsed.users) ? parsed.users : [];
  const found = users.find((u) => u && u.account);
  return found || null;
}

async function removeUserFromTag(tag, email) {
  try {
    await xrayApi([
      'api',
      'rmu',
      `--server=${config.xrayApiAddr}`,
      `-tag=${tag}`,
      email,
    ]);
  } catch {
    // user was not present on this inbound
  }
}

function userConfigFile(user, tag) {
  const common = { tag, listen: '0.0.0.0' };
  switch (tag) {
    case config.xrayTags.vless:
      return {
        ...common,
        port: config.ports.vless,
        protocol: 'vless',
        settings: {
          clients: [{ id: user.vless_uuid, flow: 'xtls-rprx-vision', email: user.uid }],
          decryption: 'none',
        },
      };
    case config.xrayTags.vmess:
      return {
        ...common,
        port: config.ports.vmess,
        protocol: 'vmess',
        settings: { clients: [{ id: user.vmess_uuid, email: user.uid }] },
      };
    case config.xrayTags.trojan:
      return {
        ...common,
        port: config.ports.trojan,
        protocol: 'trojan',
        settings: { clients: [{ password: user.trojan_password, email: user.uid }] },
      };
    case config.xrayTags.ss:
      return {
        ...common,
        port: config.ports.ss,
        protocol: 'shadowsocks',
        settings: {
          method: config.ssMethod,
          password: config.ssServerKey,
          users: [{ password: user.ss_password, email: user.uid }],
          network: 'tcp,udp',
        },
      };
    case config.xrayTags.kcp:
      // No XTLS Vision flow here: that is a TCP/RAW feature, mKCP carries plain VLESS.
      return {
        ...common,
        port: config.ports.kcp,
        protocol: 'vless',
        settings: {
          clients: [{ id: user.vless_uuid, email: user.uid }],
          decryption: 'none',
        },
      };
    default:
      throw new Error(`Unknown inbound tag: ${tag}`);
  }
}

export async function provisionUser(user) {
  return enqueueProvision(async () => {
    const tags = Object.values(config.xrayTags);
    for (const tag of tags) {
      await removeUserFromTag(tag, user.uid);
    }

    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'ptunnel-'));
    try {
      const files = [];
      for (const tag of tags) {
        const file = path.join(dir, `${tag}.json`);
        const payload = { inbounds: [userConfigFile(user, tag)] };
        await fs.writeFile(file, JSON.stringify(payload), { mode: 0o600 });
        files.push(file);
      }

      const { stdout } = await xrayApi([
        'api',
        'adu',
        `--server=${config.xrayApiAddr}`,
        ...files,
      ]);
      if (!/Added\s+5\s+user\(s\)/.test(stdout)) {
        throw new Error(`Failed to provision all protocols: ${stdout.trim()}`);
      }
    } finally {
      await fs.rm(dir, { recursive: true, force: true }).catch(() => {});
    }

    db.prepare('UPDATE users SET xray_provisioned = 1, updated_at = ? WHERE uid = ?')
      .run(nowIso(), user.uid);
    return true;
  });
}

export async function deprovisionUser(user) {
  return enqueueProvision(async () => {
    const tags = Object.values(config.xrayTags);
    for (const tag of tags) {
      await removeUserFromTag(tag, user.uid);
    }
    db.prepare('UPDATE users SET xray_provisioned = 0, updated_at = ? WHERE uid = ?')
      .run(nowIso(), user.uid);
    return true;
  });
}

export async function queryStats() {
  const { stdout } = await xrayApi([
    'api',
    'statsquery',
    `--server=${config.xrayApiAddr}`,
    '-reset',
    '-pattern',
    'user>>>',
  ]);
  const parsed = parseJsonSafely(stdout);
  const result = new Map();
  if (!parsed || !Array.isArray(parsed.stat)) return result;

  for (const item of parsed.stat) {
    const match = /^user>>>(.+)>>>traffic>>>(uplink|downlink)$/.exec(item.name || '');
    if (!match) continue;
    const uid = match[1];
    const bytes = Number(item.value) || 0;
    const current = result.get(uid) || { up: 0, down: 0 };
    if (match[2] === 'uplink') current.up += bytes;
    else current.down += bytes;
    result.set(uid, current);
  }
  return result;
}

export async function getOnlineUsers() {
  const { stdout } = await xrayApi([
    'api',
    'statsgetallonlineusers',
    `--server=${config.xrayApiAddr}`,
  ]);
  const parsed = parseJsonSafely(stdout);
  const result = new Map();
  if (!parsed) return result;

  const add = (email, count) => {
    let normalized = String(email || '');
    if (!normalized) return;
    const onlineMatch = /^user>>>(.+)>>>online$/.exec(normalized);
    if (onlineMatch) normalized = onlineMatch[1];
    result.set(normalized, Number(count) || 1);
  };

  const walk = (value) => {
    if (!value) return;
    if (Array.isArray(value)) {
      for (const item of value) {
        if (typeof item === 'string') add(item, 1);
        else if (item && typeof item === 'object') {
          add(item.email || item.user || item.id || item.name, item.count ?? item.online ?? item.connections ?? 1);
        }
      }
      return;
    }
    if (typeof value === 'object') {
      for (const [key, item] of Object.entries(value)) {
        if (typeof item === 'number') add(key, item);
        else if (typeof item === 'boolean') add(key, item ? 1 : 0);
        else if (item && typeof item === 'object') {
          add(item.email || item.user || item.id || item.name || key, item.count ?? item.online ?? item.connections ?? 1);
        }
      }
    }
  };

  walk(parsed.users ?? parsed.online ?? parsed);
  return result;
}

export async function reprovisionAllActive(users) {
  let ok = 0;
  let failed = 0;
  for (const user of users) {
    try {
      await provisionUser(user);
      ok += 1;
    } catch (err) {
      failed += 1;
      console.error(`[xray] reprovision failed for ${user.username}: ${err.message}`);
    }
  }
  return { ok, failed };
}
