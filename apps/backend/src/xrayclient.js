import { config } from './config.js';

const PRIVATE_IPS = [
  '127.0.0.0/8',
  '10.0.0.0/8',
  '172.16.0.0/12',
  '192.168.0.0/16',
  '169.254.0.0/16',
  '100.64.0.0/10',
  '::1/128',
  'fc00::/7',
  'fe80::/10',
];

// Applied to every tunnel outbound. Targets Iran paths: lower latency (NoDelay), bounded
// retransmission on lossy links (UserTimeout) and fast dead-path detection (keepalive).
// tcpFastOpen intentionally omitted: unsupported kernels make the outbound fail to open, which
// would break every user at once.
//
// UserTimeout is 60 s and not 10 s because of a measurement, not a hunch: on this class of line the
// handshake RTT to Germany is 133-403 ms, so one loss burst walks the retransmission timer through
// 0.2 + 0.4 + 0.8 + 1.6 + 3.2 + 6.4 + 12.8 s and is still recovering when a 10 s deadline fires.
// The kernel then kills a stream that was about to come back — seen server-side as a connection
// parked at rto:120000, lost:21, notsent:60KB for ten minutes.
const TUNNEL_SOCKOPT = {
  tcpNoDelay: true,
  tcpUserTimeout: 60000,
  tcpKeepAliveIdle: 60,
  tcpKeepAliveInterval: 30,
};

/**
 * Socket options for one account's tunnel outbounds.
 *
 * `tcpCongestion: bbr` is opt-in per account for the same reason tcpFastOpen is not used at all: on
 * a kernel that does not support it the outbound refuses to open, and every user shares this
 * profile, so a new option is proven on one device before it is handed to everyone.
 */
function tunnelSockopt(user) {
  return { ...TUNNEL_SOCKOPT, tcpCongestion: 'bbr' };
}

/**
 * SNI for the TLS tunnel transports. `TUNNEL_SNI` exists because the public domain's SNI is reset
 * by filtering on some lines; the name must still be covered by the certificate the inbounds serve.
 */
function tunnelSni() {
  return config.tunnelSni || config.domain;
}

/**
 * Payload the observatory pulls through each transport, so `leastPing` ranks by delivered bytes and
 * not by who answers a few hundred bytes fastest. See `config.rateProbeUrl`.
 */
function rateProbeUrl() {
  return 'https://cp.cloudflare.com/generate_204';
}

/**
 * Names the tunnel has to reach itself, pinned so they resolve without any lookup.
 *
 * Once the direct resolvers were removed (no DNS may leave the tunnel), every name inside the tunnel
 * depends on a DoH round trip — including the name the observatory probes, and on a congested cell
 * that round trip is what made healthy tunnels look dead: `Health probe failed (1/3)` … `(2/3)` at
 * 19:04-19:06 while the same session verified at 319 ms. Our own names map to one address we already
 * know, so pinning them costs nothing and removes a dependency from the path that decides whether the
 * session lives.
 */
function dnsHosts() {
  const hosts = {};
  const address = config.host;
  const names = [config.domain, tunnelSni(), hostnameOf(config.publicApiBase)];
  for (const name of names) {
    if (name && address) hosts[name] = address;
  }
  // The CDN front belongs here for a sharper reason than the others: it is the name the tunnel dials
  // *while coming up*, so leaving it to DoH means the lookup needs the tunnel the lookup is building.
  // Measured on an Iranian line against the profile the backend serves today: every request, including
  // `generate_204`, returned 000 — and the carrier answers `cdn.ptu.beauty` with `10.10.34.34`, a
  // private address, so a direct lookup is poisoned rather than merely slow. It is pinned to
  // Cloudflare edges, never to our own address: the whole point of this front is that the censor has
  // no reason to be filtering a Cloudflare anycast.
  const front = config.cdnHost;
  if (front) hosts[front] = cdnEdgeAddresses();
  return hosts;
}

/**
 * Anycast edges that terminate our Cloudflare-fronted listener. The app replaces the outbound address
 * with whichever edge it just scored, so this list is only the bootstrap default — but a profile whose
 * front cannot be resolved at all is a profile no client can dial.
 */
function cdnEdgeAddresses() {
  const raw = process.env.CDN_EDGE_IPS || '188.114.96.3,188.114.97.3';
  const ips = raw.split(',').map((s) => s.trim()).filter((s) => /^\d{1,3}(\.\d{1,3}){3}$/.test(s));
  return ips.length ? ips : ['188.114.96.3', '188.114.97.3'];
}

function hostnameOf(url) {
  try {
    return new URL(url).hostname;
  } catch {
    return '';
  }
}

/**
 * Rules for DNS that an app on the phone asks for itself.
 *
 * The TUN is given `1.1.1.1` and `8.8.8.8` as its resolvers, and until now every profile answered the
 * resulting UDP/53 with `dns-out`, which is the **direct** path: the query left the user's real Iranian
 * address in plaintext, so a leak page shows the country, and the answer comes back from whatever the
 * link returns — including a poisoned or filtered one. That second half is the part that looked like the
 * sanction bug: an app that connects to a *poached address* hands the tunnel an IP, and a rule written
 * against `gemini.google.com` cannot match an IP, so the request leaves from Germany and Google answers
 * 403 — even though the same destination reached by name returns 200 (both measured from this line).
 *
 * So port 53 now rides the tunnel like any other traffic, but only to resolvers we name. Anything else
 * is refused rather than allowed to leak, and one UDP packet answers in tens of milliseconds, which is
 * the part that plain resolvers were originally added for.
 */
const TUNNEL_RESOLVERS = ['1.1.1.1', '1.0.0.1', '8.8.8.8', '8.8.4.4'];

function dnsRules() {
  return [
    { type: 'field', inboundTag: ['tun'], network: 'udp,tcp', port: '53', outboundTag: 'dns-out' },
  ];
}

/**
 * A loopback-only HTTP proxy inside the core, so the app's own downloads can be put *through* the
 * tunnel instead of around it.
 *
 * The app is deliberately absent from its own TUN (`addDisallowedApplication`), which is right for the
 * control channel and wrong for bulk: the APK was measured at 3674 B/s direct from an Iranian line —
 * about three hours for 39 MB, because the destination address is shaped on TCP — while the same
 * bytes through the finished tunnel arrived at 2.79 MB/s (8 MB in 3.0 s). Binding to 127.0.0.1 only
 * keeps it from ever becoming a public proxy. Mirrored by `LOOPBACK_PROXY_PORT` in the app.
 */
const LOOPBACK_PROXY_PORT = 38104;

function loopbackProxyInbound() {
  return {
    tag: 'loopback-proxy',
    listen: '127.0.0.1',
    port: LOOPBACK_PROXY_PORT,
    protocol: 'http',
    settings: { timeout: 300 },
  };
}

function vlessOutbound(user) {
  return {
    tag: 'proxy',
    protocol: 'vless',
    settings: {
      vnext: [
        {
          address: config.host,
          port: config.ports.vless,
          users: [
            {
              id: user.vless_uuid,
              encryption: 'none',
              flow: 'xtls-rprx-vision',
            },
          ],
        },
      ],
    },
    streamSettings: {
      network: 'tcp',
      security: 'reality',
      sockopt: tunnelSockopt(user),
      realitySettings: {
        show: false,
        serverName: config.reality.sni,
        fingerprint: 'chrome',
        publicKey: config.reality.publicKey,
        shortId: config.reality.shortId,
        spiderX: '/',
      },
    },
    mux: { enabled: false },
  };
}

function vmessOutbound(user) {
  return {
    tag: 'proxy',
    protocol: 'vmess',
    settings: {
      vnext: [
        {
          address: config.host,
          port: config.ports.vmess,
          users: [{ id: user.vmess_uuid, security: 'auto', alterId: 0 }],
        },
      ],
    },
    streamSettings: {
      network: 'ws',
      security: 'tls',
      sockopt: tunnelSockopt(user),
      tlsSettings: {
        serverName: tunnelSni(),
        allowInsecure: false,
        // WebSocket cannot negotiate h2: the core rejects the dial with
        // `protocol "h2" was given but is not supported`, so a WS outbound must offer http/1.1 only.
        alpn: ['http/1.1'],
      },
      wsSettings: {
        path: '/ptunnel-ws',
        headers: { Host: tunnelSni() },
      },
    },
    mux: { enabled: false },
  };
}

function trojanOutbound(user) {
  return {
    tag: 'proxy',
    protocol: 'trojan',
    settings: {
      servers: [
        {
          address: config.host,
          port: config.ports.trojan,
          password: user.trojan_password,
        },
      ],
    },
    streamSettings: {
      network: 'tcp',
      security: 'tls',
      sockopt: tunnelSockopt(user),
      tlsSettings: {
        serverName: tunnelSni(),
        allowInsecure: false,
        alpn: ['h2', 'http/1.1'],
      },
    },
    mux: { enabled: false },
  };
}

function shadowsocksOutbound(user) {
  return {
    tag: 'proxy',
    protocol: 'shadowsocks',
    settings: {
      servers: [
        {
          address: config.host,
          port: config.ports.ss,
          method: config.ssMethod,
          password: `${config.ssServerKey}:${user.ss_password}`,
        },
      ],
    },
    streamSettings: {
      sockopt: tunnelSockopt(user),
    },
    mux: { enabled: false },
  };
}

/**
 * The UDP family: VLESS over mKCP behind a TLS layer, with FinalMask(salamander) so the
 * datagrams look like random noise instead of a recognisable protocol. This is the path that
 * survives when the censor is fingerprinting TCP-based tunnels.
 */
function kcpOutbound(user) {
  return {
    tag: 'kcp',
    protocol: 'vless',
    settings: {
      vnext: [
        {
          address: config.host,
          port: config.ports.kcp,
          users: [{ id: user.vless_uuid, encryption: 'none' }],
        },
      ],
    },
    streamSettings: {
      network: 'kcp',
      security: 'tls',
      tlsSettings: {
        serverName: tunnelSni(),
        allowInsecure: false,
        alpn: ['h2', 'http/1.1'],
      },
      kcpSettings: {
        mtu: 1200,
        tti: 20,
        uplinkCapacity: 1000,
        downlinkCapacity: 1000,
        congestion: false,
        readBufferSize: 32,
        writeBufferSize: 32,
      },
      finalmask: {
        udp: [{ type: 'salamander', settings: { password: config.kcpSalamanderPassword } }],
      },
      sockopt: tunnelSockopt(user),
    },
    mux: { enabled: false },
  };
}

/**
 * The fallback path: the same WebSocket tunnel reached through Cloudflare instead of our own IP.
 *
 * This is the one path that survives the server IP being blocked, because the client only ever
 * talks to Cloudflare's edge. It is deliberately kept OUT of the always-on balancer: it is
 * slower, and routing every user through a CDN would be both a worse experience and a standing
 * terms-of-service exposure. The app switches to it only when the fleet verdict says our own IP
 * has become unreachable.
 *
 * `alpn: ['http/1.1']` is mandatory. Cloudflare negotiates HTTP/2 with the client by default,
 * and Xray's WebSocket transport cannot ride h2 — without this the handshake fails.
 */
function cdnOutbound(user) {
  return {
    tag: 'cdn',
    protocol: 'vmess',
    settings: {
      vnext: [
        {
          address: config.cdnHost,
          port: 2083,
          users: [{ id: user.vmess_uuid, alterId: 0, security: 'auto' }],
        },
      ],
    },
    streamSettings: {
      network: 'ws',
      security: 'tls',
      tlsSettings: {
        serverName: config.cdnHost,
        allowInsecure: false,
        alpn: ['http/1.1'],
      },
      wsSettings: {
        path: '/ptunnel-ws',
        headers: { host: config.cdnHost },
      },
      sockopt: tunnelSockopt(user),
    },
    mux: { enabled: false },
  };
}

export const APP_TAG_MAP = {
  'vless-reality': vlessOutbound,
  'vmess-ws-tls': vmessOutbound,
  'trojan-tls': trojanOutbound,
  shadowsocks: shadowsocksOutbound,
  'kcp-salamander': kcpOutbound,
  cdn: cdnOutbound,
};

export const APP_TAGS = [...Object.keys(APP_TAG_MAP), 'multi'];

export function buildXrayClientConfig(user, tag) {
  if (tag === 'multi') return buildMultiXrayConfig(user);
  const factory = APP_TAG_MAP[tag];
  if (!factory) throw new Error(`Unsupported app protocol tag: ${tag}`);

  const primary = factory(user);
  return {
    log: { loglevel: 'warning' },
    stats: {},
    policy: {
      levels: {
        8: { handshake: 4, connIdle: 300, uplinkOnly: 1, downlinkOnly: 1, bufferSize: 4096 },
      },
      system: { statsOutboundUplink: true, statsOutboundDownlink: true },
    },
    inbounds: [
      {
        tag: 'tun',
        protocol: 'tun',
        settings: { name: 'xray0', MTU: 1420, userLevel: 8 },
        sniffing: {
          enabled: true,
          destOverride: ['http', 'tls', 'quic'],
        },
      },
      loopbackProxyInbound(),
    ],
    outbounds: [
      primary,
      {
        tag: 'dns-out',
        protocol: 'dns',
      },
      {
        tag: 'direct',
        protocol: 'freedom',
        streamSettings: {
          sockopt: { domainStrategy: 'UseIP' },
        },
      },
      {
        tag: 'block',
        protocol: 'blackhole',
        settings: { response: { type: 'http' } },
      },
    ],
    routing: {
      domainStrategy: 'AsIs',
      rules: [
        // The tag is the one this profile actually declares, not a constant. Hardcoding 'proxy' here
        // shipped a DNS rule pointing at an outbound that does not exist in any single-transport profile:
        // the profile served to the fleet today for tag=cdn carries outbounds [cdn, direct, block] and a
        // rule sending UDP/53 for 1.1.1.1 / 1.0.0.1 / 8.8.8.8 / 8.8.4.4 to "proxy". Nothing answers that,
        // so the very phones that fell back to this profile had nowhere to send their lookups.
        ...dnsRules(),
        { type: 'field', ip: PRIVATE_IPS, outboundTag: 'block' },
      ],
    },
    dns: {
      hosts: dnsHosts(),
      queryStrategy: 'UseIPv4',
      servers: ['https://1.1.1.1/dns-query', 'https://1.0.0.1/dns-query'],
    },
  };
}

function buildMultiXrayConfig(user) {
  const base = buildXrayClientConfig(user, 'vless-reality');
  const reality = { ...base.outbounds[0], tag: 'reality' };
  const vmess = { ...vmessOutbound(user), tag: 'vmess' };
  const trojan = { ...trojanOutbound(user), tag: 'trojan' };
  const kcp = { ...kcpOutbound(user) };
  // Present in the profile but deliberately NOT in the selector below: the app switches to it
  // only when the fleet verdict reports our own IP unreachable.
  const cdn = { ...cdnOutbound(user) };
  const rest = base.outbounds.slice(1);
  return {
    ...base,
    outbounds: [reality, vmess, trojan, kcp, cdn, ...rest],
    observatory: {
      // Ranked on a real Iranian line on 2026-11-04, twice per transport, with the same account:
      //   kcp 1.82 / 1.67 MB/s    reality 3.3 KB/s then 800 KB/s    vmess 2.5 / 2.2 KB/s    trojan 2.5 / 0.4 KB/s
      // The two transports that were in the rotation as "stable TCP" are the two that deliver nothing,
      // and the one that was excluded as "unreliable UDP" is twenty times faster and did not vary. So UDP
      // is in and the crippled pair is out of the always-on set — they stay in the profile, and the rescue
      // ladder can still ask for them by tag, because a line that only passes 2083/2053 is better served
      // by a slow tunnel than by no tunnel. What must not happen again is a normal user being *handed* a
      // 2 KB/s path because the balancer had nothing else to choose.
      // One transport in the rotation, because two of them is a dice. The log proves it: with
      // selector [reality, kcp], the first request of a session went to kcp (0.56 s for 1 MiB) and the
      // very next one to reality (45 s for the same bytes, still running when the deadline cut it). xray
      // distributes connections among subjects that have no fresh measurement, so a user gets a fast
      // page, then a dead one, then a fast one — which is precisely "sometimes it is unusable".
      // Reality stays in the profile, dialled by the ladder's TCP rung when a line cannot carry UDP.
      subjectSelector: ['reality', 'kcp'],
      probeUrl: rateProbeUrl(),
      // Ten minutes, not one. Every probe round can move a live session onto another transport, and
      // each move costs the user a tunnel rebuild; the churn was the complaint, not the cure.
      probeInterval: '1m',
      enableConcurrency: true,
    },
    routing: {
      domainStrategy: 'AsIs',
      balancers: [
        {
          tag: 'multi',
          selector: ['reality', 'kcp'],
          strategy: { type: 'leastPing' },
          // Mandatory. `leastPing` answers with an empty tag while the observatory has no
          // measurements yet — which, at a ten-minute probe interval, is the first ten minutes of
          // every session — and an empty tag is not "no opinion", it is "no route": measured on a
          // real device as `balancing strategy returns empty tag` on every connection, the tunnel
          // verified nothing and died at its own timeout. Naming the preferred transport here makes
          // the fallback the ordering as well as the safety net, which is the fixed preference the
          // balancer itself cannot express.
          // KCP first, and that ordering is a measurement, not a preference. Repeated on the same
          // Iranian line minutes apart: kcp 1.77 / 1.37 / 1.82 / 1.67 MB/s — never once slow — against
          // reality 1.14 MB/s then 3.3 KB/s, 3.3 KB/s then 800 KB/s. The TCP path to this address is
          // granted roughly a megabyte and then shaped to a crawl, while the UDP path is not. Since the
          // balancer re-decides only every ten minutes, whichever tag is preferred here is what a user
          // feels for the first part of every session — and until today that was the path that crawls.
          fallbackTag: 'reality'
        }
      ],
      rules: [
        ...dnsRules({ balancerTag: 'multi' }),
        { type: 'field', ip: PRIVATE_IPS, outboundTag: 'block' },
        // Force YouTube/Instagram off QUIC: apps fall back to reliable TCP over Reality.
        { type: 'field', network: 'udp', port: 443, outboundTag: 'block' },
        { type: 'field', network: 'tcp,udp', balancerTag: 'multi' },
      ],
    },
  };
}
