import { daysRemaining, planTitleFor, statusOf } from './util.js';

export function adminUserView(user) {
  const status = statusOf(user);
  const total = Number(user.total_bytes) || 0;
  const used = Number(user.used_bytes) || 0;

  return {
    uid: user.uid,
    username: user.username,
    displayName: user.display_name || user.username,
    status,
    isEnabled: Boolean(user.is_enabled),
    planTitle: planTitleFor(user),
    totalBytes: total,
    usedBytes: used,
    remainingBytes: total > 0 ? Math.max(0, total - used) : null,
    usagePercent: total > 0 ? Math.min(100, Math.round((used / total) * 1000) / 10) : null,
    expiresAt: user.expires_at || null,
    expiryDaysRemaining: daysRemaining(user.expires_at),
    onlineSessions: (Number(user.online_xray) || 0) + (Number(user.online_hysteria) || 0),
    lastSeenAt: user.last_seen_at || null,
    createdAt: user.created_at,
    updatedAt: user.updated_at,
  };
}

export function mapUserRow(user) {
  return adminUserView(user);
}
