import jwt from 'jsonwebtoken';
import { config } from './config.js';
import { db } from './db.js';
import { httpError } from './util.js';

export function signUserToken(user) {
  return jwt.sign(
    { typ: 'user', ver: user.token_version, username: user.username },
    config.userJwtSecret,
    { subject: user.uid, expiresIn: config.userTokenTtl, issuer: 'ptunnel', audience: 'user' }
  );
}

export function signAdminToken(admin) {
  return jwt.sign(
    { typ: 'admin', ver: admin.token_version, role: admin.role, username: admin.username },
    config.adminJwtSecret,
    { subject: String(admin.id), expiresIn: config.adminTokenTtl, issuer: 'ptunnel', audience: 'admin' }
  );
}

function extractBearer(req) {
  const header = req.headers.authorization || '';
  return header.startsWith('Bearer ') ? header.slice(7).trim() : '';
}

export function userAuth(req, _res, next) {
  const token = extractBearer(req);
  if (!token) return next(httpError(401, 'Missing bearer token', 'unauthorized'));
  let payload;
  try {
    payload = jwt.verify(token, config.userJwtSecret, { issuer: 'ptunnel', audience: 'user' });
  } catch {
    return next(httpError(401, 'Invalid or expired token', 'unauthorized'));
  }
  if (payload.typ !== 'user') return next(httpError(401, 'Invalid token type', 'unauthorized'));
  const user = db.prepare('SELECT * FROM users WHERE uid = ?').get(payload.sub);
  if (!user || Number(user.token_version) !== Number(payload.ver)) {
    return next(httpError(401, 'Session is no longer valid', 'unauthorized'));
  }
  req.user = user;
  return next();
}

export function adminAuth(req, _res, next) {
  const token = extractBearer(req);
  if (!token) return next(httpError(401, 'Missing bearer token', 'unauthorized'));
  let payload;
  try {
    payload = jwt.verify(token, config.adminJwtSecret, { issuer: 'ptunnel', audience: 'admin' });
  } catch {
    return next(httpError(401, 'Invalid or expired token', 'unauthorized'));
  }
  if (payload.typ !== 'admin') return next(httpError(401, 'Invalid token type', 'unauthorized'));
  const admin = db.prepare('SELECT * FROM admins WHERE id = ?').get(Number(payload.sub));
  if (!admin || !admin.is_active || Number(admin.token_version) !== Number(payload.ver)) {
    return next(httpError(401, 'Admin session is no longer valid', 'unauthorized'));
  }
  req.admin = admin;
  return next();
}

export function requireSuperadmin(req, _res, next) {
  if (!req.admin || req.admin.role !== 'superadmin') {
    return next(httpError(403, 'Superadmin access required', 'forbidden'));
  }
  return next();
}
