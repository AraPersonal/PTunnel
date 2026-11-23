import { config } from './config.js';

const TAG_INFO = {
  'vless-reality': {
    id: 'reality',
    name: 'Reality 8080',
    protocol: 'vless',
    port: 8080,
    flag: '⚡',
    transport: 'TCP',
    security: 'REALITY',
  },
  'kcp-salamander': {
    id: 'kcp',
    name: 'mKCP 8443',
    protocol: 'vless',
    port: 8443,
    flag: '🚀',
    transport: 'mKCP',
    security: 'TLS',
  },
  hysteria2: {
    id: 'hysteria2',
    name: 'Hysteria2 443',
    protocol: 'hysteria2',
    port: 443,
    flag: '🚄',
    transport: 'UDP',
    security: 'TLS',
  },
  'vmess-ws-tls': {
    id: 'vmess',
    name: 'VMess WS 2083',
    protocol: 'vmess',
    port: 2083,
    flag: '🧩',
    transport: 'WebSocket',
    security: 'TLS',
  },
  'trojan-tls': {
    id: 'trojan',
    name: 'Trojan 2053',
    protocol: 'trojan',
    port: 2053,
    flag: '🛡',
    transport: 'TCP',
    security: 'TLS',
  },
};

export const ALL_TAGS = Object.keys(TAG_INFO);

export function serverMetadata() {
  return ALL_TAGS.map((tag) => ({
    ...TAG_INFO[tag],
    tag,
    host: config.host,
  }));
}

function b64(value) {
  return Buffer.from(value, 'utf8').toString('base64');
}

export function buildLinks(user) {
  const username = user.username;
  const domain = config.domain;
  const host = config.host;
  const realityLabel = encodeURIComponent('PTunnel ⚡ Reality');
  const hyLabel = encodeURIComponent('PTunnel 🚀 Hysteria2');
  const vmessLabel = encodeURIComponent('PTunnel 🧩 VMess WS');
  const trojanLabel = encodeURIComponent('PTunnel 🛡 Trojan');

  const vless =
    `vless://${user.vless_uuid}@${host}:${config.ports.vless}` +
    `?encryption=none&security=reality&sni=${encodeURIComponent(config.reality.sni)}` +
    `&fp=chrome&pbk=${encodeURIComponent(config.reality.publicKey)}` +
    `&sid=${encodeURIComponent(config.reality.shortId)}&type=tcp&flow=xtls-rprx-vision` +
    `#${realityLabel}`;

  const vmessObject = {
    v: '2',
    ps: 'PTunnel 🧩 VMess WS',
    add: host,
    port: String(config.ports.vmess),
    id: user.vmess_uuid,
    aid: '0',
    scy: 'auto',
    net: 'ws',
    type: 'none',
    host: domain,
    path: '/ptunnel-ws',
    tls: 'tls',
    sni: domain,
    alpn: 'h2,http/1.1',
  };
  const vmess = `vmess://${b64(JSON.stringify(vmessObject))}#${vmessLabel}`;

  const trojan =
    `trojan://${encodeURIComponent(user.trojan_password)}@${host}:${config.ports.trojan}` +
    `?security=tls&sni=${encodeURIComponent(domain)}&alpn=h2%2Chttp%2F1.1&type=tcp` +
    `#${trojanLabel}`;

  const hyPassword = `${username}:${user.hysteria_password}`;
  const hysteria2 =
    `hysteria2://${encodeURIComponent(username)}:${encodeURIComponent(user.hysteria_password)}@${host}:${config.ports.hysteria}` +
    `/?sni=${encodeURIComponent(domain)}&insecure=0#${hyLabel}`;

  return [
    { tag: 'vless-reality', name: TAG_INFO['vless-reality'].name, link: vless },
    { tag: 'hysteria2', name: TAG_INFO.hysteria2.name, link: hysteria2 },
    { tag: 'vmess-ws-tls', name: TAG_INFO['vmess-ws-tls'].name, link: vmess },
    { tag: 'trojan-tls', name: TAG_INFO['trojan-tls'].name, link: trojan },
  ];
}

export function buildSubscriptionPayload(user) {
  return buildLinks(user).map((item) => item.link).join('\n');
}

function outboundFor(user, tag) {
  const domain = config.domain;
  const host = config.host;

  switch (tag) {
    case 'vless-reality':
      return {
        type: 'vless',
        tag,
        server: host,
        server_port: config.ports.vless,
        uuid: user.vless_uuid,
        flow: 'xtls-rprx-vision',
        tls: {
          enabled: true,
          server_name: config.reality.sni,
          utls: { enabled: true, fingerprint: 'chrome' },
          reality: {
            enabled: true,
            public_key: config.reality.publicKey,
            short_id: config.reality.shortId,
          },
        },
      };
    case 'hysteria2':
      return {
        type: 'hysteria2',
        tag,
        server: host,
        server_port: config.ports.hysteria,
        password: `${user.username}:${user.hysteria_password}`,
        tls: {
          enabled: true,
          server_name: domain,
          alpn: ['h3'],
        },
      };
    case 'vmess-ws-tls':
      return {
        type: 'vmess',
        tag,
        server: host,
        server_port: config.ports.vmess,
        uuid: user.vmess_uuid,
        security: 'auto',
        alter_id: 0,
        transport: {
          type: 'ws',
          path: '/ptunnel-ws',
          headers: { Host: domain },
        },
        tls: {
          enabled: true,
          server_name: domain,
          alpn: ['h2', 'http/1.1'],
        },
      };
    case 'trojan-tls':
      return {
        type: 'trojan',
        tag,
        server: host,
        server_port: config.ports.trojan,
        password: user.trojan_password,
        tls: {
          enabled: true,
          server_name: domain,
          alpn: ['h2', 'http/1.1'],
        },
      };
    default:
      throw new Error(`Unknown protocol tag: ${tag}`);
  }
}

export function buildSingboxConfig(user, selectedTag) {
  const tags = selectedTag ? [selectedTag] : ALL_TAGS.slice();
  const selectedOutbounds = tags.map((tag) => outboundFor(user, tag));

  const direct = { type: 'direct', tag: 'direct' };
  const block = { type: 'block', tag: 'block' };

  if (selectedTag) {
    return {
      log: { level: 'warn', timestamp: true },
      outbounds: [...selectedOutbounds, direct, block],
      route: {
        rules: [],
        final: selectedTag,
        auto_detect_interface: true,
      },
    };
  }

  return {
    log: { level: 'warn', timestamp: true },
    outbounds: [
      {
        type: 'selector',
        tag: 'proxy',
        outbounds: ALL_TAGS.slice(),
        default: 'vless-reality',
      },
      ...selectedOutbounds,
      direct,
      block,
    ],
    route: {
      rules: [],
      final: 'proxy',
      auto_detect_interface: true,
    },
  };
}
