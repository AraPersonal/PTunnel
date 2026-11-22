#!/usr/bin/env bash
# ==============================================================================
# PTunnel Automated End-to-End Node Bootstrap & Cluster Integration
# ==============================================================================
set -euo pipefail

NODE_IP="${1:?usage: bootstrap-new-server.sh <NODE_IP> [DOMAIN] [SERVER_NAME] [COUNTRY] [FLAG]}"
DOMAIN="${2:-}"
SERVER_NAME="${3:-Node}"
COUNTRY="${4:-Unknown}"
FLAG="${5:-🌐}"

DASH_IP="$(echo "$NODE_IP" | tr '.' '-')"
if [ -z "$DOMAIN" ]; then
  DOMAIN="${DASH_IP}.nip.io"
fi

SERVER_ID="srv_$(echo "${SERVER_NAME}" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9' '_' | sed 's/_$//')"

echo "=========================================================="
echo " Bootstrapping PTunnel Server: ${SERVER_NAME} (${SERVER_ID})"
echo " Host IP: ${NODE_IP}"
echo " Domain:  ${DOMAIN}"
echo " Flag:    ${FLAG}"
echo "=========================================================="

export DEBIAN_FRONTEND=noninteractive

# 1. Base system setup
apt-get update -qq
apt-get install -y -qq curl ca-certificates jq unzip nftables iptables ufw software-properties-common sqlite3 certbot nginx

# 2. Kernel optimization (BBR & FQ)
cat > /etc/sysctl.d/90-ptunnel.conf <<'EOF'
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
net.ipv4.ip_forward = 1
net.ipv6.conf.all.forwarding = 1
EOF
sysctl --system >/dev/null 2>&1 || true

# 3. Docker install
if ! command -v docker >/dev/null 2>&1; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo \
    "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
    tee /etc/apt/sources.list.d/docker.list > /dev/null
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin
  systemctl enable --now docker
fi

# 4. Folder structure
mkdir -p /opt/ptunnel/{config,config/certs,config/awg,data,backups}
chmod 755 /opt/ptunnel
chmod 777 /opt/ptunnel/data

# 5. SSL Certificates (Nginx & Hysteria)
mkdir -p /var/www/html /etc/letsencrypt/live/${DOMAIN}
if ! certbot certonly --standalone --preferred-challenges http -d "${DOMAIN}" --non-interactive --agree-tos --register-unsafely-without-email >/dev/null 2>&1; then
  echo "Generating self-signed SSL certificate for ${DOMAIN}..."
  openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout /etc/letsencrypt/live/${DOMAIN}/privkey.pem \
    -out /etc/letsencrypt/live/${DOMAIN}/fullchain.pem \
    -subj "/CN=${DOMAIN}" >/dev/null 2>&1
fi

openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -days 365 -nodes \
  -keyout /opt/ptunnel/config/certs/privkey.pem \
  -out /opt/ptunnel/config/certs/fullchain.pem \
  -subj "/CN=${DOMAIN}" >/dev/null 2>&1

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
        proxy_pass http://127.0.0.1:8080/v1/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 30s;
    }

    location = /healthz {
        proxy_pass http://127.0.0.1:8080/healthz;
    }

    location = /version {
        proxy_pass http://127.0.0.1:8080/version;
    }

    location /dl/ {
        alias /var/www/ptu-dl/;
        add_header Cache-Control "public, max-age=86400" always;
    }

    location = /dl {
        alias /var/www/ptu-dl/PTunnel-latest.apk;
        default_type application/vnd.android.package-archive;
        add_header Content-Disposition "attachment; filename=PTunnel-latest.apk" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header Cache-Control "no-store" always;
        gzip off;
        access_log off;
    }

    location / {
        proxy_pass http://127.0.0.1:8080;
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
nginx -t && systemctl reload nginx || systemctl restart nginx

# 7. Hysteria 2 Port Hopping (20000:50000 -> 443)
iptables -t nat -C PREROUTING -p udp --dport 20000:50000 -j REDIRECT --to-ports 443 2>/dev/null || \
iptables -t nat -A PREROUTING -p udp --dport 20000:50000 -j REDIRECT --to-ports 443

# 8. Configure Core Services (Xray & Hysteria2)
cat > /opt/ptunnel/config/xray.json <<'EOF'
{
  "log": { "loglevel": "warning" },
  "api": {
    "tag": "api",
    "services": ["HandlerService", "StatsService", "LoggerService"]
  },
  "stats": {},
  "policy": {
    "levels": {
      "0": { "statsUserUplink": true, "statsUserDownlink": true, "statsUserOnline": true }
    },
    "system": { "statsInboundUplink": true, "statsInboundDownlink": true }
  },
  "inbounds": [
    {
      "tag": "api",
      "listen": "127.0.0.1",
      "port": 10085,
      "protocol": "dokodemo-door",
      "settings": { "address": "127.0.0.1" }
    },
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
          "privateKey": "REPLACE_REALITY_PRIVATE_KEY",
          "shortIds": ["2a18498b611d7ac7"]
        }
      },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"] }
    },
    {
      "tag": "kcp-salamander",
      "listen": "0.0.0.0",
      "port": 8443,
      "protocol": "vless",
      "settings": { "clients": [], "decryption": "none" },
      "streamSettings": {
        "network": "kcp",
        "kcpSettings": {
          "mtu": 1350,
          "tti": 20,
          "uplinkCapacity": 100,
          "downlinkCapacity": 100,
          "congestion": false,
          "readBufferSize": 4,
          "writeBufferSize": 4,
          "seed": "IqE5nHey2xqXXiew2xh9lMnhy4MdCuh4",
          "header": { "type": "salamander" }
        }
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
        "tlsSettings": {
          "certificates": [{ "certificateFile": "/opt/ptunnel/config/certs/fullchain.pem", "keyFile": "/opt/ptunnel/config/certs/privkey.pem" }]
        },
        "wsSettings": { "path": "/ptunnel-ws" }
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
          "certificates": [{ "certificateFile": "/opt/ptunnel/config/certs/fullchain.pem", "keyFile": "/opt/ptunnel/config/certs/privkey.pem" }]
        }
      }
    }
  ],
  "outbounds": [
    { "protocol": "freedom", "tag": "direct" },
    { "protocol": "blackhole", "tag": "blocked" }
  ]
}
EOF

# Use default shared reality private key or generate
sed -i 's|REPLACE_REALITY_PRIVATE_KEY|yGSmQYpW8G89fE9Jm7Q97K5T0K5t5mZ2yZ0Y9W8G89c=|g' /opt/ptunnel/config/xray.json

cat > /opt/ptunnel/config/hysteria.yaml <<EOF
listen: :443

tls:
  cert: /etc/hysteria/certs/fullchain.pem
  key: /etc/hysteria/certs/privkey.pem

auth:
  type: http
  http:
    url: http://127.0.0.1:8080/internal/hysteria/auth

obfs:
  type: salamander
  salamander:
    password: "IqE5nHey2xqXXiew2xh9lMnhy4MdCuh4"

masquerade:
  type: proxy
  proxy:
    url: https://www.bing.com
    rewriteHost: true
EOF

cat > /opt/ptunnel/config/awg/wg0.conf <<EOF
[Interface]
Address = 10.8.0.1/24
ListenPort = 51820
PrivateKey = REPLACE_AWG_SERVER_KEY
Jc = 4
Jmin = 40
Jmax = 70
S1 = 15
S2 = 25
H1 = 1
H2 = 2
H3 = 3
H4 = 4
EOF

# 9. Docker Compose
cat > /opt/ptunnel/compose.yml <<EOF
name: ptunnel-prod

services:
  xray:
    image: ghcr.io/arapersonal/ptunnel/xray:main
    container_name: ptunnel-xray
    network_mode: host
    restart: unless-stopped
    volumes:
      - ./config/xray.json:/etc/xray/config.json:ro
      - ./config/certs:/opt/ptunnel/config/certs:ro
    environment:
      XRAY_CONFIG: /etc/xray/config.json

  hysteria:
    image: ghcr.io/arapersonal/ptunnel/hysteria:main
    container_name: ptunnel-hysteria
    network_mode: host
    restart: unless-stopped
    volumes:
      - ./config/hysteria.yaml:/etc/hysteria/config.yaml:ro
      - ./config/certs:/etc/hysteria/certs:ro

  panel:
    image: ghcr.io/arapersonal/ptunnel/panel:main
    container_name: ptunnel-panel
    network_mode: host
    restart: unless-stopped
    volumes:
      - ./data:/app/data
    environment:
      NODE_ENV: production
      PORT: 8080
      DB_PATH: /app/data/ptunnel.db
      PUBLIC_HOST: ${NODE_IP}
      PUBLIC_DOMAIN: ${DOMAIN}
      PUBLIC_BASE_URL: https://${DOMAIN}
      USER_JWT_SECRET: baf998de7a611c9072b25e41141264c9231948ad581f4c4ea4044bfe7fb55f3b
      ADMIN_JWT_SECRET: 2bdb5ff1e5d7f41775f6e8ffa15b9055060e08d9bfd50fdf55c63b4331f35e8d
      INTERNAL_SECRET: 7b6463a893641e25a6d93e757df56de43bd55c218d03b198
      XRAY_API_ADDR: 127.0.0.1:10085
EOF

# 10. Start Docker Stack
cd /opt/ptunnel
docker compose pull --quiet || true
docker compose up -d

echo "PTunnel node provisioning successfully finished on ${DOMAIN}!"
EOF
