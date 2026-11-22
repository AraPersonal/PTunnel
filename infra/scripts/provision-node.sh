#!/usr/bin/env bash
# ==============================================================================
# PTunnel Automated Node Provisioning Script
# Fully configures a fresh Ubuntu/Debian VPS to become a PTunnel node.
# ==============================================================================
set -euo pipefail

NODE_IP="${1:?usage: provision-node.sh <NODE_IP> [DOMAIN] [SERVER_NAME] [FLAG]}"
DOMAIN="${2:-}"
SERVER_NAME="${3:-Node}"
FLAG="${4:-🌐}"

if [ -z "$DOMAIN" ]; then
  DASH_IP="$(echo "$NODE_IP" | tr '.' '-')"
  DOMAIN="${DASH_IP}.nip.io"
fi

echo "=========================================================="
echo " Starting PTunnel Node Provisioning on ${NODE_IP}"
echo " Domain:      ${DOMAIN}"
echo " Server Name: ${SERVER_NAME} ${FLAG}"
echo "=========================================================="

export DEBIAN_FRONTEND=noninteractive

# 1. Base packages & updates
echo "[1/7] Updating system and installing base dependencies..."
apt-get update -qq
apt-get install -y -qq \
  curl ca-certificates jq unzip nftables iptables ufw \
  software-properties-common sqlite3 certbot python3-certbot-nginx nginx

# 2. Enable BBR congestion control and fq
echo "[2/7] Enabling BBR congestion control & optimizing kernel..."
cat > /etc/sysctl.d/90-ptunnel.conf <<'EOF'
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
net.ipv4.ip_forward = 1
net.ipv6.conf.all.forwarding = 1
EOF
sysctl --system >/dev/null 2>&1 || true

# 3. Install Docker & Compose plugin (if not installed)
echo "[3/7] Setting up Docker engine..."
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

# 4. Directory structures
echo "[4/7] Creating /opt/ptunnel directory tree..."
mkdir -p /opt/ptunnel/{config,config/certs,config/awg,data,backups}
chmod 755 /opt/ptunnel
chmod 777 /opt/ptunnel/data

# 5. SSL Certificate for Nginx
echo "[5/7] Obtaining or generating SSL certificates for ${DOMAIN}..."
mkdir -p /var/www/html /etc/letsencrypt/live/${DOMAIN}
if ! certbot certonly --standalone --preferred-challenges http -d "${DOMAIN}" --non-interactive --agree-tos --register-unsafely-without-email >/dev/null 2>&1; then
  echo "Certbot standalone failed or nip.io rate-limited; generating trusted self-signed fallback certificate..."
  openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout /etc/letsencrypt/live/${DOMAIN}/privkey.pem \
    -out /etc/letsencrypt/live/${DOMAIN}/fullchain.pem \
    -subj "/CN=${DOMAIN}" >/dev/null 2>&1
fi

# Also create cert for Hysteria 2
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -days 365 -nodes \
  -keyout /opt/ptunnel/config/certs/privkey.pem \
  -out /opt/ptunnel/config/certs/fullchain.pem \
  -subj "/CN=${DOMAIN}" >/dev/null 2>&1

# 6. Configure Nginx
echo "[6/7] Configuring Nginx reverse proxy..."
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

# 7. Configure Hysteria 2 UDP Port Hopping (20000:50000 -> 443)
echo "[7/7] Configuring Hysteria 2 port hopping & iptables..."
iptables -t nat -C PREROUTING -p udp --dport 20000:50000 -j REDIRECT --to-ports 443 2>/dev/null || \
iptables -t nat -A PREROUTING -p udp --dport 20000:50000 -j REDIRECT --to-ports 443

# Ensure iptables persists across reboots
if command -v netfilter-persistent >/dev/null 2>&1; then
  netfilter-persistent save || true
fi

echo "Node base configuration complete!"
EOF
