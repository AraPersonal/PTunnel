import express from 'express';
import net from 'node:net';
import crypto from 'node:crypto';
import { initDb, verifyPassword, hashPassword } from './db.js';
import { signJwt, authMiddleware } from './auth.js';
import { buildClientConfig } from './profile.js';

const startedAt = Date.now();

/** One TCP round trip to the core's admin API */
function tcpProbe(host, port, timeoutMs = 500) {
  return new Promise((resolve) => {
    const socket = new net.Socket();
    let done = false;
    const finish = (ok) => {
      if (done) return;
      done = true;
      socket.destroy();
      resolve(ok);
    };
    socket.setTimeout(timeoutMs);
    socket.once('connect', () => finish(true));
    socket.once('timeout', () => finish(false));
    socket.once('error', () => finish(false));
    socket.connect(port, host);
  });
}

function sessionPayload(user, token, config) {
  const expiresAt = user.expires_at ? new Date(user.expires_at) : new Date(Date.now() + 30 * 86400000);
  const remainingDays = Math.max(0, Math.ceil((expiresAt.getTime() - Date.now()) / (1000 * 60 * 60 * 24)));

  return {
    uid: user.uid,
    displayName: user.display_name || user.username,
    email: user.username,
    token,
    planTitle: user.plan_title || 'Pro 50GB',
    usedBytes: Number(user.used_bytes) || 0,
    totalBytes: Number(user.total_bytes) || 53687091200,
    expiryDaysRemaining: remainingDays,
    username: user.username,
    status: user.is_enabled ? 'active' : 'suspended',
    expiresAt: expiresAt.toISOString(),
    servers: [
      {
        tag: 'multi',
        id: 'multi',
        name: 'Auto (Fastest)',
        protocol: 'vless',
        port: config.kcpPort,
        host: config.publicHost,
        flag: '🇩🇪',
        transport: 'kcp',
        security: 'none',
      },
      {
        tag: 'reality',
        id: 'reality',
        name: 'Germany Reality',
        protocol: 'vless',
        port: config.realityPort,
        host: config.publicHost,
        flag: '🇩🇪',
        transport: 'tcp',
        security: 'reality',
      },
    ],
  };
}

export function createApp(config, deps = {}) {
  const probe = deps.probe ?? tcpProbe;
  const db = deps.db ?? initDb(config.dbPath);
  const app = express();

  app.disable('x-powered-by');
  app.use(express.json({ limit: '64kb' }));

  // --- Health & Diagnostic Endpoints ---
  app.get('/healthz', (_req, res) => {
    res.json({ status: 'ok', service: config.service, env: config.environment });
  });

  app.get('/version', (_req, res) => {
    res.json({
      service: config.service,
      env: config.environment,
      version: config.buildVersion,
      gitSha: config.gitSha,
      uptimeSeconds: Math.floor((Date.now() - startedAt) / 1000),
    });
  });

  app.get('/readyz', async (_req, res) => {
    if (config.requireEnv && !['stage', 'prod'].includes(config.environment)) {
      return res.status(503).json({ ready: false, reason: `PT_ENV must be stage or prod, got "${config.environment}"` });
    }
    const coreUp = await probe(config.xrayApiHost, config.xrayApiPort);
    if (!coreUp) return res.status(503).json({ ready: false, reason: 'core admin API is not answering', core: false });
    res.json({ ready: true, core: true, env: config.environment });
  });

  // --- Anti-Censorship Bootstrap & Out-of-Band Reachability ---
  app.get('/b', (_req, res) => {
    res.json({
      api: [config.publicBaseUrl],
      pins: {
        [config.publicDomain]: config.publicHost,
      },
      mirrors: [`${config.publicBaseUrl}b`],
    });
  });

  app.get('/s', (_req, res) => {
    res.json({ ok: true, verdict: 'ok', timestamp: new Date().toISOString() });
  });

  app.get('/c', (_req, res) => {
    res.json({
      ips: [{ ip: '188.114.96.3' }, { ip: '188.114.97.3' }, { ip: '104.17.2.81' }],
    });
  });

  // --- User Authentication ---
  const handleLogin = (req, res) => {
    const username = String(req.body?.username || req.body?.email || '').trim().toLowerCase();
    const pass = String(req.body?.password || '');

    if (!username || !pass) {
      return res.status(400).json({ error: 'invalid_request', message: 'Username and password required' });
    }

    const user = db.prepare('SELECT * FROM users WHERE username = ? COLLATE NOCASE').get(username);
    if (!user || !verifyPassword(pass, user.password_hash)) {
      return res.status(401).json({ error: 'invalid_credentials', message: 'Invalid username or password' });
    }

    if (!user.is_enabled) {
      return res.status(403).json({ error: 'account_disabled', message: 'Account is suspended or disabled' });
    }

    // Update last seen
    db.prepare('UPDATE users SET last_seen_at = ?, updated_at = ? WHERE uid = ?').run(
      new Date().toISOString(),
      new Date().toISOString(),
      user.uid
    );

    const token = signJwt({ uid: user.uid, username: user.username, channel: user.channel }, config.jwtSecret);
    res.json(sessionPayload(user, token, config));
  };

  app.post('/v1/auth/login', handleLogin);
  app.post('/v1/login', handleLogin);
  app.post('/auth/login', handleLogin);

  app.post('/v1/auth/register', (req, res) => {
    const username = String(req.body?.email || req.body?.username || '').trim().toLowerCase();
    const pass = String(req.body?.password || '');
    const displayName = String(req.body?.displayName || username).trim();

    if (!username || !pass) {
      return res.status(400).json({ error: 'invalid_request', message: 'Email and password required' });
    }

    const existing = db.prepare('SELECT uid FROM users WHERE username = ? COLLATE NOCASE').get(username);
    if (existing) {
      return res.status(409).json({ error: 'user_exists', message: 'An account with this email already exists' });
    }

    const uid = 'usr_' + crypto.randomUUID().slice(0, 8);
    const passHash = hashPassword(pass);
    const vlessUuid = crypto.randomUUID();
    const hy2Pass = crypto.randomBytes(16).toString('hex');
    const subToken = crypto.randomBytes(16).toString('hex');
    const expires = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

    db.prepare(`
      INSERT INTO users (
        uid, username, display_name, password_hash, subscription_token,
        vless_uuid, hysteria_password, expires_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).run(uid, username, displayName, passHash, subToken, vlessUuid, hy2Pass, expires);

    const user = db.prepare('SELECT * FROM users WHERE uid = ?').get(uid);
    const token = signJwt({ uid: user.uid, username: user.username, channel: user.channel }, config.jwtSecret);
    res.json(sessionPayload(user, token, config));
  });

  // --- Authenticated User Endpoints ---
  const auth = authMiddleware(config.jwtSecret, db);

  const handleProfile = (req, res) => {
    const token = req.headers.authorization.slice(7).trim();
    res.json(sessionPayload(req.user, token, config));
  };

  app.get('/v1/user/profile', auth, handleProfile);
  app.get('/user/profile', auth, handleProfile);

  app.put('/v1/user/profile', auth, (req, res) => {
    const displayName = String(req.body?.displayName || '').trim();
    if (!displayName) {
      return res.status(400).json({ error: 'invalid_request', message: 'displayName required' });
    }
    db.prepare('UPDATE users SET display_name = ?, updated_at = ? WHERE uid = ?').run(
      displayName,
      new Date().toISOString(),
      req.user.uid
    );
    req.user.display_name = displayName;
    const token = req.headers.authorization.slice(7).trim();
    res.json(sessionPayload(req.user, token, config));
  });

  app.post('/v1/user/traffic-sync', auth, (req, res) => {
    const deltaBytes = Math.max(0, Number(req.body?.consumedSessionBytes) || 0);
    const host = String(req.body?.activeNodeHost || '');

    if (deltaBytes > 0) {
      db.prepare('UPDATE users SET used_bytes = used_bytes + ?, updated_at = ? WHERE uid = ?').run(
        deltaBytes,
        new Date().toISOString(),
        req.user.uid
      );
      db.prepare('INSERT INTO traffic_syncs (uid, consumed_bytes, node_host) VALUES (?, ?, ?)').run(
        req.user.uid,
        deltaBytes,
        host
      );
    }

    const freshUser = db.prepare('SELECT * FROM users WHERE uid = ?').get(req.user.uid);
    const token = req.headers.authorization.slice(7).trim();
    res.json(sessionPayload(freshUser, token, config));
  });

  // --- Dynamic Profile & Locations ---
  app.get('/v1/client/xray', auth, (req, res) => {
    const tag = String(req.query.tag || 'multi');
    const xrayConfig = buildClientConfig(req.user, tag, config);
    res.json({ config: xrayConfig });
  });

  app.get('/v1/locations', (_req, res) => {
    res.json({
      locations: [
        {
          id: 'de-1',
          name: 'Germany - Frankfurt',
          country: 'Germany',
          host: config.publicHost,
          domain: config.publicDomain,
          flag: '🇩🇪',
          api_base: config.publicBaseUrl,
        },
      ],
    });
  });

  // --- Fleet Telemetry ---
  const handleReachability = (req, res) => {
    const network = String(req.body?.network || '');
    const verdict = String(req.body?.verdict || '');
    const port = Number(req.body?.port) || 0;
    const box = String(req.body?.box || '');

    if (network || verdict) {
      db.prepare('INSERT INTO reachability_reports (network, verdict, port, box) VALUES (?, ?, ?, ?)').run(
        network.slice(0, 32),
        verdict.slice(0, 32),
        port,
        box.slice(0, 64)
      );
    }
    res.json({ ok: true });
  };

  app.post('/v1/reachability', handleReachability);
  app.post('/internal/reachability', handleReachability);

  // --- Range-Supported Download & App Updates (Solving Problem 3) ---
  app.get('/v1/app/latest', (_req, res) => {
    res.json({
      versionCode: 66,
      versionName: '0.66',
      sig: '',
      url: `${config.publicBaseUrl}dl`,
      sha256: '',
      sizeBytes: 1048576,
      minVersionCode: 49,
      mandatory: false,
      notes: 'PTunnel with Range-Chunked unthrottled downloads',
    });
  });

  // Download endpoint with HTTP 206 Partial Content support
  app.get('/dl', (req, res) => {
    const dummyFileSize = 1048576; // 1 MB test dummy
    const range = req.headers.range;

    res.setHeader('Accept-Ranges', 'bytes');
    res.setHeader('Content-Type', 'application/vnd.android.package-archive');

    if (!range) {
      res.setHeader('Content-Length', dummyFileSize);
      return res.status(200).send(Buffer.alloc(dummyFileSize, 0));
    }

    const match = range.match(/bytes=(\d+)-(\d+)?/);
    if (!match) {
      return res.status(416).setHeader('Content-Range', `bytes */${dummyFileSize}`).end();
    }

    const start = parseInt(match[1], 10);
    const end = match[2] ? parseInt(match[2], 10) : dummyFileSize - 1;
    const chunksize = end - start + 1;

    res.status(206);
    res.setHeader('Content-Range', `bytes ${start}-${end}/${dummyFileSize}`);
    res.setHeader('Content-Length', chunksize);
    res.send(Buffer.alloc(chunksize, 0));
  });

  return app;
}
