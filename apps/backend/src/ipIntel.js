import fs from 'node:fs';
import net from 'node:net';

// The server's own view of a user's line, independent of what the client claims.
// Without a prefix table there is no independent view: callers must treat that as "unknown",
// never as "the client is right".

const toInt = (ip) => {
  if (!net.isIPv4(ip)) return null;
  return ip.split('.').reduce((acc, part) => acc * 256 + Number(part), 0);
};

const isPrivate = (ip) => {
  const n = toInt(ip);
  if (n === null) return true;
  const first = Number(ip.split('.')[0]);
  const second = Number(ip.split('.')[1]);
  return (
    first === 10 ||
    first === 127 ||
    first === 0 ||
    (first === 172 && second >= 16 && second <= 31) ||
    (first === 192 && second === 168) ||
    (first === 169 && second === 254) ||
    (first === 100 && second >= 64 && second <= 127)
  );
};

export function prefix24(ip) {
  if (!net.isIPv4(ip)) return '';
  return ip.split('.').slice(0, 3).join('.');
}

export function parsePrefixes(entries) {
  const parsed = (Array.isArray(entries) ? entries : [])
    .map((e) => {
      const [base, bitsRaw] = String(e.prefix || '').split('/');
      const bits = Number.parseInt(bitsRaw ?? '32', 10);
      const baseInt = toInt(base);
      if (baseInt === null || !Number.isFinite(bits) || bits < 0 || bits > 32) return null;
      const mask = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
      return {
        network: (baseInt & mask) >>> 0,
        mask,
        bits,
        asn: Number(e.asn) || null,
        org: String(e.org || ''),
        country: String(e.country || '').toUpperCase(),
        region: String(e.region || '').toLowerCase(),
      };
    })
    .filter(Boolean);
  parsed.sort((a, b) => b.bits - a.bits);
  return parsed;
}

export function loadPrefixTable(filePath) {
  if (!filePath) return [];
  const raw = JSON.parse(fs.readFileSync(filePath, 'utf8'));
  return parsePrefixes(Array.isArray(raw) ? raw : raw.prefixes || []);
}

export function createIpIntel({ ownIps = [], prefixes = [] } = {}) {
  const own = new Set(ownIps.filter((ip) => net.isIPv4(ip)));

  const lookup = (ip) => {
    const n = toInt(ip);
    if (n === null) return null;
    for (const entry of prefixes) {
      if ((n & entry.mask) >>> 0 === entry.network) {
        return { asn: entry.asn, org: entry.org, country: entry.country, region: entry.region };
      }
    }
    return null;
  };

  return {
    lookup,
    isOwnIp: (ip) => own.has(ip),
    // A usable observation needs a real, public, non-PTunnel source address.
    isUserLine: (ip) => net.isIPv4(ip) && !own.has(ip) && !isPrivate(ip),
    view: (ip) => {
      if (!net.isIPv4(ip)) return { ip: null, usable: false, own: false, asn: null, org: '', country: '', region: '', prefix24: '' };
      const info = lookup(ip);
      return {
        ip,
        usable: !own.has(ip) && !isPrivate(ip),
        own: own.has(ip),
        asn: info?.asn ?? null,
        org: info?.org ?? '',
        country: info?.country ?? '',
        region: info?.region ?? '',
        prefix24: prefix24(ip),
      };
    },
  };
}