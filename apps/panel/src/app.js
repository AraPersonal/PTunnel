import express from 'express';
import net from 'node:net';

const startedAt = Date.now();

/** One TCP round trip to the core's admin API; the answer is all this needs to know. */
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

export function createApp(config, deps = {}) {
  const probe = deps.probe ?? tcpProbe;
  const app = express();
  app.disable('x-powered-by');
  app.use(express.json({ limit: '64kb' }));

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

  return app;
}
