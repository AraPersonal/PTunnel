import 'dotenv/config';

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function optional(name, fallback) {
  return process.env[name] ?? fallback;
}

export const config = {
  port: Number(optional('PORT', 3000)),
  dbPath: optional('DB_PATH', '/opt/ptunnel/data/ptunnel.db'),
  domain: optional('PUBLIC_DOMAIN', '86-54-42-124.sslip.io'),
  host: optional('PUBLIC_HOST', '86.54.42.124'),
  publicBaseUrl: optional('PUBLIC_BASE_URL', 'https://86-54-42-124.sslip.io:8443'),

  userJwtSecret: required('USER_JWT_SECRET'),
  adminJwtSecret: required('ADMIN_JWT_SECRET'),
  internalSecret: required('INTERNAL_SECRET'),

  xrayApiAddr: optional('XRAY_API_ADDR', '127.0.0.1:10085'),
  xrayBin: optional('XRAY_BIN', '/usr/local/bin/xray'),
  xrayTags: {
    vless: 'vless-reality',
    vmess: 'vmess-ws-tls',
    trojan: 'trojan-tls',
    ss: 'ss-2022',
    kcp: 'kcp-salamander',
  },

  hysteriaStatsUrl: optional('HYSTERIA_STATS_URL', 'http://127.0.0.1:9999'),
  hysteriaStatsSecret: required('HYSTERIA_STATS_SECRET'),

  ssMethod: optional('SS_METHOD', '2022-blake3-aes-128-gcm'),
  ssServerKey: required('SS_SERVER_KEY'),

  reality: {
    publicKey: required('REALITY_PUBLIC_KEY'),
    shortId: required('REALITY_SHORT_ID'),
    sni: optional('REALITY_SNI', 'www.microsoft.com'),
  },

  ports: {
    /**
     * Public port of the Reality tunnel: 8080, not 443.
     *
     * Measured on an Iranian line (Tehran, 2026-11-04) against this server's own IP: a 4 MB download
     * through the finished Reality tunnel on 443 held 7.2 KB/s, while the identical Reality handshake
     * on 8082 — same decoy SNI, same keys, same client — held 2.16 MB/s. The port was the only
     * variable, and the SNI string was the other one: TLS with an `*.sslip.io`/`*.nip.io` SNI crawled
     * at 3.4 KB/s on 8080 too. So the filter polices this IP's 443 and those name suffixes anywhere;
     * the tunnel needs a decoy SNI *and* a non-443 port to be fast. nginx's stream `default` arm
     * still forwards 443 into this inbound, so a client that has not refetched its profile keeps
     * working — on the throttled path — instead of failing outright.
     */
    vless: Number(optional('REALITY_PUBLIC_PORT', '8080')),
    vmess: 2083,
    trojan: 2053,
    ss: 8388,
    hysteria: 443,
    kcp: 8443,
  },

  /** FinalMask(salamander) shared secret for the mKCP inbound: both ends must match. */
  kcpSalamanderPassword: required('KCP_SALAMANDER_PASSWORD'),

  /** Cloudflare-fronted hostname: the fallback path used when our own IP is blocked. */
  cdnHost: optional('CDN_HOST', 'cdn.ptu.beauty'),

  /**
   * SNI the TLS tunnel transports advertise. Defaults to PUBLIC_DOMAIN.
   *
   * Kept separate because the name is filterable: on a measured Iranian line every SNI ending in
   * the public domain is reset during the ClientHello, while the sslip.io name in the same
   * certificate passes. Subscription links and the panel keep using PUBLIC_DOMAIN.
   */
  tunnelSni: optional('TUNNEL_SNI', ''),

  /**
   * Accounts whose tunnel outbounds ask for `tcpCongestion: bbr`.
   *
   * A kernel that does not know BBR makes the outbound fail to open, and this profile is handed to
   * every user at once, so the option is proven on a named account first. Empty list = nobody.
   */
  bbrTestUsers: (optional('BBR_TEST_USERS', '') || '')
    .split(',')
    .map((name) => name.trim())
    .filter(Boolean),

  /**
   * Second-box topology.
   *
   * The accounts DB has exactly one writer: box #1. A replica measures the bytes that crossed its
   * own cores and posts them here, because the replica's copy of the database is replaced by the
   * next snapshot and a tally kept only there would be thrown away. Empty = this box is the primary.
   */
  usagePrimaryUrl: optional('USAGE_PRIMARY_URL', ''),
  boxId: optional('BOX_ID', 'primary'),

  /**
   * Where a client should send its API calls to reach *this* box, published in the location list so
   * the app can talk to a chosen box directly instead of only to whichever hostname it booted with.
   */
  /**
   * What the observatory fetches to rank the transports.
   *
   * It used to be `https://www.gstatic.com/generate_204` — a few hundred bytes, which is *less* than
   * the burst allowance the censor grants a flow before throttling it. A transport crawling at 6.5 KB/s
   * therefore answers that probe faster than one delivering 1.1 MB/s, and `leastPing` parks every user
   * on the suffocating path. Measured in a single window on 2026-11-04: reality 1.12 MB/s, kcp
   * 1.66 MB/s, trojan 6.5 KB/s, vmess 5.7 KB/s — while the probe said the last two were the winners.
   * A payload probe has to cross the allowance to answer at all, so the ranking becomes throughput.
   * Empty means: derive it from the tunnel SNI, which always resolves.
   */
  rateProbeUrl: optional('RATE_PROBE_URL', ''),

  publicApiBase: optional('PUBLIC_API_BASE', ''),

  /**
   * Other boxes to publish in the location list, one per `;`-separated entry with `|`-separated
   * fields: `id|name|host|domain|location|flag|api_base`. A delimited format rather than JSON
   * because this lives in a systemd EnvironmentFile, where quoting JSON is fragile.
   * Seeded with INSERT OR IGNORE on the primary and carried to every replica by the snapshot, so
   * the list is identical everywhere.
   */
  peerServers: (optional('PEER_SERVERS', '') || '')
    .split(';')
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const [id, name, host, domain, location, flag, api_base] = entry.split('|').map((f) => (f || '').trim());
      return { id, name, host, domain, location, flag, api_base };
    })
    .filter((peer) => peer.id && peer.host),

  pollIntervalMs: Number(optional('POLL_INTERVAL_MS', 30000)),
  reconcileIntervalMs: Number(optional('RECONCILE_INTERVAL_MS', 600000)),
  userTokenTtl: optional('USER_TOKEN_TTL', '30d'),
  adminTokenTtl: optional('ADMIN_TOKEN_TTL', '12h'),
  bcryptRounds: Number(optional('BCRYPT_ROUNDS', 11)),

  seedAdminUsername: optional('SEED_ADMIN_USERNAME', 'admin'),
  seedAdminPassword: optional('SEED_ADMIN_PASSWORD', ''),

  // Cluster topology & Write-Forwarding (ADR-0009 / ADR-0011)
  clusterRole: optional('CLUSTER_ROLE', optional('USAGE_PRIMARY_URL', '') ? 'follower' : (optional('PUBLIC_HOST', '') === '86.54.42.124' || optional('BOX_ID', 'primary') === 'primary' ? 'leader' : 'follower')),
  clusterLeaderUrl: optional('CLUSTER_LEADER_URL', optional('USAGE_PRIMARY_URL', 'https://86-54-42-124.nip.io')),

  // Network Discovery & Operator Presets (docs/design/0001)
  adminUsernames: (optional('ADMIN_USERNAMES', 'admin') || '').split(',').map((s) => s.trim()).filter(Boolean),
  ownBoxIps: ['86.54.42.124', '38.17.64.159', '62.144.51.213', optional('PUBLIC_HOST', '86.54.42.124')],
  asnDbPath: optional('ASN_DB_PATH', ''),
  discovery: {
    K: Number(optional('PRESET_K', 3)),
    N: Number(optional('PRESET_N', 6)),
    T_kBps: Number(optional('PRESET_T_KBPS', 500)),
    D: Number(optional('PRESET_D', 0.1)),
    R_days: Number(optional('PRESET_R_DAYS', 14)),
    K_op: Number(optional('PRESET_K_OP', 3)),
    T_stable_min: Number(optional('PRESET_T_STABLE_MIN', 3)),
    observePerUidPerDay: Number(optional('OBSERVE_PER_UID_DAY', 10)),
    observeGlobalPerDay: Number(optional('OBSERVE_GLOBAL_DAY', 500)),
    reportPerUidPerHour: Number(optional('REPORT_PER_UID_HOUR', 30)),
    maxCandidates: Number(optional('PRESET_MAX_CANDIDATES', 12)),
  },
};
