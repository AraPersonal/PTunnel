import { createApp } from './app.js';
import { loadConfig } from './config.js';

const config = loadConfig();
const server = createApp(config).listen(config.port, config.bindHost, () => {
  console.log(`[panel] ${config.service} ${config.buildVersion} (${config.gitSha}) env=${config.environment} listening on ${config.bindHost}:${config.port}`);
});

// Containers get SIGTERM and a grace period; close the listener so orchestrators see a clean exit.
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
