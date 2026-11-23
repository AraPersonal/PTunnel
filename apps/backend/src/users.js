import express from 'express';
import fs from 'node:fs';
import rateLimit from 'express-rate-limit';
import bcrypt from 'bcryptjs';
import { config } from './config.js';
import { db, getSetting } from './db.js';
import { userAuth, signUserToken } from './auth.js';
import { buildLinks, buildSubscriptionPayload, buildSingboxConfig, serverMetadata, ALL_TAGS } from './links.js';
import { buildXrayClientConfig, APP_TAGS } from './xrayclient.js';
import {
  daysRemaining,
  httpError,
  isUsable,
  normalizeUsername,
  nowIso,
  planTitleFor,
  statusOf,
} from './util.js';

export const usersRouter = express.Router();

const asyncHandler = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

const loginLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 30,
  standardHeaders: true,
  legacyHeaders: false,
});

function sessionFor(user) {
  const status = statusOf(user);
  const usable = status === 'active';
  return {
    uid: user.uid,
    username: user.username,
    displayName: user.display_name || user.username,
    email: '',
    token: signUserToken(user),
    planTitle: planTitleFor(user),
    usedBytes: Number(user.used_bytes) || 0,
    totalBytes: Number(user.total_bytes) || 0,
    expiryDaysRemaining: daysRemaining(user.expires_at),
    expiresAt: user.expires_at,
    status,
    subscriptionPayload: usable ? buildSubscriptionPayload(user) : '',
    subscriptionUrl: `${config.publicBaseUrl}/v1/sub/${user.subscription_token}`,
    servers: usable ? serverMetadata().filter((item) => item.protocol !== 'hysteria2') : [],
    registrationEnabled: getSetting('registration_enabled', 'false') === 'true',
    announcement: getSetting('announcement', ''),
    supportUrl: getSetting('support_url', ''),
  };
}

usersRouter.post(
  '/auth/login',
  loginLimiter,
  asyncHandler(async (req, res) => {
    const username = normalizeUsername(req.body?.username ?? req.body?.email);
    const password = String(req.body?.password || '');
    if (!username || !password) {
      throw httpError(400, 'Username and password are required', 'invalid_request');
    }

    const user = db.prepare('SELECT * FROM users WHERE username = ? COLLATE NOCASE').get(username);
    const ok = user ? await bcrypt.compare(password, user.password_hash) : false;
    if (!user || !ok) {
      throw httpError(401, 'Invalid username or password', 'invalid_credentials');
    }

    db.prepare('UPDATE users SET last_seen_at = ?, last_ip = ? WHERE uid = ?')
      .run(nowIso(), String(req.ip || '').slice(0, 64), user.uid);

    try {
      const { discovery } = await import('./index.js');
      discovery?.noteServerLine?.(user.uid, req.headers['x-forwarded-for'] || req.ip);
    } catch (_) {}

    res.json(sessionFor(user));
  })
);

usersRouter.post('/auth/register', (_req, _res, next) => {
  next(httpError(403, 'Registration is disabled. Please contact support.', 'registration_disabled'));
});

usersRouter.get(
  '/user/profile',
  userAuth,
  asyncHandler(async (req, res) => {
    db.prepare('UPDATE users SET last_seen_at = ?, last_ip = ? WHERE uid = ?')
      .run(nowIso(), String(req.ip || '').slice(0, 64), req.user.uid);
    res.json(sessionFor(req.user));
  })
);

usersRouter.put(
  '/user/profile',
  userAuth,
  asyncHandler(async (req, res) => {
    const displayName = String(req.body?.displayName || '').trim().slice(0, 80);
    if (!displayName) throw httpError(400, 'displayName is required', 'invalid_request');
    db.prepare('UPDATE users SET display_name = ?, updated_at = ? WHERE uid = ?')
      .run(displayName, nowIso(), req.user.uid);
    const fresh = db.prepare('SELECT * FROM users WHERE uid = ?').get(req.user.uid);
    res.json(sessionFor(fresh));
  })
);

usersRouter.post(
  '/user/traffic-sync',
  userAuth,
  asyncHandler(async (req, res) => {
    db.prepare('UPDATE users SET last_seen_at = ?, last_ip = ? WHERE uid = ?')
      .run(nowIso(), String(req.ip || '').slice(0, 64), req.user.uid);
    const fresh = db.prepare('SELECT * FROM users WHERE uid = ?').get(req.user.uid);
    res.json(sessionFor(fresh));
  })
);

usersRouter.get(
  '/user/links',
  userAuth,
  asyncHandler(async (req, res) => {
    const status = statusOf(req.user);
    if (status !== 'active') {
      throw httpError(403, 'Account is not active', status);
    }
    res.json({
      links: buildLinks(req.user),
      subscriptionUrl: `${config.publicBaseUrl}/v1/sub/${req.user.subscription_token}`,
    });
  })
);

usersRouter.get(
  '/client/config',
  userAuth,
  asyncHandler(async (req, res) => {
    if (!isUsable(req.user)) {
      throw httpError(403, 'Account is not active', statusOf(req.user));
    }
    const tag = req.query.tag ? String(req.query.tag) : '';
    if (tag && !ALL_TAGS.includes(tag)) {
      throw httpError(400, 'Unknown protocol tag', 'invalid_request');
    }
    res.json({
      tag: tag || null,
      config: buildSingboxConfig(req.user, tag || null),
    });
  })
);

usersRouter.get(
  '/client/xray',
  userAuth,
  asyncHandler(async (req, res) => {
    if (!isUsable(req.user)) {
      throw httpError(403, 'Account is not active', statusOf(req.user));
    }
    const tag = String(req.query.tag || 'vless-reality');
    if (!APP_TAGS.includes(tag)) {
      throw httpError(400, 'Unknown app protocol tag', 'invalid_request');
    }
    res.json({
      tag,
      config: buildXrayClientConfig(req.user, tag),
    });
  })
);

usersRouter.get(
  '/sub/:token',
  asyncHandler(async (req, res) => {
    const user = db
      .prepare('SELECT * FROM users WHERE subscription_token = ?')
      .get(String(req.params.token || ''));
    if (!user) {
      throw httpError(404, 'Subscription not found', 'not_found');
    }

    const active = statusOf(user) === 'active';
    const total = Number(user.total_bytes) || 0;
    const used = Number(user.used_bytes) || 0;
    const expireUnix = user.expires_at
      ? Math.floor(new Date(user.expires_at).getTime() / 1000)
      : 0;

    res.setHeader(
      'Subscription-Userinfo',
      `upload=0; download=${used}; total=${total}; expire=${expireUnix}`
    );

    if (!active) {
      res.status(403).type('text/plain').send('');
      return;
    }

    res.type('text/plain').send(buildSubscriptionPayload(user));
  })
);

usersRouter.get('/app/meta', (_req, res) => {
  res.json({
    name: 'PTunnel',
    registrationEnabled: getSetting('registration_enabled', 'false') === 'true',
    announcement: getSetting('announcement', ''),
    supportUrl: getSetting('support_url', ''),
    servers: serverMetadata().filter((item) => item.protocol !== 'hysteria2'),
  });
});

const APP_RELEASE_FILE = process.env.APP_RELEASE_FILE || '/opt/ptunnel/app-release/latest.json';

usersRouter.get('/app/latest', (_req, res) => {
  try {
    const release = JSON.parse(fs.readFileSync(APP_RELEASE_FILE, 'utf8'));
    res.set('Cache-Control', 'no-store').json(release);
  } catch {
    res.status(404).json({ error: 'no_release', code: 'no_release' });
  }
});

usersRouter.get('/locations', (_req, res) => {
  // `is_active` is the kill switch for a box: flipping it removes that country from every client's
  // list on the next snapshot, without a release.
  const rows = db.prepare('SELECT * FROM servers WHERE is_active = 1 ORDER BY id ASC').all();
  res.set('Cache-Control', 'no-store').json({
    locations: rows.map((server) => ({
      id: server.id,
      name: server.name,
      country: server.location || server.name,
      host: server.host,
      domain: server.domain,
      flag: server.flag || '🌐',
      api_base: server.api_base || '',
    })),
  });
});

usersRouter.post('/auth/logout', (_req, res) => {
  res.status(204).end();
});
