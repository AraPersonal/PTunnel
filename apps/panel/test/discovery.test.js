import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { initDb, hashPassword } from '../src/db.js';
import { createIpIntel, parsePrefixes } from '../src/ipIntel.js';
import { configIdFor } from '../src/discovery.js';

// Documentation ASNs and invented org names: these are test lines, not claims about real Iranian prefixes.
const PREFIXES = [
  { prefix: '2.144.0.0/16', asn: 64501, org: 'Test Fixed ISP A', country: 'IR', region: 'tehran' },
  { prefix: '5.22.0.0/16', asn: 64502, org: 'Test Fixed ISP B', country: 'IR', region: 'isfahan' },
  { prefix: '188.114.96.0/24', asn: 64503, org: 'Test Foreign Edge', country: 'DE', region: '' },
];
const IR_IP = '2.144.5.6';
const IR_IP_B = '5.22.1.1';
const FOREIGN_IP = '188.114.96.3';

const PARAMS = { port: 8080, sni: 'www.microsoft.com', fingerprint: 'chrome' };
const CID = configIdFor(PARAMS);
const OPERATOR = 'm.432.70';
const NETWORK = 'c.432.70.tehran';

function makeDb() {
  const db = initDb(':memory:');
  const people = [
    ['admin@ptunnel.net', 'admin'],
    ['b@ptunnel.net', 'user'],
    ['c@ptunnel.net', 'user'],
    ['d@ptunnel.net', 'user'],
  ];
  for (const [username, role] of people) {
    db.prepare(`
      INSERT INTO users (uid, username, password_hash, subscription_token, vless_uuid, hysteria_password, role)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `).run(`usr_${username}`, username, hashPassword('password123'), `sub_${username}`, crypto.randomUUID(), `hy_${username}`, role);
  }
  return db;
}

async function withPanel(fn, opts = {}) {
  const db = makeDb();
  const config = {
    ...loadConfig({ PT_ENV: 'dev', NODE_ENV: 'test' }),
    dbPath: ':memory:',
    jwtSecret: 'test-jwt-secret-xyz-1234567890',
    adminUsernames: ['admin@ptunnel.net'],
    ...opts.config,
  };
  const ipIntel = createIpIntel({ ownIps: config.ownBoxIps, prefixes: parsePrefixes(opts.prefixes ?? PREFIXES) });
  const app = createApp(config, { db, ipIntel, serverEvidence: opts.serverEvidence ?? null });
  const server = app.listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const tokens = new Map();

  const call = async (method, url, { who, body, ip, headers } = {}) => {
    if (who && !tokens.has(who)) {
      const res = await fetch(`${base}/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: who, password: 'password123' }),
      });
      assert.equal(res.status, 200, `login for ${who}`);
      tokens.set(who, (await res.json()).token);
    }
    const res = await fetch(base + url, {
      method,
      headers: {
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(who ? { Authorization: `Bearer ${tokens.get(who)}` } : {}),
        ...(ip ? { 'X-Forwarded-For': ip } : {}),
        ...headers,
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = null;
    }
    return { status: res.status, body: json, headers: res.headers };
  };

  try {
    return await fn({ call, db, config, base });
  } finally {
    await new Promise((resolve) => server.close(resolve));
    db.close();
  }
}

const observeBody = (over = {}) => ({
  evidence: { mcc: 432, mnc: 70, sim_operator_name: 'شاتل موبایل' },
  operator_tag: 'shatel',
  client_view: { province_guess: 'Tehran' },
  ...over,
});

const reportBody = (over = {}) => ({
  probe_method: 'range-512k',
  operator_key: OPERATOR,
  network_key: NETWORK,
  config_id: CID,
  transport: 'reality',
  params: PARAMS,
  stability: { connected_minutes: 5, deaths: 0 },
  samples: [{ throughput_kBps: 900, latency_ms: 90, verdict: 'ok', bytes: 524288, duration_s: 0.6 }],
  ...over,
});

/** Three independent clients, two samples each: the locked K=3 / N=6 bar. */
async function crowdReports(call, { throughput = 900, verdict = 'ok', deaths = 0 } = {}) {
  const values = [throughput - 20, throughput, throughput + 10, throughput - 10, throughput + 5, throughput - 5];
  let i = 0;
  for (const who of ['test@ptunnel.net', 'b@ptunnel.net', 'c@ptunnel.net']) {
    const samples = [0, 1].map(() => ({
      throughput_kBps: values[i++], latency_ms: 90, verdict, bytes: 524288, duration_s: 0.6,
    }));
    const res = await call('POST', '/v1/presets/report', {
      who, ip: IR_IP, body: reportBody({ samples, stability: { connected_minutes: 5, deaths } }),
    });
    assert.equal(res.status, 200, JSON.stringify(res.body));
  }
}

test('a brand-new operator is registered by the client, with a numeric key and a non-canonical tag', async () => {
  await withPanel(async ({ call }) => {
    const res = await call('POST', '/v1/operators/observe', { who: 'b@ptunnel.net', ip: IR_IP, body: observeBody() });
    assert.equal(res.status, 200);
    assert.equal(res.body.operator_key, OPERATOR);
    assert.equal(res.body.network_key, NETWORK);
    assert.equal(res.body.status, 'provisional');
    // The client's own tag is a hint, never the canonical identity.
    assert.equal(res.body.operator_tag, `auto:${OPERATOR}`);
    assert.equal(res.body.display_name, 'شاتل موبایل');
    assert.equal(res.body.server_view.asn, 64501);
    assert.equal(res.body.confidence, 'medium');

    const list = await call('GET', '/v1/operators', { who: 'b@ptunnel.net' });
    assert.equal(list.status, 200);
    const row = list.body.operators.find((o) => o.operator_key === OPERATOR);
    assert.ok(row, 'the new operator must be visible to peers');
    assert.equal(row.clients, 1);
    assert.equal(row.source, 'client');
  });
});

test('observe refuses our own exit and refuses evidence-free claims', async () => {
  await withPanel(async ({ call, config }) => {
    const own = await call('POST', '/v1/operators/observe', {
      who: 'b@ptunnel.net', ip: config.publicHost, body: observeBody(),
    });
    assert.equal(own.status, 400);
    assert.equal(own.body.error, 'own_box_ip');

    const empty = await call('POST', '/v1/operators/observe', {
      who: 'b@ptunnel.net', ip: IR_IP, body: { evidence: {}, operator_key: 'whatever' },
    });
    assert.equal(empty.status, 400);
    assert.equal(empty.body.error, 'no_evidence');
  });
});

test('an operator is confirmed only after K_op independent clients, never after one client repeats', async () => {
  await withPanel(async ({ call }) => {
    for (let i = 0; i < 3; i += 1) {
      const again = await call('POST', '/v1/operators/observe', { who: 'b@ptunnel.net', ip: IR_IP, body: observeBody() });
      assert.equal(again.body.clients, 1);
      assert.equal(again.body.status, 'provisional');
    }
    await call('POST', '/v1/operators/observe', { who: 'c@ptunnel.net', ip: IR_IP, body: observeBody() });
    const third = await call('POST', '/v1/operators/observe', { who: 'd@ptunnel.net', ip: IR_IP, body: observeBody() });
    assert.equal(third.body.clients, 3);
    assert.equal(third.body.status, 'confirmed');
    assert.equal(third.body.confidence, 'high');
  });
});

test('a claim that contradicts the server view is flagged and never confirmed', async () => {
  await withPanel(async ({ call }) => {
    const foreign = await call('POST', '/v1/operators/observe', { who: 'b@ptunnel.net', ip: FOREIGN_IP, body: observeBody() });
    assert.equal(foreign.status, 200);
    assert.equal(foreign.body.mismatch, true);
    assert.equal(foreign.body.confidence, 'low');

    const wrongAsn = await call('POST', '/v1/operators/observe', {
      who: 'c@ptunnel.net', ip: IR_IP, body: observeBody({ evidence: { mcc: 432, mnc: 71, asn: 64502 } }),
    });
    assert.equal(wrongAsn.body.mismatch, true);

    await call('POST', '/v1/operators/observe', { who: 'd@ptunnel.net', ip: IR_IP, body: observeBody() });
    const list = await call('GET', '/v1/operators', { who: 'b@ptunnel.net' });
    const row = list.body.operators.find((o) => o.operator_key === OPERATOR);
    assert.equal(row.status, 'provisional', 'a mismatched operator must not reach confirmed');
  });
});

test('observe quotas bite per user and globally, but preset polling does not consume them', async () => {
  await withPanel(async ({ call }) => {
    await call('POST', '/v1/admin/settings', { who: 'admin@ptunnel.net', body: { observePerUidPerDay: 2, observeGlobalPerDay: 3 } });
    for (let i = 0; i < 2; i += 1) {
      assert.equal((await call('POST', '/v1/operators/observe', { who: 'b@ptunnel.net', ip: IR_IP, body: observeBody() })).status, 200);
    }
    assert.equal((await call('POST', '/v1/operators/observe', { who: 'b@ptunnel.net', ip: IR_IP, body: observeBody() })).status, 429);
    assert.equal((await call('POST', '/v1/operators/observe', { who: 'c@ptunnel.net', ip: IR_IP, body: observeBody() })).status, 200);
    assert.equal((await call('POST', '/v1/operators/observe', { who: 'd@ptunnel.net', ip: IR_IP, body: observeBody() })).status, 429);
  });
});

test('server-side discovery from preset polling is logged apart from the observe quota', async () => {
  await withPanel(async ({ call }) => {
    for (let i = 0; i < 12; i += 1) {
      const res = await call('GET', `/v1/presets?operator_key=a.${70000 + i}`, { who: 'b@ptunnel.net', ip: IR_IP });
      assert.equal(res.status, 200);
    }
    const observe = await call('POST', '/v1/operators/observe', { who: 'b@ptunnel.net', ip: IR_IP, body: observeBody() });
    assert.equal(observe.status, 200, 'polling presets must not exhaust the observe quota');
  });
});

test('login registers the line server-side, but only for a positively identified Iranian address', async () => {
  await withPanel(async ({ call, config }) => {
    const ir = await call('POST', '/v1/auth/login', {
      ip: IR_IP_B, body: { username: 'b@ptunnel.net', password: 'password123' },
    });
    assert.equal(ir.status, 200);
    const list = await call('GET', '/v1/operators', { who: 'admin@ptunnel.net' });
    const row = list.body.operators.find((o) => o.operator_key === 'a.64502');
    assert.ok(row, 'the fixed-line ASN must be registered from the server view');
    assert.equal(row.source, 'server');
    assert.equal(row.network_type, 'unknown');

    const before = list.body.operators.length;
    await call('POST', '/v1/auth/login', { ip: FOREIGN_IP, body: { username: 'c@ptunnel.net', password: 'password123' } });
    await call('POST', '/v1/auth/login', { ip: config.publicHost, body: { username: 'd@ptunnel.net', password: 'password123' } });
    const after = await call('GET', '/v1/operators', { who: 'admin@ptunnel.net' });
    assert.equal(after.body.operators.length, before, 'a foreign edge or our own exit is not an operator');
  });
});

test('reports are refused unless they carry a chunked probe and a stable connection', async () => {
  await withPanel(async ({ call }) => {
    const connectOnly = await call('POST', '/v1/presets/report', {
      who: 'b@ptunnel.net', ip: IR_IP, body: reportBody({ probe_method: 'connect-only' }),
    });
    assert.equal(connectOnly.status, 400);
    assert.equal(connectOnly.body.error, 'invalid_probe_method');

    const unstable = await call('POST', '/v1/presets/report', {
      who: 'b@ptunnel.net', ip: IR_IP, body: reportBody({ stability: { connected_minutes: 1, deaths: 0 } }),
    });
    assert.equal(unstable.status, 400);
    assert.equal(unstable.body.error, 'not_stable');

    const noSamples = await call('POST', '/v1/presets/report', { who: 'b@ptunnel.net', ip: IR_IP, body: reportBody({ samples: [] }) });
    assert.equal(noSamples.body.error, 'no_samples');

    const badSamples = await call('POST', '/v1/presets/report', {
      who: 'b@ptunnel.net', ip: IR_IP, body: reportBody({ samples: [{ verdict: 'ok' }] }),
    });
    assert.equal(badSamples.body.error, 'no_valid_samples');

    const unknownShape = await call('POST', '/v1/presets/report', {
      who: 'b@ptunnel.net', ip: IR_IP, body: reportBody({ config_id: 'deadbeefdeadbeef', params: undefined }),
    });
    assert.equal(unknownShape.body.error, 'unknown_config');

    const noNetwork = await call('POST', '/v1/presets/report', {
      who: 'b@ptunnel.net', ip: IR_IP, body: reportBody({ operator_key: '', network_key: '', evidence: {} }),
    });
    assert.equal(noNetwork.body.error, 'no_network');
  });
});

test('config identity is the shape without secrets, and secrets never reach peers', async () => {
  await withPanel(async ({ call }) => {
    const withSecrets = { ...PARAMS, uuid: '11111111-2222-3333-4444-555555555555', password: 'hunter2' };
    assert.equal(configIdFor(withSecrets), CID, 'secrets must not change the config identity');

    const res = await call('POST', '/v1/presets/report', {
      who: 'b@ptunnel.net', ip: IR_IP, body: reportBody({ config_id: '', params: withSecrets }),
    });
    assert.equal(res.status, 200);
    assert.equal(res.body.config_id, CID);

    const presets = await call('GET', `/v1/presets?operator_key=${OPERATOR}&network_key=${NETWORK}`, { who: 'c@ptunnel.net', ip: IR_IP });
    const served = presets.body.candidates.find((c) => c.config_id === CID);
    assert.ok(served);
    assert.equal(served.params.uuid, undefined);
    assert.equal(served.params.password, undefined);
    assert.equal(served.params.port, PARAMS.port);
  });
});

test('a crowd row stays candidate until the server corroborates the bytes', async () => {
  await withPanel(async ({ call }) => {
    await crowdReports(call);
    const presets = await call('GET', `/v1/presets?operator_key=${OPERATOR}&network_key=${NETWORK}`, { who: 'd@ptunnel.net', ip: IR_IP });
    const row = presets.body.candidates.find((c) => c.config_id === CID);
    assert.equal(row.status, 'candidate');
    assert.equal(presets.body.candidates.some((c) => c.status === 'validated'), false);
  });
});

test('with server corroboration the same measurements validate, and one loud outlier is dropped', async () => {
  await withPanel(async ({ call }) => {
    await crowdReports(call);
    const loud = await call('POST', '/v1/presets/report', {
      who: 'd@ptunnel.net', ip: IR_IP,
      body: reportBody({ samples: [{ throughput_kBps: 100000, latency_ms: 5, verdict: 'ok' }] }),
    });
    assert.equal(loud.status, 200);
    assert.equal(loud.body.validation.status, 'validated');
    assert.equal(loud.body.validation.samples, 6, 'the 100 MB/s claim is a MAD outlier, not a measurement');
    assert.equal(loud.body.validation.clients, 3, 'a client whose only sample is rejected does not count');
    assert.ok(Math.abs(loud.body.validation.p50_throughput_kBps - 900) < 25);
    assert.equal(loud.body.validation.server_corroborated, true);
  }, { serverEvidence: () => true });
});

test('fresh measurements that contradict a row retire it and hide it from peers', async () => {
  await withPanel(async ({ call }) => {
    await crowdReports(call);
    const dead = await call('POST', '/v1/presets/report', {
      who: 'd@ptunnel.net', ip: IR_IP,
      body: reportBody({
        samples: [
          { throughput_kBps: 5, verdict: 'dead' },
          { throughput_kBps: 4, verdict: 'dead' },
          { throughput_kBps: 3, verdict: 'fail' },
        ],
        stability: { connected_minutes: 5, deaths: 6 },
      }),
    });
    assert.equal(dead.body.validation.status, 'retired');
    const presets = await call('GET', `/v1/presets?operator_key=${OPERATOR}&network_key=${NETWORK}`, { who: 'b@ptunnel.net', ip: IR_IP });
    assert.equal(presets.body.candidates.some((c) => c.config_id === CID), false);
  });
});

test('a validated row nobody re-measured decays back to candidate', async () => {
  await withPanel(async ({ call, db }) => {
    await crowdReports(call);
    db.prepare("UPDATE preset_validations SET status = 'validated', last_seen = ? WHERE config_id = ?")
      .run(new Date(Date.now() - 20 * 86400000).toISOString(), CID);
    const presets = await call('GET', `/v1/presets?operator_key=${OPERATOR}&network_key=${NETWORK}`, { who: 'b@ptunnel.net', ip: IR_IP });
    const row = presets.body.candidates.find((c) => c.config_id === CID);
    assert.equal(row.status, 'candidate');
  }, { serverEvidence: () => true });
});

test('admin validation outranks the crowd cap, and crowd noise cannot undo an admin decision', async () => {
  await withPanel(async ({ call }) => {
    const validated = await call('POST', '/v1/admin/presets/validate', {
      who: 'admin@ptunnel.net', body: { operator_key: OPERATOR, network_key: NETWORK, config_id: CID },
    });
    assert.equal(validated.status, 200);
    assert.equal(validated.body.status, 'validated');

    await crowdReports(call, { throughput: 8, verdict: 'dead', deaths: 3 });
    const presets = await call('GET', `/v1/presets?operator_key=${OPERATOR}&network_key=${NETWORK}`, { who: 'b@ptunnel.net', ip: IR_IP });
    assert.equal(presets.body.candidates[0].config_id, CID);
    assert.equal(presets.body.candidates[0].status, 'validated');

    await call('POST', '/v1/admin/presets/retire', {
      who: 'admin@ptunnel.net', body: { operator_key: OPERATOR, network_key: NETWORK, config_id: CID },
    });
    await crowdReports(call);
    const after = await call('GET', `/v1/presets?operator_key=${OPERATOR}&network_key=${NETWORK}`, { who: 'b@ptunnel.net', ip: IR_IP });
    assert.equal(after.body.candidates.some((c) => c.config_id === CID), false, 'an admin retirement stands');
  });
});

test('admin routes need the admin role, and every admin action is audited', async () => {
  await withPanel(async ({ call }) => {
    assert.equal((await call('GET', '/v1/presets', {})).status, 401);
    assert.equal((await call('POST', '/v1/admin/operators', { who: 'b@ptunnel.net', body: { operator_key: 'a.64501' } })).status, 403);
    assert.equal((await call('GET', '/v1/admin/audit', { who: 'b@ptunnel.net' })).status, 403);

    await call('POST', '/v1/admin/operators', {
      who: 'admin@ptunnel.net',
      body: { operator_key: 'a.64501', operator_tag: 'shatel-fixed', display_name: 'شاتل ثابت', network_type: 'fixed' },
    });
    const audit = await call('GET', '/v1/admin/audit', { who: 'admin@ptunnel.net' });
    assert.equal(audit.status, 200);
    assert.ok(audit.body.audit.some((a) => a.action === 'operator_upsert' && a.actor === 'admin@ptunnel.net'));
  });
});

test('a client cannot overwrite an admin-set identity', async () => {
  await withPanel(async ({ call }) => {
    await call('POST', '/v1/admin/operators', {
      who: 'admin@ptunnel.net',
      body: { operator_key: 'a.64501', operator_tag: 'shatel-fixed', display_name: 'شاتل ثابت', network_type: 'fixed' },
    });
    const observe = await call('POST', '/v1/operators/observe', {
      who: 'b@ptunnel.net', ip: IR_IP, body: { evidence: { asn: 64501 }, operator_tag: 'hacked', suggested_label: 'Not Shatel' },
    });
    assert.equal(observe.status, 200);
    assert.equal(observe.body.operator_tag, 'shatel-fixed');
    assert.equal(observe.body.display_name, 'شاتل ثابت');
    assert.equal(observe.body.status, 'confirmed');
  });
});

test('merge keeps caches alive through aliases and moves the validation rows', async () => {
  await withPanel(async ({ call }) => {
    for (const key of ['a.64501', 'a.64509']) {
      await call('POST', '/v1/admin/operators', { who: 'admin@ptunnel.net', body: { operator_key: key, network_type: 'fixed' } });
    }
    await call('POST', '/v1/admin/presets/validate', {
      who: 'admin@ptunnel.net', body: { operator_key: 'a.64509', network_key: 'f.64509.5.22.1', config_id: CID },
    });
    const merged = await call('POST', '/v1/admin/operators/merge', {
      who: 'admin@ptunnel.net', body: { from_key: 'a.64509', into_key: 'a.64501' },
    });
    assert.equal(merged.status, 200);
    assert.ok(merged.body.operator.aliases.includes('a.64509'));

    const list = await call('GET', '/v1/operators', { who: 'admin@ptunnel.net' });
    assert.equal(list.body.operators.some((o) => o.operator_key === 'a.64509'), false);

    const presets = await call('GET', '/v1/presets?operator_key=a.64509', { who: 'b@ptunnel.net', ip: IR_IP_B });
    assert.equal(presets.body.operator_key, 'a.64501');
    assert.ok(presets.body.candidates.some((c) => c.config_id === CID && c.status === 'validated'));

    const self = await call('POST', '/v1/admin/operators/merge', {
      who: 'admin@ptunnel.net', body: { from_key: 'a.64501', into_key: 'a.64501' },
    });
    assert.equal(self.status, 400);
  });
});

test('rename changes the label, never the key', async () => {
  await withPanel(async ({ call }) => {
    await call('POST', '/v1/operators/observe', { who: 'b@ptunnel.net', ip: IR_IP, body: observeBody() });
    const renamed = await call('POST', '/v1/admin/operators/rename', {
      who: 'admin@ptunnel.net', body: { operator_key: OPERATOR, operator_tag: 'shatel-mobile', display_name: 'شاتل موبایل' },
    });
    assert.equal(renamed.status, 200);
    assert.equal(renamed.body.operator.operator_key, OPERATOR);
    assert.equal(renamed.body.operator.operator_tag, 'shatel-mobile');

    const presets = await call('GET', '/v1/presets?operator_tag=shatel-mobile', { who: 'c@ptunnel.net', ip: IR_IP });
    assert.equal(presets.body.operator_key, OPERATOR);
  });
});

test('a freshly registered network is served seeds, explicitly unvalidated', async () => {
  await withPanel(async ({ call }) => {
    const observe = await call('POST', '/v1/operators/observe', { who: 'b@ptunnel.net', ip: IR_IP, body: observeBody() });
    assert.ok(observe.body.presets_hint.network_type_seeds >= 2);

    const presets = await call('GET', `/v1/presets?operator_key=${OPERATOR}&network_key=${NETWORK}&network_type=cellular`, {
      who: 'b@ptunnel.net', ip: IR_IP,
    });
    assert.equal(presets.body.operator_status, 'provisional');
    const seeds = presets.body.candidates.filter((c) => c.status === 'seed');
    assert.equal(seeds.length, 2);
    assert.deepEqual(seeds.map((s) => s.transport).sort(), ['kcp', 'reality']);
    assert.equal(presets.body.candidates.some((c) => c.status === 'validated'), false);
  });
});

test('validated rows rank above candidates, and exact network above operator level', async () => {
  await withPanel(async ({ call }) => {
    const otherShape = { ...PARAMS, port: 9999 };
    const other = configIdFor(otherShape);
    const registered = await call('POST', '/v1/admin/presets/validate', {
      who: 'admin@ptunnel.net',
      body: { operator_key: OPERATOR, network_key: 'c.432.70.isfahan', config_id: '', transport: 'reality', params: otherShape },
    });
    assert.equal(registered.status, 200);
    assert.equal(registered.body.config_id, other);
    await call('POST', '/v1/admin/presets/validate', {
      who: 'admin@ptunnel.net', body: { operator_key: OPERATOR, network_key: NETWORK, config_id: CID },
    });
    await crowdReports(call);
    const presets = await call('GET', `/v1/presets?operator_key=${OPERATOR}&network_key=${NETWORK}`, { who: 'd@ptunnel.net', ip: IR_IP });
    assert.equal(presets.body.candidates[0].config_id, CID);
    assert.equal(presets.body.candidates[0].priority, 1);
    assert.equal(presets.body.candidates[1].config_id, other);
  });
});

test('the operator list carries an ETag and answers 304 when nothing changed', async () => {
  await withPanel(async ({ call }) => {
    const first = await call('GET', '/v1/operators', { who: 'b@ptunnel.net' });
    const etag = first.headers.get('etag');
    assert.ok(etag);
    const second = await call('GET', '/v1/operators', { who: 'b@ptunnel.net', headers: { 'If-None-Match': etag } });
    assert.equal(second.status, 304);
  });
});

test('domestic rules start at .ir and only accept suffix-shaped additions', async () => {
  await withPanel(async ({ call }) => {
    const first = await call('GET', '/v1/operators', { who: 'b@ptunnel.net' });
    assert.ok(first.body.domestic.suffixes.includes('.ir'));
    assert.equal(first.body.domestic.geoip, 'IR');

    const added = await call('POST', '/v1/admin/domestic', { who: 'admin@ptunnel.net', body: { suffixes: ['.co.ir', 'ir', 'x', '.IR'] } });
    assert.ok(added.body.added.includes('.co.ir'));
    assert.equal(added.body.added.includes('ir'), false, 'a bare label is not a DNS suffix');
    assert.equal(added.body.added.includes('x'), false);
    const after = await call('GET', '/v1/operators', { who: 'b@ptunnel.net' });
    assert.ok(after.body.domestic.suffixes.includes('.co.ir'));
    assert.equal(after.body.domestic.suffixes.filter((s) => s === '.ir').length, 1);
  });
});

test('thresholds are runtime settings, and nonsense values are refused', async () => {
  await withPanel(async ({ call }) => {
    const res = await call('POST', '/v1/admin/settings', { who: 'admin@ptunnel.net', body: { K: 0, D: 5, T_kBps: 100, N: -3 } });
    assert.equal(res.status, 200);
    assert.equal(res.body.settings.K, 3);
    assert.equal(res.body.settings.D, 0.1);
    assert.equal(res.body.settings.N, 6);
    assert.equal(res.body.settings.T_kBps, 100);

    await crowdReports(call, { throughput: 150 });
    const presets = await call('GET', `/v1/presets?operator_key=${OPERATOR}&network_key=${NETWORK}`, { who: 'd@ptunnel.net', ip: IR_IP });
    assert.equal(presets.body.candidates.find((c) => c.config_id === CID).status, 'validated');
  }, { serverEvidence: () => true });
});

test('the candidate list is capped so a client never receives an endless scan list', async () => {
  await withPanel(async ({ call }) => {
    await call('POST', '/v1/admin/settings', { who: 'admin@ptunnel.net', body: { maxCandidates: 3 } });
    for (let i = 0; i < 6; i += 1) {
      await call('POST', '/v1/admin/presets/validate', {
        who: 'admin@ptunnel.net',
        body: { operator_key: OPERATOR, network_key: NETWORK, config_id: '', transport: 'reality', params: { ...PARAMS, port: 9000 + i } },
      });
    }
    const presets = await call('GET', `/v1/presets?operator_key=${OPERATOR}&network_key=${NETWORK}`, { who: 'b@ptunnel.net', ip: IR_IP });
    assert.equal(presets.body.candidates.length, 3);
  });
});

test('configured admin usernames are promoted on an existing database', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ptunnel-role-'));
  const file = path.join(dir, 'panel.db');
  try {
    const first = initDb(file);
    first.prepare(`
      INSERT INTO users (uid, username, password_hash, subscription_token, vless_uuid, hysteria_password)
      VALUES ('usr_admin', 'admin@ptunnel.net', ?, 'sub_admin', ?, 'hy_admin')
    `).run(hashPassword('password123'), crypto.randomUUID());
    assert.equal(first.prepare("SELECT role FROM users WHERE username = 'admin@ptunnel.net'").get().role, 'user');
    first.close();

    const second = initDb(file, { adminUsernames: ['ADMIN@ptunnel.net'] });
    assert.equal(second.prepare("SELECT role FROM users WHERE username = 'admin@ptunnel.net'").get().role, 'admin');
    second.close();
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});