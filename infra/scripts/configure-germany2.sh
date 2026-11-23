#!/bin/bash
set -euo pipefail

DOMAIN="62-144-51-213.nip.io"
HOST="62.144.51.213"
REALITY_PUB="tgNDp7yrvFn4qjRqSXt9xwhpQTK8aakAkGavZ8GoxDw"
REALITY_PRIV="WKlRJx_PYKl-S4CcnUlTf37e3nzRituTx6k5Es7uJk0"
REALITY_SID="2a18498b611d7ac7"

echo "=== Configuring Germany 2 (${HOST} / ${DOMAIN}) ==="

# 1. SSL Certificates for Xray, Hysteria & Nginx
mkdir -p /etc/hysteria/certs /usr/local/etc/xray/certs /etc/letsencrypt/live/${DOMAIN} /var/www/html
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout /etc/letsencrypt/live/${DOMAIN}/privkey.pem \
  -out /etc/letsencrypt/live/${DOMAIN}/fullchain.pem \
  -subj "/CN=${DOMAIN}" >/dev/null 2>&1

cp /etc/letsencrypt/live/${DOMAIN}/fullchain.pem /etc/hysteria/certs/fullchain.pem
cp /etc/letsencrypt/live/${DOMAIN}/privkey.pem /etc/hysteria/certs/privkey.pem
cp /etc/letsencrypt/live/${DOMAIN}/fullchain.pem /usr/local/etc/xray/certs/fullchain.pem
cp /etc/letsencrypt/live/${DOMAIN}/privkey.pem /usr/local/etc/xray/certs/privkey.pem
chown -R hysteria:hysteria /etc/hysteria
chown -R xray:xray /usr/local/etc/xray

# 2. Xray configuration
cat > /usr/local/etc/xray/config.json <<EOF
{
  "log": { "loglevel": "warning" },
  "api": {
    "tag": "api",
    "services": ["HandlerService", "StatsService", "LoggerService"]
  },
  "stats": {},
  "policy": {
    "levels": {
      "0": {
        "statsUserUplink": true,
        "statsUserDownlink": true,
        "statsUserOnline": true
      }
    },
    "system": {
      "statsInboundUplink": true,
      "statsInboundDownlink": true
    }
  },
  "inbounds": [
    {
      "tag": "vless-reality",
      "listen": "0.0.0.0",
      "port": 8080,
      "protocol": "vless",
      "settings": { "clients": [], "decryption": "none" },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "show": false,
          "dest": "www.microsoft.com:443",
          "xver": 0,
          "serverNames": ["www.microsoft.com"],
          "privateKey": "${REALITY_PRIV}",
          "shortIds": ["${REALITY_SID}"]
        }
      },
      "sniffing": {
        "enabled": true,
        "destOverride": ["http", "tls", "quic"],
        "routeOnlySelectedDomains": false,
        "domains": [
          "domain:gemini.google.com",
          "domain:aistudio.google.com",
          "domain:makersuite.google.com",
          "domain:bard.google.com",
          "domain:notebooklm.google.com",
          "domain:notebook.google.com",
          "domain:generativelanguage.googleapis.com",
          "domain:alkalimakersuite-pa.googleapis.com",
          "domain:cloudcode-pa.googleapis.com",
          "regexp:^[a-z0-9][a-z0-9-]*-pa\\\\.googleapis\\\\.com$"
        ]
      }
    },
    {
      "tag": "vmess-ws-tls",
      "listen": "0.0.0.0",
      "port": 2083,
      "protocol": "vmess",
      "settings": { "clients": [] },
      "streamSettings": {
        "network": "ws",
        "security": "tls",
        "wsSettings": { "path": "/ptunnel-ws" },
        "tlsSettings": {
          "serverName": "${DOMAIN}",
          "alpn": ["h2", "http/1.1"],
          "certificates": [{
            "certificateFile": "/usr/local/etc/xray/certs/fullchain.pem",
            "keyFile": "/usr/local/etc/xray/certs/privkey.pem"
          }]
        }
      }
    },
    {
      "tag": "trojan-tls",
      "listen": "0.0.0.0",
      "port": 2053,
      "protocol": "trojan",
      "settings": { "clients": [] },
      "streamSettings": {
        "network": "tcp",
        "security": "tls",
        "tlsSettings": {
          "serverName": "${DOMAIN}",
          "alpn": ["h2", "http/1.1"],
          "certificates": [{
            "certificateFile": "/usr/local/etc/xray/certs/fullchain.pem",
            "keyFile": "/usr/local/etc/xray/certs/privkey.pem"
          }]
        }
      }
    },
    {
      "tag": "api",
      "listen": "127.0.0.1",
      "port": 10085,
      "protocol": "dokodemo-door",
      "settings": { "address": "127.0.0.1", "network": "tcp" }
    },
    {
      "tag": "kcp-salamander",
      "listen": "0.0.0.0",
      "port": 8443,
      "protocol": "vless",
      "settings": { "clients": [], "decryption": "none" },
      "streamSettings": {
        "network": "kcp",
        "security": "tls",
        "tlsSettings": {
          "certificates": [{
            "certificateFile": "/usr/local/etc/xray/certs/fullchain.pem",
            "keyFile": "/usr/local/etc/xray/certs/privkey.pem"
          }]
        },
        "kcpSettings": {
          "mtu": 1200, "tti": 20, "uplinkCapacity": 1000, "downlinkCapacity": 1000,
          "congestion": false, "readBufferSize": 32, "writeBufferSize": 32
        },
        "finalmask": {
          "udp": [{ "type": "salamander", "settings": { "password": "IqE5nHey2xqXXiew2xh9lMnhy4MdCuh4" } }]
        }
      }
    }
  ],
  "outbounds": [
    { "protocol": "freedom", "tag": "direct" },
    { "protocol": "blackhole", "tag": "block" }
  ],
  "routing": {
    "rules": [
      { "type": "field", "inboundTag": ["api"], "outboundTag": "api" },
      { "type": "field", "ip": ["geoip:private"], "outboundTag": "block" }
    ]
  }
}
EOF

# 3. Hysteria configuration
cat > /etc/hysteria/config.yaml <<EOF
listen: :443

tls:
  cert: /etc/hysteria/certs/fullchain.pem
  key: /etc/hysteria/certs/privkey.pem

auth:
  type: http
  http:
    url: http://127.0.0.1:3000/internal/hysteria/auth?key=7b6463a893641e25a6d93e757df56de43bd55c218d03b198

trafficStats:
  listen: 127.0.0.1:9999
  secret: 109f93514c9e56e468a060ca97dca9b0adceaca8d2a270d2

masquerade:
  type: proxy
  proxy:
    url: https://www.microsoft.com/
    rewriteHost: true
EOF

# 4. Backend .env
cat > /opt/ptunnel/backend/.env <<EOF
PORT=3000
NODE_ENV=production
DB_PATH=/opt/ptunnel/data/ptunnel.db
PUBLIC_DOMAIN=${DOMAIN}
PUBLIC_HOST=${HOST}
PUBLIC_BASE_URL=https://${DOMAIN}
USER_JWT_SECRET=baf998de7a611c9072b25e41141264c9231948ad581f4c4ea4044bfe7fb55f3b
ADMIN_JWT_SECRET=2bdb5ff1e5d7f41775f6e8ffa15b9055060e08d9bfd50fdf55c63b4331f35e8d
INTERNAL_SECRET=7b6463a893641e25a6d93e757df56de43bd55c218d03b198
XRAY_API_ADDR=127.0.0.1:10085
XRAY_BIN=/usr/local/bin/xray
REALITY_PUBLIC_KEY=${REALITY_PUB}
REALITY_SHORT_ID=${REALITY_SID}
REALITY_SNI=www.microsoft.com
HYSTERIA_STATS_URL=http://127.0.0.1:9999
HYSTERIA_STATS_SECRET=109f93514c9e56e468a060ca97dca9b0adceaca8d2a270d2
SS_METHOD=2022-blake3-aes-128-gcm
SS_SERVER_KEY=s6SSNWcHBF5WPRA17qCG+A==
POLL_INTERVAL_MS=30000
RECONCILE_INTERVAL_MS=600000
SEED_ADMIN_USERNAME=admin
SEED_ADMIN_PASSWORD=q8reo8oce394d9oz3VBFxU5v
KCP_SALAMANDER_PASSWORD=IqE5nHey2xqXXiew2xh9lMnhy4MdCuh4
TUNNEL_SNI=${DOMAIN}
PUBLIC_API_BASE=https://${DOMAIN}/v1/
REALITY_PUBLIC_PORT=443
EOF

chown -R ptunnel:ptunnel /opt/ptunnel/backend

# 5. Systemd services
cat > /etc/systemd/system/xray.service <<'EOF'
[Unit]
Description=Xray Service
Documentation=https://xtls.github.io/
After=network.target nss-lookup.target

[Service]
User=root
CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_BIND_SERVICE CAP_NET_RAW
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_BIND_SERVICE CAP_NET_RAW
NoNewPrivileges=yes
ExecStart=/usr/local/bin/xray run -config /usr/local/etc/xray/config.json
Restart=on-failure
RestartPreventExitStatus=23
LimitNPROC=10000
LimitNOFILE=1000000

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/hysteria-server.service <<'EOF'
[Unit]
Description=Hysteria Server Service (config.yaml)
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/hysteria server --config /etc/hysteria/config.yaml
WorkingDirectory=/
User=root
Environment=HYSTERIA_LOG_LEVEL=info
CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_BIND_SERVICE CAP_NET_RAW
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_BIND_SERVICE CAP_NET_RAW

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/ptunnel-backend.service <<'EOF'
[Unit]
Description=PTunnel Backend API
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/ptunnel/backend
EnvironmentFile=/opt/ptunnel/backend/.env
ExecStart=/usr/bin/node /opt/ptunnel/backend/src/index.js
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

# 6. Nginx configuration
cat > /etc/nginx/sites-available/ptunnel <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN} _;

    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name ${DOMAIN} _;

    ssl_certificate     /etc/letsencrypt/live/${DOMAIN}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${DOMAIN}/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;

    client_max_body_size 64M;

    location /v1/ {
        proxy_pass http://127.0.0.1:3000/v1/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 30s;
    }

    location = /healthz {
        default_type text/plain;
        return 200 "ok\n";
    }

    location = /b {
        alias /var/www/ptu-bootstrap/bootstrap.json;
        default_type application/json;
        add_header Cache-Control "no-store" always;
    }

    location = /s {
        alias /var/www/ptu-bootstrap/status.json;
        default_type application/json;
        add_header Cache-Control "no-store" always;
    }

    location /dl/ {
        alias /var/www/ptu-dl/;
        add_header Cache-Control "public, max-age=86400" always;
    }

    location = /dl {
        alias /var/www/ptu-dl/PTunnel-latest.apk;
        default_type application/vnd.android.package-archive;
        add_header Content-Disposition "attachment; filename=PTunnel-latest.apk" always;
    }

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
}
EOF

ln -sf /etc/nginx/sites-available/ptunnel /etc/nginx/sites-enabled/ptunnel
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl restart nginx

# 7. Port hopping
iptables -t nat -C PREROUTING -p udp --dport 20000:50000 -j REDIRECT --to-ports 443 2>/dev/null || \
iptables -t nat -A PREROUTING -p udp --dport 20000:50000 -j REDIRECT --to-ports 443

# 8. Start and enable services
systemctl daemon-reload
systemctl enable --now xray hysteria-server ptunnel-backend

echo "=== Germany 2 successfully configured and started! ==="
EOF
