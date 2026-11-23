#!/bin/bash
set -euo pipefail

DIR=/var/www/ptu-bootstrap
FILE=$DIR/bootstrap.json
TOKEN=$(cat /root/.cf_token 2>/dev/null || echo '')
ZONE=b97b178991af5e5cc50a82a93a482cfb
TXT_NAME=_ptu-bootstrap.ptu.beauty

mkdir -p "$DIR"
STAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)

cat > "$FILE" <<JSON_EOF
{
  "v": 1,
  "issuedAt": "$STAMP",
  "host": "86.54.42.124",
  "api": [
    "https://62-144-51-213.nip.io/v1/",
    "https://38-17-64-159.nip.io/v1/",
    "https://86-54-42-124.nip.io/v1/",
    "https://ptu.beauty/v1/"
  ],
  "pins": {
    "ptu.beauty": "86.54.42.124",
    "www.ptu.beauty": "86.54.42.124",
    "86-54-42-124.nip.io": "86.54.42.124",
    "38-17-64-159.nip.io": "38.17.64.159",
    "62-144-51-213.nip.io": "62.144.51.213"
  },
  "mirrors": [
    "https://62-144-51-213.nip.io/b",
    "https://38-17-64-159.nip.io/b",
    "https://86-54-42-124.nip.io/b",
    "https://ptu.beauty/b"
  ],
  "status": "https://86-54-42-124.nip.io/s",
  "cdn": {
    "host": "cdn.ptu.beauty",
    "port": 2083,
    "path": "/ptunnel-ws",
    "alpn": "http/1.1",
    "ips": "https://86-54-42-124.nip.io/c"
  },
  "entryPoints": [
    { "protocol": "vless-reality", "host": "86.54.42.124", "port": 8080, "transport": "tcp" },
    { "protocol": "vless-reality", "host": "86.54.42.124", "port": 443, "transport": "tcp" },
    { "protocol": "trojan-tls", "host": "86.54.42.124", "port": 2053, "transport": "tcp" },
    { "protocol": "vmess-ws-tls", "host": "86.54.42.124", "port": 2083, "transport": "tcp" },
    { "protocol": "kcp-salamander", "host": "86.54.42.124", "port": 8443, "transport": "udp" },
    { "protocol": "hysteria2", "host": "86.54.42.124", "port": 443, "transport": "udp" },
    { "protocol": "vless-reality", "host": "38.17.64.159", "port": 8080, "transport": "tcp" },
    { "protocol": "vless-reality", "host": "38.17.64.159", "port": 443, "transport": "tcp" },
    { "protocol": "trojan-tls", "host": "38.17.64.159", "port": 2053, "transport": "tcp" },
    { "protocol": "vmess-ws-tls", "host": "38.17.64.159", "port": 2083, "transport": "tcp" },
    { "protocol": "kcp-salamander", "host": "38.17.64.159", "port": 8443, "transport": "udp" },
    { "protocol": "hysteria2", "host": "38.17.64.159", "port": 443, "transport": "udp" },
    { "protocol": "vless-reality", "host": "62.144.51.213", "port": 8080, "transport": "tcp" },
    { "protocol": "vless-reality", "host": "62.144.51.213", "port": 443, "transport": "tcp" },
    { "protocol": "trojan-tls", "host": "62.144.51.213", "port": 2053, "transport": "tcp" },
    { "protocol": "vmess-ws-tls", "host": "62.144.51.213", "port": 2083, "transport": "tcp" },
    { "protocol": "kcp-salamander", "host": "62.144.51.213", "port": 8443, "transport": "udp" },
    { "protocol": "hysteria2", "host": "62.144.51.213", "port": 443, "transport": "udp" }
  ]
}
JSON_EOF
chmod 644 "$FILE"
echo "payload_bytes=$(wc -c < "$FILE")"

if [ -n "$TOKEN" ]; then
  echo "--- publish pointer as Cloudflare TXT ---"
  POINTER="v=1;b=https://62-144-51-213.nip.io/b;b=https://38-17-64-159.nip.io/b;b=https://86-54-42-124.nip.io/b"
  EXISTING=$(curl -sS -H "Authorization: Bearer $TOKEN" \
    "https://api.cloudflare.com/client/v4/zones/$ZONE/dns_records?type=TXT&name=$TXT_NAME" \
    | python3 -c "import json,sys; r=json.load(sys.stdin).get('result') or []; print(r[0]['id'] if r else '')")
  if [ -n "$EXISTING" ]; then
    curl -sS -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      "https://api.cloudflare.com/client/v4/zones/$ZONE/dns_records/$EXISTING" \
      --data "{\"type\":\"TXT\",\"name\":\"$TXT_NAME\",\"content\":\"$POINTER\",\"ttl\":300}" \
      | python3 -c "import json,sys; d=json.load(sys.stdin); print('CF TXT updated' if d.get('success') else d.get('errors'))"
  fi
fi
echo "publish-bootstrap completed!"
