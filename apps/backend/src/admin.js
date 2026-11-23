import crypto from 'node:crypto';
import express from 'express';
import rateLimit from 'express-rate-limit';
import bcrypt from 'bcryptjs';
import { config } from './config.js';
import { db, getSetting, setSettingValue } from './db.js';
import { adminAuth, requireSuperadmin, signAdminToken } from './auth.js';
import { adminUserView, mapUserRow } from './views.js';
import { buildLinks } from './links.js';
import { provisionUser, deprovisionUser } from './xray.js';
import { kick } from './hysteria.js';
import {
  GB,
  addDays,
  expiresAtFromDays,
  httpError,
  isUsable,
  normalizeUsername,
  nowIso,
  randomBase64,
  randomHex,
  validatePassword,
  validateUsername,
} from './util.js';

export const adminRouter = express.Router();

const asyncHandler = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

const loginLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 20,
  standardHeaders: true,
  legacyHeaders: false,
});

function audit(req, action, targetType, targetId, details) {
  db.prepare(
    `INSERT INTO audit_log (admin_id, admin_username, action, target_type, target_id, details, ip)
     VALUES (?, ?, ?, ?, ?, ?, ?)`
  ).run(
    req.admin?.id ?? null,
    req.admin?.username ?? null,
    action,
    targetType ?? null,
    targetId != null ? String(targetId) : null,
    details ? JSON.stringify(details) : null,
    String(req.ip || '').slice(0, 64)
  );
}

function getAdminById(id) {
  return db.prepare('SELECT * FROM admins WHERE id = ?').get(Number(id));
}

function getUserByUid(uid) {
  return db.prepare('SELECT * FROM users WHERE uid = ?').get(String(uid || ''));
}

function detailFor(user, extra = {}) {
  return {
    ...adminUserView(user),
    links: buildLinks(user),
    subscriptionUrl: `${config.publicBaseUrl}/v1/sub/${user.subscription_token}`,
    lastIp: user.last_ip || '',
    xrayProvisioned: Boolean(user.xray_provisioned),
    note: user.note || '',
    ...extra,
  };
}

adminRouter.post(
  '/auth/login',
  loginLimiter,
  asyncHandler(async (req, res) => {
    const username = normalizeUsername(req.body?.username);
    const password = String(req.body?.password || '');
    if (!username || !password) {
      throw httpError(400, 'Username and password are required', 'invalid_request');
    }

    const admin = db.prepare('SELECT * FROM admins WHERE username = ? COLLATE NOCASE').get(username);
    const ok = admin ? await bcrypt.compare(password, admin.password_hash) : false;
    if (!admin || !ok || !admin.is_active) {
      throw httpError(401, 'Invalid username or password', 'invalid_credentials');
    }

    db.prepare('UPDATE admins SET last_login_at = ? WHERE id = ?').run(nowIso(), admin.id);
    res.json({
      token: signAdminToken(admin),
      admin: {
        id: admin.id,
        username: admin.username,
        role: admin.role,
      },
    });
  })
);

adminRouter.get(
  '/me',
  adminAuth,
  asyncHandler(async (req, res) => {
    res.json({
      id: req.admin.id,
      username: req.admin.username,
      role: req.admin.role,
    });
  })
);

adminRouter.post(
  '/auth/change-password',
  adminAuth,
  asyncHandler(async (req, res) => {
    const currentPassword = String(req.body?.currentPassword || '');
    const newPassword = String(req.body?.newPassword || '');
    if (!validatePassword(newPassword)) {
      throw httpError(400, 'Password must be 8-128 characters', 'invalid_request');
    }
    const ok = await bcrypt.compare(currentPassword, req.admin.password_hash);
    if (!ok) throw httpError(401, 'Current password is incorrect', 'invalid_credentials');

    const hash = await bcrypt.hash(newPassword, config.bcryptRounds);
    db.prepare('UPDATE admins SET password_hash = ?, token_version = token_version + 1 WHERE id = ?')
      .run(hash, req.admin.id);
    const fresh = getAdminById(req.admin.id);
    audit(req, 'admin.change_password', 'admin', req.admin.id);
    res.json({ token: signAdminToken(fresh) });
  })
);

adminRouter.get(
  '/dashboard',
  adminAuth,
  asyncHandler(async (_req, res) => {
    const now = nowIso();
    const count = (sql, ...params) => db.prepare(sql).get(...params).c;

    const stats = {
      totalUsers: count('SELECT COUNT(*) AS c FROM users'),
      activeUsers: count(
        `SELECT COUNT(*) AS c FROM users
         WHERE is_enabled = 1
           AND (expires_at IS NULL OR expires_at > ?)
           AND (total_bytes = 0 OR used_bytes < total_bytes)`,
        now
      ),
      expiredUsers: count(
        'SELECT COUNT(*) AS c FROM users WHERE is_enabled = 1 AND expires_at IS NOT NULL AND expires_at <= ?',
        now
      ),
      quotaUsers: count(
        `SELECT COUNT(*) AS c FROM users
         WHERE is_enabled = 1 AND (expires_at IS NULL OR expires_at > ?)
           AND total_bytes > 0 AND used_bytes >= total_bytes`,
        now
      ),
      disabledUsers: count('SELECT COUNT(*) AS c FROM users WHERE is_enabled = 0'),
      onlineSessions: db.prepare('SELECT COALESCE(SUM(online_xray + online_hysteria), 0) AS c FROM users').get().c,
      totalUsedBytes: db.prepare('SELECT COALESCE(SUM(used_bytes), 0) AS c FROM users').get().c,
      newToday: count('SELECT COUNT(*) AS c FROM users WHERE created_at >= ?', new Date(new Date().setUTCHours(0, 0, 0, 0)).toISOString()),
    };

    const topUsers = db
      .prepare(
        `SELECT * FROM users ORDER BY used_bytes DESC LIMIT 5`
      )
      .all()
      .map(mapUserRow);

    const recentAudit = db
      .prepare('SELECT * FROM audit_log ORDER BY id DESC LIMIT 12')
      .all();

    res.json({ stats, topUsers, recentAudit });
  })
);

function statusClause(status) {
  const now = nowIso();
  switch (status) {
    case 'active':
      return {
        sql: 'is_enabled = 1 AND (expires_at IS NULL OR expires_at > ?) AND (total_bytes = 0 OR used_bytes < total_bytes)',
        params: [now],
      };
    case 'expired':
      return {
        sql: 'is_enabled = 1 AND expires_at IS NOT NULL AND expires_at <= ?',
        params: [now],
      };
    case 'quota_exceeded':
      return {
        sql: 'is_enabled = 1 AND (expires_at IS NULL OR expires_at > ?) AND total_bytes > 0 AND used_bytes >= total_bytes',
        params: [now],
      };
    case 'disabled':
      return { sql: 'is_enabled = 0', params: [] };
    default:
      return null;
  }
}

adminRouter.get(
  '/users',
  adminAuth,
  asyncHandler(async (req, res) => {
    const search = String(req.query.search || '').trim();
    const status = String(req.query.status || '').trim();
    const page = Math.max(1, Number(req.query.page) || 1);
    const pageSize = Math.min(100, Math.max(1, Number(req.query.pageSize) || 20));

    const clauses = [];
    const params = [];

    if (search) {
      clauses.push('(username LIKE ? OR display_name LIKE ? OR uid LIKE ?)');
      const like = `%${search}%`;
      params.push(like, like, like);
    }

    const statusRule = statusClause(status);
    if (statusRule) {
      clauses.push(`(${statusRule.sql})`);
      params.push(...statusRule.params);
    }

    const where = clauses.length ? `WHERE ${clauses.join(' AND ')}` : '';
    const total = db.prepare(`SELECT COUNT(*) AS c FROM users ${where}`).get(...params).c;
    const offset = (page - 1) * pageSize;
    const rows = db
      .prepare(`SELECT * FROM users ${where} ORDER BY created_at DESC LIMIT ? OFFSET ?`)
      .all(...params, pageSize, offset)
      .map(mapUserRow);

    res.json({ items: rows, total, page, pageSize });
  })
);

adminRouter.post(
  '/users',
  adminAuth,
  asyncHandler(async (req, res) => {
    const username = normalizeUsername(req.body?.username);
    const suppliedPassword = req.body?.password ? String(req.body.password) : '';
    const displayName = String(req.body?.displayName || '').trim().slice(0, 80);
    const note = String(req.body?.note || '').trim().slice(0, 500);

    if (!validateUsername(username)) {
      throw httpError(400, 'Username must be 3-32 characters (letters, digits, . _ -)', 'invalid_request');
    }
    if (suppliedPassword && !validatePassword(suppliedPassword)) {
      throw httpError(400, 'Password must be 8-128 characters', 'invalid_request');
    }

    const existing = db.prepare('SELECT uid FROM users WHERE username = ? COLLATE NOCASE').get(username);
    if (existing) throw httpError(409, 'Username already exists', 'conflict');

    const planId = req.body?.planId ? Number(req.body.planId) : null;
    const plan = planId ? db.prepare('SELECT * FROM plans WHERE id = ?').get(planId) : null;
    if (planId && !plan) throw httpError(400, 'Unknown plan', 'invalid_request');

    const totalGbRaw = req.body?.totalGB !== undefined ? Number(req.body.totalGB) : plan ? Number(plan.total_gb) : 0;
    const daysRaw = req.body?.expiryDays !== undefined ? Number(req.body.expiryDays) : plan ? Number(plan.days) : 0;
    if (!Number.isFinite(totalGbRaw) || totalGbRaw < 0) throw httpError(400, 'Invalid totalGB', 'invalid_request');
    if (!Number.isFinite(daysRaw) || daysRaw < 0) throw httpError(400, 'Invalid expiryDays', 'invalid_request');

    const generatedPassword = suppliedPassword || crypto.randomBytes(9).toString('base64url');
    const passwordHash = await bcrypt.hash(generatedPassword, config.bcryptRounds);

    const uid = `usr_${randomHex(6)}`;
    const totalBytes = Math.round(totalGbRaw * GB);
    const expiresAt = expiresAtFromDays(daysRaw);
    const planTitle = String(req.body?.planTitle || plan?.name || '').trim().slice(0, 100);

    db.prepare(
      `INSERT INTO users (
        uid, username, display_name, password_hash, subscription_token, total_bytes, used_bytes,
        expires_at, plan_id, plan_title, note, vless_uuid, vmess_uuid, trojan_password,
        ss_password, hysteria_password
      ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).run(
      uid,
      username,
      displayName || username,
      passwordHash,
      crypto.randomBytes(24).toString('hex'),
      totalBytes,
      expiresAt,
      plan ? plan.id : null,
      planTitle,
      note,
      crypto.randomUUID(),
      crypto.randomUUID(),
      randomHex(16),
      randomBase64(16),
      randomHex(16)
    );

    let user = getUserByUid(uid);
    let provisionError = null;
    try {
      await provisionUser(user);
      user = getUserByUid(uid);
    } catch (err) {
      provisionError = err.message;
      console.error(`[admin] provision failed for ${username}: ${err.message}`);
    }

    audit(req, 'user.create', 'user', uid, {
      username,
      totalGb: totalGbRaw,
      days: daysRaw,
      plan: plan ? plan.name : null,
    });

    res.status(201).json({
      user: detailFor(user, { generatedPassword: suppliedPassword ? undefined : generatedPassword }),
      provisionError,
    });
  })
);

adminRouter.get(
  '/users/:uid',
  adminAuth,
  asyncHandler(async (req, res) => {
    const user = getUserByUid(req.params.uid);
    if (!user) throw httpError(404, 'User not found', 'not_found');
    res.json(detailFor(user));
  })
);

adminRouter.patch(
  '/users/:uid',
  adminAuth,
  asyncHandler(async (req, res) => {
    const user = getUserByUid(req.params.uid);
    if (!user) throw httpError(404, 'User not found', 'not_found');

    const updates = [];
    const params = [];

    if (req.body?.displayName !== undefined) {
      const displayName = String(req.body.displayName).trim().slice(0, 80);
      updates.push('display_name = ?');
      params.push(displayName || user.username);
    }
    if (req.body?.note !== undefined) {
      updates.push('note = ?');
      params.push(String(req.body.note || '').trim().slice(0, 500));
    }
    if (req.body?.planTitle !== undefined) {
      updates.push('plan_title = ?');
      params.push(String(req.body.planTitle || '').trim().slice(0, 100));
    }
    if (req.body?.isEnabled !== undefined) {
      const enabled = req.body.isEnabled ? 1 : 0;
      if (enabled !== Number(user.is_enabled)) {
        updates.push('is_enabled = ?');
        params.push(enabled);
      }
    }

    if (updates.length) {
      params.push(nowIso(), user.uid);
      db.prepare(`UPDATE users SET ${updates.join(', ')}, updated_at = ? WHERE uid = ?`).run(...params);
    }

    let fresh = getUserByUid(user.uid);
    if (req.body?.isEnabled !== undefined) {
      if (fresh.is_enabled) {
        if (isUsable(fresh)) {
          try {
            await provisionUser(fresh);
          } catch (err) {
            console.error(`[admin] enable/provision failed for ${fresh.username}: ${err.message}`);
          }
        }
      } else {
        try {
          await deprovisionUser(fresh);
          await kick([fresh.username]);
        } catch (err) {
          console.error(`[admin] disable failed for ${fresh.username}: ${err.message}`);
        }
        db.prepare('UPDATE users SET online_xray = 0, online_hysteria = 0 WHERE uid = ?').run(fresh.uid);
      }
      fresh = getUserByUid(user.uid);
      audit(req, req.body.isEnabled ? 'user.enable' : 'user.disable', 'user', fresh.uid, {
        username: fresh.username,
      });
    }

    if (updates.length) {
      audit(req, 'user.update', 'user', fresh.uid, { username: fresh.username });
    }

    res.json(detailFor(fresh));
  })
);

adminRouter.post(
  '/users/:uid/charge',
  adminAuth,
  asyncHandler(async (req, res) => {
    const user = getUserByUid(req.params.uid);
    if (!user) throw httpError(404, 'User not found', 'not_found');

    const mode = req.body?.mode === 'set' ? 'set' : 'add';
    const gb = Number(req.body?.gb ?? 0);
    const days = Number(req.body?.days ?? 0);
    const resetUsage = Boolean(req.body?.resetUsage);
    if (!Number.isFinite(gb) || gb < 0 || !Number.isFinite(days) || days < 0) {
      throw httpError(400, 'Invalid gb/days values', 'invalid_request');
    }

    let total = Number(user.total_bytes) || 0;
    let expiresAt = user.expires_at || null;
    let used = Number(user.used_bytes) || 0;

    if (mode === 'set') {
      total = Math.round(gb * GB);
      expiresAt = days > 0 ? addDays(null, days) : null;
    } else {
      if (gb > 0 && total > 0) total = Math.round(total + gb * GB);
      if (days > 0) expiresAt = addDays(expiresAt, days);
    }
    if (resetUsage) used = 0;

    db.prepare(
      'UPDATE users SET total_bytes = ?, expires_at = ?, used_bytes = ?, updated_at = ? WHERE uid = ?'
    ).run(total, expiresAt, used, nowIso(), user.uid);

    let fresh = getUserByUid(user.uid);
    let provisionError = null;

    if (isUsable(fresh)) {
      try {
        if (!fresh.xray_provisioned) await provisionUser(fresh);
      } catch (err) {
        provisionError = err.message;
        console.error(`[admin] charge/provision failed for ${fresh.username}: ${err.message}`);
      }
    } else if (fresh.xray_provisioned) {
      try {
        await deprovisionUser(fresh);
        await kick([fresh.username]);
      } catch (err) {
        console.error(`[admin] charge/deprovision failed for ${fresh.username}: ${err.message}`);
      }
    }

    fresh = getUserByUid(user.uid);
    audit(req, 'user.charge', 'user', fresh.uid, {
      username: fresh.username,
      mode,
      gb,
      days,
      resetUsage,
      totalBytes: fresh.total_bytes,
      expiresAt: fresh.expires_at,
    });

    res.json(detailFor(fresh, { provisionError }));
  })
);

adminRouter.post(
  '/users/:uid/reset-password',
  adminAuth,
  asyncHandler(async (req, res) => {
    const user = getUserByUid(req.params.uid);
    if (!user) throw httpError(404, 'User not found', 'not_found');

    const password = req.body?.password ? String(req.body.password) : crypto.randomBytes(9).toString('base64url');
    if (!validatePassword(password)) {
      throw httpError(400, 'Password must be 8-128 characters', 'invalid_request');
    }
    const hash = await bcrypt.hash(password, config.bcryptRounds);
    db.prepare(
      'UPDATE users SET password_hash = ?, token_version = token_version + 1, updated_at = ? WHERE uid = ?'
    ).run(hash, nowIso(), user.uid);

    audit(req, 'user.reset_password', 'user', user.uid, { username: user.username });
    res.json({ username: user.username, password });
  })
);

adminRouter.post(
  '/users/:uid/sync',
  adminAuth,
  asyncHandler(async (req, res) => {
    const user = getUserByUid(req.params.uid);
    if (!user) throw httpError(404, 'User not found', 'not_found');

    let error = null;
    try {
      if (isUsable(user)) await provisionUser(user);
      else await deprovisionUser(user);
    } catch (err) {
      error = err.message;
    }
    const fresh = getUserByUid(user.uid);
    audit(req, 'user.sync', 'user', user.uid, { username: user.username, error });
    res.json({ user: detailFor(fresh), error });
  })
);

adminRouter.delete(
  '/users/:uid',
  adminAuth,
  requireSuperadmin,
  asyncHandler(async (req, res) => {
    const user = getUserByUid(req.params.uid);
    if (!user) throw httpError(404, 'User not found', 'not_found');
    if (String(req.body?.confirm || '') !== user.username) {
      throw httpError(400, 'Type the username to confirm deletion', 'invalid_request');
    }

    try {
      await deprovisionUser(user);
      await kick([user.username]);
    } catch (err) {
      console.error(`[admin] delete/deprovision failed for ${user.username}: ${err.message}`);
    }

    db.prepare('DELETE FROM users WHERE uid = ?').run(user.uid);
    audit(req, 'user.delete', 'user', user.uid, { username: user.username });
    res.status(204).end();
  })
);

adminRouter.get(
  '/plans',
  adminAuth,
  asyncHandler(async (_req, res) => {
    const plans = db.prepare('SELECT * FROM plans ORDER BY id ASC').all();
    res.json({ plans });
  })
);

adminRouter.post(
  '/plans',
  adminAuth,
  requireSuperadmin,
  asyncHandler(async (req, res) => {
    const name = String(req.body?.name || '').trim();
    const totalGb = Number(req.body?.totalGB ?? 0);
    const days = Number(req.body?.days ?? 0);
    const price = Number(req.body?.priceToman ?? 0);
    if (!name) throw httpError(400, 'Plan name is required', 'invalid_request');
    if (!Number.isFinite(totalGb) || totalGb < 0 || !Number.isFinite(days) || days < 0) {
      throw httpError(400, 'Invalid plan values', 'invalid_request');
    }
    const result = db
      .prepare('INSERT INTO plans (name, total_gb, days, price_toman) VALUES (?, ?, ?, ?)')
      .run(name, totalGb, days, Number.isFinite(price) ? price : 0);
    audit(req, 'plan.create', 'plan', result.lastInsertRowid, { name });
    res.status(201).json(db.prepare('SELECT * FROM plans WHERE id = ?').get(result.lastInsertRowid));
  })
);

adminRouter.patch(
  '/plans/:id',
  adminAuth,
  requireSuperadmin,
  asyncHandler(async (req, res) => {
    const plan = db.prepare('SELECT * FROM plans WHERE id = ?').get(Number(req.params.id));
    if (!plan) throw httpError(404, 'Plan not found', 'not_found');

    const updates = [];
    const params = [];
    for (const [key, column, cast] of [
      ['name', 'name', String],
      ['totalGB', 'total_gb', Number],
      ['days', 'days', Number],
      ['priceToman', 'price_toman', Number],
      ['isActive', 'is_active', (v) => (v ? 1 : 0)],
    ]) {
      if (req.body?.[key] !== undefined) {
        updates.push(`${column} = ?`);
        params.push(cast(req.body[key]));
      }
    }
    if (!updates.length) return res.json(plan);
    params.push(plan.id);
    db.prepare(`UPDATE plans SET ${updates.join(', ')} WHERE id = ?`).run(...params);
    audit(req, 'plan.update', 'plan', plan.id, { name: plan.name });
    res.json(db.prepare('SELECT * FROM plans WHERE id = ?').get(plan.id));
  })
);

adminRouter.delete(
  '/plans/:id',
  adminAuth,
  requireSuperadmin,
  asyncHandler(async (req, res) => {
    const plan = db.prepare('SELECT * FROM plans WHERE id = ?').get(Number(req.params.id));
    if (!plan) throw httpError(404, 'Plan not found', 'not_found');
    const used = db.prepare('SELECT COUNT(*) AS c FROM users WHERE plan_id = ?').get(plan.id).c;
    if (used > 0) throw httpError(409, 'Plan is used by users and cannot be deleted', 'conflict');
    db.prepare('DELETE FROM plans WHERE id = ?').run(plan.id);
    audit(req, 'plan.delete', 'plan', plan.id, { name: plan.name });
    res.status(204).end();
  })
);

adminRouter.get(
  '/admins',
  adminAuth,
  requireSuperadmin,
  asyncHandler(async (_req, res) => {
    const admins = db
      .prepare('SELECT id, username, role, is_active, last_login_at, created_at FROM admins ORDER BY id ASC')
      .all();
    res.json({ admins });
  })
);

adminRouter.post(
  '/admins',
  adminAuth,
  requireSuperadmin,
  asyncHandler(async (req, res) => {
    const username = normalizeUsername(req.body?.username);
    const password = String(req.body?.password || '');
    const role = req.body?.role === 'superadmin' ? 'superadmin' : 'admin';
    if (!validateUsername(username)) {
      throw httpError(400, 'Username must be 3-32 characters (letters, digits, . _ -)', 'invalid_request');
    }
    if (!validatePassword(password)) {
      throw httpError(400, 'Password must be 8-128 characters', 'invalid_request');
    }
    const existing = db.prepare('SELECT id FROM admins WHERE username = ? COLLATE NOCASE').get(username);
    if (existing) throw httpError(409, 'Admin username already exists', 'conflict');

    const hash = await bcrypt.hash(password, config.bcryptRounds);
    const result = db
      .prepare('INSERT INTO admins (username, password_hash, role) VALUES (?, ?, ?)')
      .run(username, hash, role);
    audit(req, 'admin.create', 'admin', result.lastInsertRowid, { username, role });
    res.status(201).json({
      id: result.lastInsertRowid,
      username,
      role,
    });
  })
);

adminRouter.patch(
  '/admins/:id',
  adminAuth,
  requireSuperadmin,
  asyncHandler(async (req, res) => {
    const admin = getAdminById(req.params.id);
    if (!admin) throw httpError(404, 'Admin not found', 'not_found');

    const updates = [];
    const params = [];

    if (req.body?.role !== undefined) {
      const role = req.body.role === 'superadmin' ? 'superadmin' : 'admin';
      if (admin.id === req.admin.id && role !== 'superadmin') {
        throw httpError(400, 'Cannot demote yourself', 'invalid_request');
      }
      updates.push('role = ?');
      params.push(role);
    }
    if (req.body?.isActive !== undefined) {
      const active = req.body.isActive ? 1 : 0;
      if (admin.id === req.admin.id && !active) {
        throw httpError(400, 'Cannot deactivate yourself', 'invalid_request');
      }
      updates.push('is_active = ?');
      params.push(active);
      if (!active) {
        updates.push('token_version = token_version + 1');
      }
    }
    if (req.body?.password) {
      const password = String(req.body.password);
      if (!validatePassword(password)) {
        throw httpError(400, 'Password must be 8-128 characters', 'invalid_request');
      }
      updates.push('password_hash = ?', 'token_version = token_version + 1');
      params.push(await bcrypt.hash(password, config.bcryptRounds));
    }

    if (updates.length) {
      params.push(admin.id);
      db.prepare(`UPDATE admins SET ${updates.join(', ')} WHERE id = ?`).run(...params);
      audit(req, 'admin.update', 'admin', admin.id, { username: admin.username });
    }

    res.json(
      db.prepare('SELECT id, username, role, is_active, last_login_at, created_at FROM admins WHERE id = ?').get(admin.id)
    );
  })
);

adminRouter.get(
  '/audit',
  adminAuth,
  asyncHandler(async (req, res) => {
    const limit = Math.min(200, Math.max(1, Number(req.query.limit) || 50));
    const items = db.prepare('SELECT * FROM audit_log ORDER BY id DESC LIMIT ?').all(limit);
    res.json({ items });
  })
);

adminRouter.get(
  '/settings',
  adminAuth,
  asyncHandler(async (_req, res) => {
    res.json({
      announcement: getSetting('announcement', ''),
      supportUrl: getSetting('support_url', ''),
      registrationEnabled: getSetting('registration_enabled', 'false') === 'true',
    });
  })
);

adminRouter.put(
  '/settings',
  adminAuth,
  requireSuperadmin,
  asyncHandler(async (req, res) => {
    if (req.body?.announcement !== undefined) {
      setSettingValue('announcement', String(req.body.announcement || '').slice(0, 1000));
    }
    if (req.body?.supportUrl !== undefined) {
      setSettingValue('support_url', String(req.body.supportUrl || '').slice(0, 300));
    }
    audit(req, 'settings.update', 'settings', null);
    res.json({
      announcement: getSetting('announcement', ''),
      supportUrl: getSetting('support_url', ''),
      registrationEnabled: getSetting('registration_enabled', 'false') === 'true',
    });
  })
);

adminRouter.get(
  '/servers',
  adminAuth,
  asyncHandler(async (_req, res) => {
    const servers = db.prepare('SELECT * FROM servers ORDER BY id ASC').all().map((server) => ({
      ...server,
      protocols: JSON.parse(server.protocols || '[]'),
    }));
    res.json({
      servers,
      node: {
        domain: config.domain,
        host: config.host,
        xrayApi: config.xrayApiAddr,
        hysteriaStats: config.hysteriaStatsUrl,
      },
    });
  })
);

adminRouter.post(
  '/servers',
  adminAuth,
  requireSuperadmin,
  asyncHandler(async (req, res) => {
    const { id, name, host, domain, location, flag, protocols, api_base } = req.body || {};
    if (!id || !host) {
      throw httpError(400, 'id and host are required', 'invalid_request');
    }
    const safeDomain = domain || `${host.replace(/\./g, '-')}.nip.io`;
    const safeLocation = location || name || 'Unknown';
    const safeFlag = flag || '🌐';
    const safeApiBase = api_base || `https://${safeDomain}/v1/`;
    const safeProtocols = typeof protocols === 'string' ? protocols : JSON.stringify(protocols || []);

    db.prepare(`
      INSERT INTO servers (id, name, host, domain, location, flag, protocols, api_base)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        name = excluded.name,
        host = excluded.host,
        domain = excluded.domain,
        location = excluded.location,
        flag = excluded.flag,
        protocols = excluded.protocols,
        api_base = excluded.api_base
    `).run(id, name || id, host, safeDomain, safeLocation, safeFlag, safeProtocols, safeApiBase);

    audit(req, 'server.upsert', 'server', id, { host, location: safeLocation });
    res.status(201).json({ ok: true, id, host, location: safeLocation, api_base: safeApiBase });
  })
);

adminRouter.get(
  '/system',
  adminAuth,
  asyncHandler(async (_req, res) => {
    res.json({
      linkHost: config.domain,
      host: config.host,
      ports: config.ports,
      pollIntervalMs: config.pollIntervalMs,
      time: nowIso(),
    });
  })
);
