import express from 'express';
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';
import { db } from './db.js';

/**
 * Fleet-wide reachability picture.
 *
 * IP blocking in Iran is direction-specific: the server looks perfectly healthy from anywhere
 * outside, so no external witness can detect it. Only clients inside the country can, which
 * means the verdict has to be assembled from their reports.
 *
 * A single report is never a verdict. Iran has many ISPs and one phone on a bad cell can fail
 * for its own reasons, so a report only counts when it comes from a *distinct network*, and a
 * fleet verdict needs several of them inside the window.
 */

export const reachabilityRouter = express.Router();

const STATUS_DIR = '/var/www/ptu-bootstrap';
const STATUS_FILE = path.join(STATUS_DIR, 'status.json');
const WINDOW_MS = 30 * 60 * 1000;
const RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
const MIN_DISTINCT_NETWORKS = 3;

const VERDICTS = new Set(['ok', 'device_network', 'server_unreachable', 'tunnel_failed']);

db.exec(`
  CREATE TABLE IF NOT EXISTS reachability_reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    network TEXT NOT NULL,
    verdict TEXT NOT NULL,
    port INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_reachability_created ON reachability_reports (created_at);
`);

// Which control-plane endpoint the reporting client actually reached — the question behind "can
// anyone in Iran reach the second box at all", which used to need one person opening a URL by hand.
// Added later than the table, so an existing database needs the column.
const reportColumns = db
  .prepare("SELECT name FROM pragma_table_info('reachability_reports')")
  .all()
  .map((row) => row.name);
if (!reportColumns.includes('box')) {
  db.exec("ALTER TABLE reachability_reports ADD COLUMN box TEXT NOT NULL DEFAULT ''");
  console.log('[reachability] migrated reports.box');
}

/** Coarse client identity: a /24 keeps one phone from counting as a whole network. */
function sourceNetwork(ip) {
  const raw = String(ip || '').replace(/^::ffff:/, '');
  if (raw.includes(':')) return raw.split(':').slice(0, 4).join(':');
  return raw.split('.').slice(0, 3).join('.');
}

/**
 * The two shapes [sourceNetwork] produces: three octets for IPv4 (`1.2.3`, no trailing `.0`) and the
 * first four hextets for IPv6. Holding that exact shape matters — the same network written two ways
 * would be two voters, and the whole verdict rests on counting networks once.
 */
function isCoarseNetwork(value) {
  const v4 = String(value).match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4) return v4.slice(1).every((part) => Number(part) <= 255);
  return value.includes(':') && /^[0-9a-f:]+$/i.test(value) && value.split(':').length <= 8;
}

/**
 * One report, from either box. Returns the new fleet status, or null when the report is not one this
 * fleet counts. Publishing is left to the caller so a test can exercise the rules without writing the
 * live status file.
 */
export function recordReport({ network, verdict, port, box }) {
  if (!VERDICTS.has(verdict) || !isCoarseNetwork(network)) return null;
  db.prepare(
    'INSERT INTO reachability_reports (network, verdict, port, box, created_at) VALUES (?, ?, ?, ?, ?)'
  ).run(network, verdict, Number(port) || 0, String(box || '').slice(0, 64), Date.now());

  db.prepare('DELETE FROM reachability_reports WHERE created_at < ?').run(Date.now() - RETENTION_MS);
  return computeFleetStatus();
}

/**
 * Hand a report to the box that keeps the database.
 *
 * A replica's copy is replaced by the next snapshot, so a report that only landed there would be
 * gone within a minute — including the exact case this telemetry exists for, a client that can reach
 * this box and not the other one. The network travels with the report because only the receiving box
 * of the client's connection saw the client's address; asking the primary to guess it from the
 * replica's IP would file every such client under one network and hide the vote.
 */
async function forwardToPrimary(report) {
  const res = await fetch(`${config.usagePrimaryUrl.replace(/\/$/, '')}/internal/reachability`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-internal-key': config.internalSecret },
    body: JSON.stringify(report),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
}

export function computeFleetStatus() {
  const since = Date.now() - WINDOW_MS;
  const rows = db
    .prepare(
      `SELECT network, verdict, box FROM reachability_reports
       WHERE created_at >= ?
       GROUP BY network, verdict, box`
    )
    .all(since);

  const unreachable = new Set(
    rows.filter((r) => r.verdict === 'server_unreachable').map((r) => r.network)
  );
  const tunnelFailed = new Set(
    rows.filter((r) => r.verdict === 'tunnel_failed').map((r) => r.network)
  );
  // Heartbeats from clients whose tunnel is up. Without them the fleet only ever speaks when
  // something is wrong, so three bad networks could outvote a hundred healthy ones.
  const healthy = new Set(rows.filter((r) => r.verdict === 'ok').map((r) => r.network));

  // Per control-plane endpoint, in the window: how many distinct networks reached it, and how many
  // reported a problem while going through it. `boxes[<host>].ok > 0` is the answer to "can clients
  // in this country reach the second box at all" — no longer a manual one-person test.
  const perBox = new Map();
  for (const row of rows) {
    if (!row.box) continue;
    const entry = perBox.get(row.box) || { ok: 0, bad: 0 };
    if (row.verdict === 'ok') entry.ok += 1;
    else entry.bad += 1;
    perBox.set(row.box, entry);
  }

  let verdict = 'ok';
  if (unreachable.size >= MIN_DISTINCT_NETWORKS && unreachable.size > healthy.size) {
    verdict = 'server_unreachable';
  } else if (unreachable.size > 0) {
    // Some evidence, not enough to act on: never switch the fleet on one network's word.
    verdict = 'insufficient_data';
  } else if (tunnelFailed.size >= MIN_DISTINCT_NETWORKS && tunnelFailed.size > healthy.size) {
    verdict = 'tunnel_failed';
  }

  return {
    verdict,
    windowMinutes: WINDOW_MS / 60000,
    distinctNetworksHealthy: healthy.size,
    distinctNetworksUnreachable: unreachable.size,
    distinctNetworksTunnelFailure: tunnelFailed.size,
    requiredDistinctNetworks: MIN_DISTINCT_NETWORKS,
    boxes: Object.fromEntries([...perBox.entries()].sort((a, b) => a[0].localeCompare(b[0]))),
    updatedAt: new Date().toISOString(),
  };
}

function publish(status) {
  try {
    fs.mkdirSync(STATUS_DIR, { recursive: true });
    const tmp = `${STATUS_FILE}.tmp`;
    // World-readable on purpose: nginx serves this file as www-data.
    fs.writeFileSync(tmp, JSON.stringify(status, null, 2), { mode: 0o644 });
    fs.renameSync(tmp, STATUS_FILE);
  } catch (err) {
    console.error(`[reachability] publish failed: ${err.message}`);
  }
}

export function refreshReachabilityStatus() {
  publish(computeFleetStatus());
}

reachabilityRouter.post('/reachability', async (req, res) => {
  const body = req.body || {};
  const report = {
    network: sourceNetwork(req.ip),
    verdict: String(body.verdict || ''),
    port: Number(body.port) || 0,
    box: String(body.box || '').trim().slice(0, 64),
  };

  if (!VERDICTS.has(report.verdict)) {
    res.status(400).json({ error: 'invalid_verdict' });
    return;
  }

  // Our own copy is kept either way: on the primary it is the fleet's memory, and on a replica it is
  // accurate until the next snapshot replaces it.
  const status = recordReport(report);
  if (!status) {
    res.status(400).json({ error: 'invalid_report' });
    return;
  }
  publish(status);

  if (config.usagePrimaryUrl) {
    try {
      await forwardToPrimary(report);
    } catch (err) {
      // The report still stands locally, so the client is told it landed. Losing one heartbeat to a
      // primary that is down is not worth failing the call the client is already retrying hourly.
      console.error(`[reachability] forward to primary failed: ${err.message}`);
    }
  }

  res.json({ ok: true, status });
});
