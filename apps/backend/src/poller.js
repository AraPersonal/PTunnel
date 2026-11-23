import fs from 'node:fs';
import { config } from './config.js';
import { db } from './db.js';
import * as xray from './xray.js';
import * as hysteria from './hysteria.js';
import { isUsable, nowIso, statusOf } from './util.js';

let pollRunning = false;
let reconcileRunning = false;

const isReplica = () => Boolean(config.usagePrimaryUrl);

/**
 * Pending usage, kept in a file rather than a table: the replica's database is replaced wholesale by
 * the next snapshot, and reports waiting for a blocked primary must survive that.
 */
const outboxPath = () => `${config.dbPath}-outbox.jsonl`;

function appendOutbox(window, reports) {
  try {
    fs.appendFileSync(outboxPath(), `${JSON.stringify({ window, reports })}\n`);
  } catch (err) {
    console.error(`[poller] outbox append failed: ${err.message}`);
  }
}

function readOutbox() {
  try {
    return fs
      .readFileSync(outboxPath(), 'utf8')
      .split('\n')
      .filter((line) => line.trim())
      .map((line) => {
        try {
          return JSON.parse(line);
        } catch {
          return null;
        }
      })
      .filter(Boolean);
  } catch {
    return [];
  }
}

function writeOutbox(entries) {
  try {
    if (!entries.length) {
      fs.rmSync(outboxPath(), { force: true });
      return;
    }
    fs.writeFileSync(outboxPath(), entries.map((e) => JSON.stringify(e)).join('\n') + '\n');
  } catch (err) {
    console.error(`[poller] outbox rewrite failed: ${err.message}`);
  }
}

/** Deliver whatever the primary has not acknowledged yet. A 409 means the window was already applied. */
async function flushOutbox() {
  const pending = readOutbox();
  if (!pending.length) return;
  const kept = [];
  for (const entry of pending) {
    try {
      const res = await fetch(`${config.usagePrimaryUrl.replace(/\/$/, '')}/internal/usage`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-internal-key': config.internalSecret },
        body: JSON.stringify({ box: config.boxId, window: entry.window, reports: entry.reports }),
        signal: AbortSignal.timeout(10000),
      });
      if (res.ok || res.status === 409) continue;
      console.error(`[poller] usage report refused: HTTP ${res.status}`);
    } catch (err) {
      console.error(`[poller] usage report unreachable: ${err.message}`);
    }
    kept.push(entry);
  }
  writeOutbox(kept);
}

export function activeUsers() {
  const now = nowIso();
  return db
    .prepare(
      `SELECT * FROM users
       WHERE is_enabled = 1
         AND (expires_at IS NULL OR expires_at > ?)
         AND (total_bytes = 0 OR used_bytes < total_bytes)`
    )
    .all(now);
}

function allUsers() {
  return db.prepare('SELECT * FROM users').all();
}

async function safe(promise, label, fallback) {
  try {
    return await promise;
  } catch (err) {
    console.error(`[poller] ${label} failed: ${err.message}`);
    return fallback;
  }
}

async function pollOnce() {
  if (pollRunning) return;
  pollRunning = true;

  try {
    /** One identity per measurement round, so a replayed report can never double-count. */
    const pollWindow = `${config.boxId}|${new Date().toISOString()}|${process.pid}`;
    const [xrayDeltas, hyDeltas, xrayOnline, hyOnline] = await Promise.all([
      safe(xray.queryStats(), 'xray stats', new Map()),
      safe(hysteria.getTraffic(), 'hysteria traffic', new Map()),
      safe(xray.getOnlineUsers(), 'xray online', new Map()),
      safe(hysteria.getOnline(), 'hysteria online', new Map()),
    ]);

    const users = allUsers();
    const byUid = new Map(users.map((u) => [u.uid, u]));
    const byUsername = new Map(users.map((u) => [u.username.toLowerCase(), u]));

    const addUsage = db.prepare('UPDATE users SET used_bytes = used_bytes + ? WHERE uid = ?');
    const resetOnline = db.prepare('UPDATE users SET online_xray = 0, online_hysteria = 0');
    const markXrayOnline = db.prepare(
      'UPDATE users SET online_xray = ?, last_seen_at = ?, updated_at = ? WHERE uid = ?'
    );
    const markHysteriaOnline = db.prepare(
      'UPDATE users SET online_hysteria = ?, last_seen_at = ?, updated_at = ? WHERE uid = ?'
    );

    const measured = new Map();
    const addMeasured = (uid, bytes) => {
      const value = Number(bytes) || 0;
      if (uid == null || value <= 0) return;
      measured.set(uid, (measured.get(uid) || 0) + value);
    };

    db.transaction(() => {
      resetOnline.run();

      for (const [uid, delta] of xrayDeltas.entries()) {
        if (!byUid.has(uid)) continue;
        addMeasured(uid, (Number(delta.up) || 0) + (Number(delta.down) || 0));
      }

      for (const [username, bytes] of hyDeltas.entries()) {
        const user = byUsername.get(String(username).toLowerCase());
        if (!user) continue;
        addMeasured(user.uid, bytes);
      }

      const ts = nowIso();
      for (const [uid, count] of xrayOnline.entries()) {
        if (!byUid.has(uid)) continue;
        markXrayOnline.run(Number(count) || 1, ts, ts, uid);
      }
      for (const [username, count] of hyOnline.entries()) {
        const user = byUsername.get(String(username).toLowerCase());
        if (!user) continue;
        markHysteriaOnline.run(Number(count) || 1, ts, ts, user.uid);
      }
    })();

    if (measured.size) {
      const reports = [...measured.entries()].map(([uid, bytes]) => ({ uid, bytes }));
      if (isReplica()) {
        appendOutbox(pollWindow, reports);
        await flushOutbox();
      } else {
        db.transaction(() => {
          for (const report of reports) addUsage.run(report.bytes, report.uid);
        })();
      }
    } else if (isReplica()) {
      await flushOutbox();
    }

    const fresh = allUsers();
    for (const user of fresh) {
      const status = statusOf(user);
      if (!isUsable(user)) {
        if (user.xray_provisioned) {
          await safe(xray.deprovisionUser(user), `deprovision ${user.username}`, null);
          await safe(hysteria.kick([user.username]), `kick ${user.username}`, null);
          db.prepare('UPDATE users SET online_xray = 0, online_hysteria = 0 WHERE uid = ?').run(user.uid);
        }
      } else if (!user.xray_provisioned) {
        await safe(xray.provisionUser(user), `provision ${user.username}`, null);
      }
    }
  } finally {
    pollRunning = false;
  }
}

export async function reprovisionAllActive() {
  const users = activeUsers();
  const result = await xray.reprovisionAllActive(users);
  console.log(`[poller] reprovisioned active users: ok=${result.ok} failed=${result.failed}`);
  return result;
}

export async function reconcileAll() {
  if (reconcileRunning) return;
  reconcileRunning = true;

  try {
    const users = allUsers();
    let restored = 0;
    let removed = 0;

    for (const user of users) {
      if (isUsable(user)) {
        let present = null;
        try {
          present = await xray.inboundUser(config.xrayTags.vless, user.uid);
        } catch (err) {
          console.error(`[reconcile] xray API unavailable: ${err.message}`);
          break;
        }
        if (!present) {
          try {
            await xray.provisionUser(user);
            restored += 1;
          } catch (err) {
            console.error(`[reconcile] failed to restore ${user.username}: ${err.message}`);
          }
        }
      } else if (user.xray_provisioned) {
        try {
          await xray.deprovisionUser(user);
          await hysteria.kick([user.username]);
          removed += 1;
        } catch (err) {
          console.error(`[reconcile] failed to remove ${user.username}: ${err.message}`);
        }
      }
    }

    if (restored || removed) {
      console.log(`[reconcile] restored=${restored} removed=${removed}`);
    }
  } finally {
    reconcileRunning = false;
  }
}

export function startPoller() {
  setTimeout(() => {
    reprovisionAllActive().catch((err) => console.error(`[startup] ${err.message}`));
    pollOnce().catch((err) => console.error(`[startup] ${err.message}`));
  }, 3000);

  setInterval(() => {
    pollOnce().catch((err) => console.error(`[poller] ${err.message}`));
  }, config.pollIntervalMs);

  setInterval(() => {
    reconcileAll().catch((err) => console.error(`[reconcile] ${err.message}`));
  }, config.reconcileIntervalMs);
}
