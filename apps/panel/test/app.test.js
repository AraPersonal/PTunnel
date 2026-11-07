import { test } from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
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

const config = (over = {}) => ({ ...loadConfig({ PT_ENV: 'stage' }), ...over });

test('liveness answers without touching the core', async () => {
  await withServer(createApp(config()), async (base) => {
    const res = await fetch(`${base}/healthz`);
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { status: 'ok', service: 'panel', env: 'stage' });
  });
});

test('readiness fails when the core admin API is not answering', async () => {
  await withServer(createApp(config(), { probe: async () => false }), async (base) => {
    const res = await fetch(`${base}/readyz`);
    assert.equal(res.status, 503);
    const body = await res.json();
    assert.equal(body.ready, false);
    assert.equal(body.core, false);
  });
});

test('readiness passes when the core answers', async () => {
  await withServer(createApp(config(), { probe: async () => true }), async (base) => {
    const res = await fetch(`${base}/readyz`);
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ready: true, core: true, env: 'stage' });
  });
});

test('a stage box without an explicit environment never reports ready', async () => {
  // The default environment is 'dev'; on a real box that means someone forgot to set PT_ENV and the
  // container would otherwise look healthy while claiming no identity at all.
  const devConfig = { ...loadConfig({}), requireEnv: true };
  await withServer(createApp(devConfig, { probe: async () => true }), async (base) => {
    const res = await fetch(`${base}/readyz`);
    assert.equal(res.status, 503);
    assert.match((await res.json()).reason, /PT_ENV/);
  });
});

test('the real TCP probe distinguishes a listener from a closed port', async () => {
  const listener = net.createServer((socket) => socket.end());
  listener.listen(0, '127.0.0.1');
  await new Promise((resolve) => listener.once('listening', resolve));
  const openPort = listener.address().port;
  try {
    await withServer(createApp(config({ xrayApiHost: '127.0.0.1', xrayApiPort: openPort })), async (base) => {
      assert.equal((await fetch(`${base}/readyz`)).status, 200);
    });
    await withServer(createApp(config({ xrayApiHost: '127.0.0.1', xrayApiPort: 1 })), async (base) => {
      assert.equal((await fetch(`${base}/readyz`)).status, 503);
    });
  } finally {
    await new Promise((resolve) => listener.close(resolve));
  }
});

test('version reports the build it was given', async () => {
  await withServer(createApp(config({ buildVersion: '1.2.3', gitSha: 'abc1234' })), async (base) => {
    const body = await (await fetch(`${base}/version`)).json();
    assert.equal(body.version, '1.2.3');
    assert.equal(body.gitSha, 'abc1234');
    assert.equal(body.env, 'stage');
  });
});
