import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { initDb, hashPassword } from '../src/db.js';
import { createIpIntel } from '../src/ipIntel.js';

function makeDb() {
  const db = initDb(':memory:');
  const people = [
    ['admin@ptunnel.net', 'admin'],
    ['b@ptunnel.net', 'user'],
  ];
  for (const [username, role] of people) {
    db.prepare(`
      INSERT INTO users (uid, username, password_hash, subscription_token, vless_uuid, hysteria_password, role, used_bytes, total_bytes, channel)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      `usr_${username}`,
      username,
      hashPassword('password123'),
      `sub_${username}`,
      crypto.randomUUID(),
      `hy_${username}`,
      role,
      1048576,
      53687091200,
      'prod'
    );
  }
  return db;
}

async function withPanel(fn) {
  const db = makeDb();
  const config = {
    ...loadConfig({ PT_ENV: 'dev', NODE_ENV: 'test' }),
    dbPath: ':memory:',
    jwtSecret: 'test-jwt-secret-xyz-1234567890',
    adminUsernames: ['admin@ptunnel.net'],
  };
  const ipIntel = createIpIntel({ ownIps: config.ownBoxIps, prefixes: [] });
  const app = createApp(config, { db, ipIntel });
  const server = app.listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const tokens = new Map();

  const call = async (method, url, { who, body, headers } = {}) => {
    if (who && !tokens.has(who)) {
      const res = await fetch(`${base}/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: who, password: 'password123' }),
      });
      assert.equal(res.status, 200, `login for ${who}`);
      tokens.set(who, (await res.json()).token);
    }
    const h = { 'Content-Type': 'application/json', ...(headers || {}) };
    if (who) h['Authorization'] = `Bearer ${tokens.get(who)}`;
    return fetch(`${base}${url}`, {
      method,
      headers: h,
      body: body ? JSON.stringify(body) : undefined,
    });
  };

  try {
    await fn({ call, db, base });
  } finally {
    server.close();
  }
}

test('housekeeping archives unobserved provisional operator rows after R=14 days', async () => {
  await withPanel(async ({ call, db }) => {
    const twentyDaysAgo = new Date(Date.now() - 20 * 86400000).toISOString();
    const oneDayAgo = new Date(Date.now() - 1 * 86400000).toISOString();

    // 1. Old provisional operator (should be archived)
    db.prepare(`
      INSERT INTO operators (operator_key, operator_tag, display_name, status, source, first_seen, last_seen)
      VALUES (?, ?, ?, 'provisional', 'client', ?, ?)
    `).run('m.999.01', 'auto:m.999.01', 'Old Provisional Carrier', twentyDaysAgo, twentyDaysAgo);

    // 2. Recent provisional operator (should stay provisional)
    db.prepare(`
      INSERT INTO operators (operator_key, operator_tag, display_name, status, source, first_seen, last_seen)
      VALUES (?, ?, ?, 'provisional', 'client', ?, ?)
    `).run('m.999.02', 'auto:m.999.02', 'Fresh Provisional Carrier', oneDayAgo, oneDayAgo);

    // 3. Old confirmed operator (should stay confirmed)
    db.prepare(`
      INSERT INTO operators (operator_key, operator_tag, display_name, status, source, first_seen, last_seen)
      VALUES (?, ?, ?, 'confirmed', 'client', ?, ?)
    `).run('m.999.03', 'mci', 'Confirmed Carrier', twentyDaysAgo, twentyDaysAgo);

    // 4. Old admin-created provisional operator (admin source protected from auto-archive)
    db.prepare(`
      INSERT INTO operators (operator_key, operator_tag, display_name, status, source, first_seen, last_seen)
      VALUES (?, ?, ?, 'provisional', 'admin', ?, ?)
    `).run('m.999.04', 'admin-carrier', 'Admin Provisional', twentyDaysAgo, twentyDaysAgo);

    // Trigger housekeeping by fetching operators
    const res = await call('GET', '/v1/operators', { who: 'admin@ptunnel.net' });
    assert.equal(res.status, 200);
    const body = await res.json();
    const keys = body.operators.map((o) => o.operator_key);

    // Verify database statuses
    const op1 = db.prepare('SELECT status FROM operators WHERE operator_key = ?').get('m.999.01');
    assert.equal(op1.status, 'archived', 'unobserved provisional row after R days must be archived');

    const op2 = db.prepare('SELECT status FROM operators WHERE operator_key = ?').get('m.999.02');
    assert.equal(op2.status, 'provisional', 'recent provisional row must stay provisional');

    const op3 = db.prepare('SELECT status FROM operators WHERE operator_key = ?').get('m.999.03');
    assert.equal(op3.status, 'confirmed', 'confirmed row must not be archived');

    const op4 = db.prepare('SELECT status FROM operators WHERE operator_key = ?').get('m.999.04');
    assert.equal(op4.status, 'provisional', 'admin-defined operator must not be auto-archived');

    // Verify archived row is filtered out of public operators list
    assert.ok(!keys.includes('m.999.01'), 'archived operator must not appear in /v1/operators');
    assert.ok(keys.includes('m.999.02'), 'active provisional operator must appear in /v1/operators');
  });
});

test('admin web interface is served at /admin', async () => {
  await withPanel(async ({ base }) => {
    const res = await fetch(`${base}/admin`);
    assert.equal(res.status, 200);
    const html = await res.text();
    assert.ok(html.includes('PTunnel Admin Console'));
    assert.ok(html.includes('Core Admin Console'));
  });
});

test('admin user management: list users and switch release channel', async () => {
  await withPanel(async ({ call, db }) => {
    // Non-admin cannot access admin users
    const forbiddenRes = await call('GET', '/v1/admin/users', { who: 'b@ptunnel.net' });
    assert.equal(forbiddenRes.status, 403);

    // Admin can list users
    const adminRes = await call('GET', '/v1/admin/users', { who: 'admin@ptunnel.net' });
    assert.equal(adminRes.status, 200);
    const usersBody = await adminRes.json();
    assert.ok(Array.isArray(usersBody.users));
    const targetUser = usersBody.users.find((u) => u.username === 'b@ptunnel.net');
    assert.ok(targetUser);
    assert.equal(targetUser.channel, 'prod');

    // Admin switches channel prod -> beta
    const updateRes = await call('POST', '/v1/admin/users/channel', {
      who: 'admin@ptunnel.net',
      body: { uid: targetUser.uid, channel: 'beta' },
    });
    assert.equal(updateRes.status, 200);
    const updateBody = await updateRes.json();
    assert.equal(updateBody.ok, true);
    assert.equal(updateBody.channel, 'beta');

    // Verify database
    const freshUser = db.prepare('SELECT channel FROM users WHERE uid = ?').get(targetUser.uid);
    assert.equal(freshUser.channel, 'beta');

    // Invalid channel rejected
    const badRes = await call('POST', '/v1/admin/users/channel', {
      who: 'admin@ptunnel.net',
      body: { uid: targetUser.uid, channel: 'canary' },
    });
    assert.equal(badRes.status, 400);
  });
});

test('admin presets list, manual housekeeping trigger, and audit logging', async () => {
  await withPanel(async ({ call, db }) => {
    // Presets list
    const presetsRes = await call('GET', '/v1/admin/presets/all', { who: 'admin@ptunnel.net' });
    assert.equal(presetsRes.status, 200);
    const presetsBody = await presetsRes.json();
    assert.ok(Array.isArray(presetsBody.configs));
    assert.ok(Array.isArray(presetsBody.validations));

    // Manual housekeeping
    const houseRes = await call('POST', '/v1/admin/housekeeping', { who: 'admin@ptunnel.net', body: {} });
    assert.equal(houseRes.status, 200);
    const houseBody = await houseRes.json();
    assert.equal(houseBody.ok, true);
    assert.ok('archived_operators' in houseBody);
    assert.ok('decayed_validations' in houseBody);

    // Verify audit log has the event
    const auditRes = await call('GET', '/v1/admin/audit', { who: 'admin@ptunnel.net' });
    assert.equal(auditRes.status, 200);
    const auditBody = await auditRes.json();
    const event = auditBody.audit.find((a) => a.action === 'housekeeping_run');
    assert.ok(event, 'audit must record housekeeping_run');
    assert.equal(event.actor, 'admin@ptunnel.net');
  });
});

test('geo canary endpoint returns enhanced sanctions verdict and fields', async () => {
  await withPanel(async ({ call }) => {
    const res = await call('GET', '/geo');
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok('google_204' in body);
    assert.ok('gemini_status' in body);
    assert.ok('alkali_status' in body);
    assert.ok('aistudio_status' in body);
    assert.ok('chatgpt_status' in body);
    assert.ok('sanctions_bypassed' in body);
    assert.ok('verdict' in body);
    assert.ok(body.timestamp);
  });
});
