/**
 * Builds the client-side Xray JSON configuration tuned specifically for Iran's network realities:
 * - TCP NoDelay enabled
 * - tcpUserTimeout bounded to 60s (prevents premature socket death during carrier packet loss bursts)
 * - Loopback HTTP proxy on 127.0.0.1:38104 for in-tunnel unthrottled downloads
 * - Port 8080 prioritized for Reality TCP (443 is heavily shaped on Iranian mobile carriers)
 * - mKCP UDP on 8443 with SRTP header
 * - DNS queries routed via DoH inside the tunnel to eliminate DNS poisoning
 */

const LOOPBACK_PROXY_PORT = 38104;

export function buildClientConfig(user, tag = 'multi', options = {}) {
  const host = options.publicHost || '86.54.42.124';
  const realityPort = options.realityPort || 8080;
  const kcpPort = options.kcpPort || 8443;
  const realitySni = options.realitySni || 'www.microsoft.com';
  const realityPubKey = options.realityPublicKey || 'm_fake_reality_pubkey';
  const realityShortId = options.realityShortId || 'abcd1234';

  const sockopt = {
    tcpNoDelay: true,
    tcpUserTimeout: 60000,
    tcpKeepAliveIdle: 60,
    tcpKeepAliveInterval: 30,
  };

  const outbounds = [];

  // 1. Reality (TCP)
  const realityOutbound = {
    tag: 'reality',
    protocol: 'vless',
    settings: {
      vnext: [
        {
          address: host,
          port: realityPort,
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
      sockopt,
      realitySettings: {
        show: false,
        serverName: realitySni,
        fingerprint: 'chrome',
        publicKey: realityPubKey,
        shortId: realityShortId,
        spiderX: '/',
      },
    },
  };

  // 2. mKCP (UDP)
  const kcpOutbound = {
    tag: 'kcp',
    protocol: 'vless',
    settings: {
      vnext: [
        {
          address: host,
          port: kcpPort,
          users: [
            {
              id: user.vless_uuid,
              encryption: 'none',
            },
          ],
        },
      ],
    },
    streamSettings: {
      network: 'kcp',
      kcpSettings: {
        mtu: 1350,
        tti: 20,
        uplinkCapacity: 100,
        downlinkCapacity: 100,
        congestion: false,
        readBufferSize: 2,
        writeBufferSize: 2,
        header: { type: 'srtp' },
        seed: options.kcpSeed || 'ptunnel-kcp-seed',
      },
    },
  };

  // 3. Fallback CDN (Cloudflare WebSocket)
  const cdnOutbound = {
    tag: 'cdn',
    protocol: 'vless',
    settings: {
      vnext: [
        {
          address: options.cdnEdge || '188.114.96.3',
          port: 2083,
          users: [{ id: user.vless_uuid, encryption: 'none' }],
        },
      ],
    },
    streamSettings: {
      network: 'ws',
      security: 'tls',
      tlsSettings: {
        serverName: options.cdnDomain || host,
      },
      wsSettings: {
        path: '/vless-ws',
      },
    },
  };

  // Configure outbounds based on requested tag:
  // In Iranian networks, VLESS-Reality on port 8080 with BBR avoids TCP port-443 shaping and raw-UDP throttling.
  // mKCP is retained as secondary/recovery, followed by CDN.
  if (tag === 'kcp') {
    outbounds.push(kcpOutbound, realityOutbound, cdnOutbound);
  } else if (tag === 'cdn') {
    outbounds.push(cdnOutbound, realityOutbound, kcpOutbound);
  } else {
    // Default / 'multi' / 'reality': Reality TCP leads for line-rate speed and zero packet-drop penalty
    outbounds.push(realityOutbound, kcpOutbound, cdnOutbound);
  }

  // Freedom direct & block outbounds
  outbounds.push({ tag: 'direct', protocol: 'freedom' }, { tag: 'block', protocol: 'blackhole' });

  return {
    log: { loglevel: 'warning' },
    dns: {
      servers: [
        'https://1.1.1.1/dns-query',
        'https://8.8.8.8/dns-query',
        { address: '1.1.1.1', port: 53, domains: ['domain:googleapis.com'] },
      ],
    },
    inbounds: [
      {
        tag: 'loopback-proxy',
        listen: '127.0.0.1',
        port: LOOPBACK_PROXY_PORT,
        protocol: 'http',
        settings: { timeout: 300 },
      },
    ],
    outbounds,
    routing: {
      domainStrategy: 'IPIfNonMatch',
      rules: [
        { type: 'field', ip: ['127.0.0.0/8', '10.0.0.0/8', '192.168.0.0/16'], outboundTag: 'direct' },
        { type: 'field', port: '53', network: 'udp', outboundTag: outbounds[0].tag },
        { type: 'field', network: 'tcp,udp', outboundTag: outbounds[0].tag },
      ],
    },
  };
}
