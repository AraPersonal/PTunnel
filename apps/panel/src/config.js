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
    requireEnv: env.PT_ENV !== 'dev',

    // Database & Auth
    dbPath: env.PT_DB_PATH || (env.NODE_ENV === 'test' ? ':memory:' : 'data/ptunnel.db'),
    jwtSecret: env.PT_JWT_SECRET || 'ptunnel-default-development-secret-key-12345678',

    // Public Tunnel & Node settings
    publicHost: env.PT_PUBLIC_HOST || '86.54.42.124',
    publicDomain: env.PT_PUBLIC_DOMAIN || '86-54-42-124.nip.io',
    publicBaseUrl: env.PT_PUBLIC_BASE_URL || 'https://86-54-42-124.nip.io/v1/',
    realityPort: int(env.PT_REALITY_PORT, 8080),
    kcpPort: int(env.PT_KCP_PORT, 8443),
    realitySni: env.PT_REALITY_SNI || 'www.microsoft.com',
    realityPublicKey: env.PT_REALITY_PUBKEY || '2segwPqlMkdRtd5asC11tYkb1ZcsvUEDAZ8JsWhjqFY',
    realityShortId: env.PT_REALITY_SHORT_ID || '2a18498b611d7ac7',
    kcpSeed: env.PT_KCP_SEED || 'ptunnel-kcp-seed',
  };
}
