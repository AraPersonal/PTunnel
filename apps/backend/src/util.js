import crypto from 'node:crypto';

export const GB = 1024 ** 3;

export function randomHex(bytes = 16) {
  return crypto.randomBytes(bytes).toString('hex');
}

export function randomBase64(bytes = 16) {
  return crypto.randomBytes(bytes).toString('base64');
}

export function newUid() {
  return `usr_${randomHex(6)}`;
}

export function newSubscriptionToken() {
  return randomHex(24);
}

export function nowIso() {
  return new Date().toISOString();
}

export function addDays(dateIso, days) {
  const base = dateIso ? new Date(dateIso) : new Date();
  const from = base.getTime() > Date.now() ? base : new Date();
  from.setUTCDate(from.getUTCDate() + Number(days || 0));
  return from.toISOString();
}

export function expiresAtFromDays(days) {
  const n = Number(days || 0);
  if (!Number.isFinite(n) || n <= 0) return null;
  return addDays(null, n);
}

export function daysRemaining(expiresAt) {
  if (!expiresAt) return 0;
  const ms = new Date(expiresAt).getTime() - Date.now();
  return Math.max(0, Math.ceil(ms / 86400000));
}

export function statusOf(user, now = Date.now()) {
  if (!user) return 'disabled';
  if (!user.is_enabled) return 'disabled';
  if (user.expires_at && new Date(user.expires_at).getTime() <= now) return 'expired';
  if (user.total_bytes > 0 && user.used_bytes >= user.total_bytes) return 'quota_exceeded';
  return 'active';
}

export function isUsable(user) {
  return statusOf(user) === 'active';
}

export function planTitleFor(user) {
  if (user.plan_title) return user.plan_title;
  if (!user.total_bytes || user.total_bytes <= 0) return 'Unlimited';
  const gb = Math.round((user.total_bytes / GB) * 100) / 100;
  return `${gb} GB`;
}

export function validateUsername(value) {
  return typeof value === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,31}$/.test(value.trim());
}

export function validatePassword(value) {
  return typeof value === 'string' && value.length >= 8 && value.length <= 128;
}

export function normalizeUsername(value) {
  return String(value || '').trim().toLowerCase();
}

export function timingSafeEqualString(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

export function httpError(status, message, code) {
  const err = new Error(message);
  err.status = status;
  if (code) err.code = code;
  return err;
}

export function parseJsonSafely(text) {
  const raw = String(text || '');
  const start = raw.indexOf('{');
  const end = raw.lastIndexOf('}');
  if (start === -1 || end === -1 || end < start) return null;
  try {
    return JSON.parse(raw.slice(start, end + 1));
  } catch {
    return null;
  }
}
