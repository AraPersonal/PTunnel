import express from 'express';
import helmet from 'helmet';
import { config } from './config.js';
import { db } from './db.js';
import { usersRouter } from './users.js';
import { adminRouter } from './admin.js';
import { reachabilityRouter, refreshReachabilityStatus, recordReport } from './reachability.js';
import * as hysteria from './hysteria.js';
import { startPoller, reconcileAll, reprovisionAllActive } from './poller.js';
import { timingSafeEqualString, nowIso } from './util.js';
import { createDiscovery, registerDiscoveryRoutes } from './discovery.js';
import { userAuth, adminAuth } from './auth.js';
import { createIpIntel, loadPrefixTable } from './ipIntel.js';
import { writeForwardingMiddleware } from './forwarding.js';

const app = express();
app.set('trust proxy', 1);
app.disable('x-powered-by');
app.use(helmet());
app.use(express.json({ limit: '64kb' }));

// Initialize IP intelligence and Network Discovery engine
const ipIntel = createIpIntel(loadPrefixTable(config.asnDbPath));
export const discovery = createDiscovery({
  db,
  config,
  ipIntel,
  now: () => new Date(),
});
discovery.seedOperators();
discovery.seedDoors();
try { discovery.applyHousekeeping(); } catch (_) {}
setInterval(() => {
  try { discovery.applyHousekeeping(); } catch (_) {}
}, 3600000).unref();

function requireInternal(req, res, next) {
  const key = String(req.query.key || req.headers['x-internal-key'] || '');
  if (!key || !timingSafeEqualString(key, config.internalSecret)) {
    res.status(403).json({ error: 'forbidden' });
    return;
  }
  next();
}

app.get('/internal/health', (_req, res) => {
  res.json({ ok: true, time: new Date().toISOString() });
});

app.post('/internal/hysteria/auth', requireInternal, (req, res) => {
  const { auth, addr } = req.body || {};
  const result = hysteria.authenticate(auth, addr);
  if (!result) {
    res.status(401).json({ ok: false });
    return;
  }
  res.json(result);
});

app.post('/internal/reconcile', requireInternal, (_req, res) => {
  res.status(202).json({ ok: true });
  setImmediate(() => {
    reprovisionAllActive().catch((err) => console.error(`[reconcile] ${err.message}`));
  });
});

/**
 * Usage measured by another box. The window identity makes a replay a no-op, so a replica that could
 * not reach us and re-sent the same round cannot charge the user twice.
 */
app.post('/internal/usage', requireInternal, (req, res) => {
  const box = String(req.body?.box || '').slice(0, 32);
  const window = String(req.body?.window || '').slice(0, 128);
  const reports = Array.isArray(req.body?.reports) ? req.body.reports.slice(0, 500) : [];
  if (!box || !window || !reports.length) {
    res.status(400).json({ error: 'bad_request', code: 'bad_request' });
    return;
  }
  const clean = reports
    .map((r) => ({ uid: String(r?.uid ?? ''), bytes: Math.max(0, Number(r?.bytes) || 0) }))
    .filter((r) => r.uid && r.bytes > 0);
  const total = clean.reduce((sum, r) => sum + r.bytes, 0);

  const claim = db.prepare('INSERT OR IGNORE INTO usage_windows (box, window_id, bytes, created_at) VALUES (?, ?, ?, ?)');
  const apply = db.prepare('UPDATE users SET used_bytes = used_bytes + ?, updated_at = ? WHERE uid = ?');
  const ts = nowIso();

  const outcome = db.transaction(() => {
    const claimed = claim.run(box, window, total, ts);
    if (!claimed.changes) return { applied: false };
    for (const report of clean) apply.run(report.bytes, ts, report.uid);
    return { applied: true, bytes: total, users: clean.length };
  })();

  if (!outcome.applied) {
    res.status(409).json({ ok: true, deduped: true });
    return;
  }
  console.log(`[usage] box=${box} users=${outcome.users} bytes=${outcome.bytes}`);
  res.json({ ok: true, applied: outcome.users, bytes: outcome.bytes });
});

/**
 * A report measured by the other box. The replica is the one that saw the client's address, and this
 * box is the one that keeps the database, so the coarse network arrives already computed — asking
 * this side to derive it from the replica's IP would file every client behind that box under a
 * single network and silence the vote it is meant to carry.
 */
app.post('/internal/reachability', requireInternal, (req, res) => {
  const body = req.body || {};
  const status = recordReport({
    network: String(body.network || ''),
    verdict: String(body.verdict || ''),
    port: Number(body.port) || 0,
    box: String(body.box || '').slice(0, 64),
  });
  if (!status) {
    res.status(400).json({ error: 'bad_request', code: 'bad_request' });
    return;
  }
  refreshReachabilityStatus();
  res.json({ ok: true, status });
});

// Write-forwarding: Followers forward all mutating requests to the cluster Leader
app.use(writeForwardingMiddleware());

// Network Discovery & Operator Registry (docs/design/0001)
registerDiscoveryRoutes(app, { discovery, auth: userAuth, adminAuth });

app.use('/v1', reachabilityRouter);
app.use('/v1', usersRouter);
app.use('/admin/api', adminRouter);

app.use((_req, res) => {
  res.status(404).json({ error: 'not_found' });
});

app.use((err, _req, res, _next) => {
  const status = Number(err.status) || 500;
  if (status >= 500) {
    console.error('[server]', err);
  }
  res.status(status).json({
    error: err.message || 'Internal server error',
    code: err.code || (status >= 500 ? 'internal_error' : 'error'),
  });
});

const server = app.listen(config.port, '127.0.0.1', () => {
  console.log(`[server] listening on 127.0.0.1:${config.port}`);
  startPoller();
  // Keep the published reachability picture fresh, so a stale "blocked" verdict ages out.
  refreshReachabilityStatus();
  setInterval(refreshReachabilityStatus, 5 * 60 * 1000).unref();
});

function shutdown(signal) {
  console.log(`[server] received ${signal}, shutting down`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5000).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('unhandledRejection', (reason) => {
  console.error('[server] unhandled rejection:', reason);
});
