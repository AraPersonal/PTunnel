// Every knob the panel reads, in one place, so a container needs nothing but environment variables.
const int = (value, fallback) => {
  const n = Number.parseInt(value ?? '', 10);
  return Number.isFinite(n) ? n : fallback;
};

const num = (value, fallback) => {
  const n = Number.parseFloat(value ?? '');
  return Number.isFinite(n) ? n : fallback;
};

const list = (value) => String(value ?? '').split(',').map((s) => s.trim()).filter(Boolean);

export function loadConfig(env = process.env) {
  const publicHost = env.PT_PUBLIC_HOST || '86.54.42.124';
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
    publicHost,
    publicDomain: env.PT_PUBLIC_DOMAIN || '86-54-42-124.nip.io',
    publicBaseUrl: env.PT_PUBLIC_BASE_URL || 'https://86-54-42-124.nip.io/v1/',
    realityPort: int(env.PT_REALITY_PORT, 8080),
    kcpPort: int(env.PT_KCP_PORT, 8443),
    realitySni: env.PT_REALITY_SNI || 'www.microsoft.com',
    realityPublicKey: env.PT_REALITY_PUBKEY || '2segwPqlMkdRtd5asC11tYkb1ZcsvUEDAZ8JsWhjqFY',
    realityShortId: env.PT_REALITY_SHORT_ID || '2a18498b611d7ac7',
    kcpSeed: env.PT_KCP_SEED || 'ptunnel-kcp-seed',

    // Network discovery & the open operator registry (docs/design/0001).
    // Thresholds are the locked defaults; the panel can override them at runtime via preset_settings.
    adminUsernames: list(env.PT_ADMIN_USERNAMES),
    trustProxy: env.PT_TRUST_PROXY || 'loopback',
    // Our own exits must never be mistaken for a user's Iranian line.
    ownBoxIps: [...list(env.PT_OWN_BOX_IPS), publicHost],
    asnDbPath: env.PT_ASN_DB_PATH || '',
    discovery: {
      K: int(env.PT_PRESET_K, 3),
      N: int(env.PT_PRESET_N, 6),
      T_kBps: int(env.PT_PRESET_T_KBPS, 500),
      D: num(env.PT_PRESET_D, 0.1),
      R_days: int(env.PT_PRESET_R_DAYS, 14),
      K_op: int(env.PT_PRESET_K_OP, 3),
      T_stable_min: num(env.PT_PRESET_T_STABLE_MIN, 3),
      observePerUidPerDay: int(env.PT_OBSERVE_PER_UID_DAY, 10),
      observeGlobalPerDay: int(env.PT_OBSERVE_GLOBAL_DAY, 500),
      reportPerUidPerHour: int(env.PT_REPORT_PER_UID_HOUR, 30),
      maxCandidates: int(env.PT_PRESET_MAX_CANDIDATES, 12),
    },
  };
}
