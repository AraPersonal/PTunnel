// Every knob the panel reads, in one place, so a container needs nothing but environment variables.
const int = (value, fallback) => {
  const n = Number.parseInt(value ?? '', 10);
  return Number.isFinite(n) ? n : fallback;
};

export function loadConfig(env = process.env) {
  return {
    service: env.PT_SERVICE || 'panel',
    environment: env.PT_ENV || 'dev',
    port: int(env.PT_PORT, 8080),
    // Bound to loopback: the panel sits behind the host's reverse proxy and is never published.
    bindHost: env.PT_BIND_HOST || '127.0.0.1',
    gitSha: env.PT_GIT_SHA || 'unknown',
    buildVersion: env.PT_BUILD_VERSION || '0.0.0',
    // Where the core's admin API lives. host network mode means 127.0.0.1 in every environment.
    xrayApiHost: env.PT_XRAY_API_HOST || '127.0.0.1',
    xrayApiPort: int(env.PT_XRAY_API_PORT, 10085),
    // The panel refuses to report ready in a non-dev environment without these being explicit,
    // because a stage box that silently shares prod's identity is worse than a stage box that is down.
    requireEnv: env.PT_ENV !== 'dev',
  };
}
