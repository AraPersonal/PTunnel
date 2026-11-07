import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';

async function withServer(app, fn) {
  const server = app.listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    return await fn(base);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

const testConfig = (over = {}) => ({
  ...loadConfig({ PT_ENV: 'dev', NODE_ENV: 'test' }),
  dbPath: ':memory:',
  jwtSecret: 'test-jwt-secret-xyz-1234567890',
  ...over,
});

test('auth: login succeeds with seeded user and returns valid token', async () => {
  await withServer(createApp(testConfig()), async (base) => {
    const res = await fetch(`${base}/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: 'test@ptunnel.net',
        password: 'password123',
      }),
    });

    assert.equal(res.status, 200);
    const data = await res.json();
    assert.equal(data.username, 'test@ptunnel.net');
    assert.ok(data.token);
    assert.ok(data.servers.length > 0);
  });
});

test('auth: login fails with wrong credentials', async () => {
  await withServer(createApp(testConfig()), async (base) => {
    const res = await fetch(`${base}/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: 'test@ptunnel.net',
        password: 'wrong_password',
      }),
    });

    assert.equal(res.status, 401);
  });
});

test('profile: authorized access returns profile, unauthorized rejected', async () => {
  await withServer(createApp(testConfig()), async (base) => {
    // 1. Login
    const loginRes = await fetch(`${base}/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'test@ptunnel.net', password: 'password123' }),
    });
    const { token } = await loginRes.json();

    // 2. Authorized profile fetch
    const profRes = await fetch(`${base}/v1/user/profile`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(profRes.status, 200);
    const profData = await profRes.json();
    assert.equal(profData.username, 'test@ptunnel.net');

    // 3. Unauthorized fetch
    const badRes = await fetch(`${base}/v1/user/profile`, {
      headers: { Authorization: 'Bearer bad_token' },
    });
    assert.equal(badRes.status, 401);
  });
});

test('traffic-sync: updates user consumed bytes', async () => {
  await withServer(createApp(testConfig()), async (base) => {
    const loginRes = await fetch(`${base}/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'test@ptunnel.net', password: 'password123' }),
    });
    const { token } = await loginRes.json();

    const syncRes = await fetch(`${base}/v1/user/traffic-sync`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        consumedSessionBytes: 1048576,
        activeNodeHost: '86.54.42.124',
      }),
    });

    assert.equal(syncRes.status, 200);
    const updated = await syncRes.json();
    assert.equal(updated.usedBytes, 1048576);
  });
});

test('client config: generates anti-throttling profile for Iran paths', async () => {
  await withServer(createApp(testConfig()), async (base) => {
    const loginRes = await fetch(`${base}/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'test@ptunnel.net', password: 'password123' }),
    });
    const { token } = await loginRes.json();

    const xrayRes = await fetch(`${base}/v1/client/xray?tag=multi`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    assert.equal(xrayRes.status, 200);
    const body = await xrayRes.json();
    assert.ok(body.config);

    // Verify critical Iran anti-throttling tuning
    const loopback = body.config.inbounds.find((i) => i.tag === 'loopback-proxy');
    assert.ok(loopback, 'Must provide loopback-proxy on 127.0.0.1 for unthrottled in-tunnel range pulls');

    const reality = body.config.outbounds.find((o) => o.tag === 'reality');
    assert.ok(reality, 'Reality outbound must exist');
    assert.equal(reality.streamSettings.sockopt.tcpNoDelay, true);
    assert.equal(reality.streamSettings.sockopt.tcpUserTimeout, 60000);
  });
});

test('download: supports HTTP 206 Partial Content for range-chunked updates', async () => {
  await withServer(createApp(testConfig()), async (base) => {
    // 512-byte range pull (mirrors Android client 512KB range requests)
    const rangeRes = await fetch(`${base}/dl`, {
      headers: { Range: 'bytes=0-511' },
    });
    assert.equal(rangeRes.status, 206);
    assert.match(rangeRes.headers.get('content-range'), /^bytes 0-511\//);
    const chunk = await rangeRes.arrayBuffer();
    assert.equal(chunk.byteLength, 512);
  });
});

test('bootstrap and reachability: respond with valid JSON schemas', async () => {
  await withServer(createApp(testConfig()), async (base) => {
    const bRes = await fetch(`${base}/b`);
    assert.equal(bRes.status, 200);
    const bData = await bRes.json();
    assert.ok(Array.isArray(bData.api));
    assert.ok(bData.pins);

    const reachRes = await fetch(`${base}/v1/reachability`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        network: '37.156.12',
        verdict: 'ok',
        port: 8443,
        box: 'de-1',
      }),
    });
    assert.equal(reachRes.status, 200);
    assert.deepEqual(await reachRes.json(), { ok: true });
  });
});
