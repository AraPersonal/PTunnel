#!/bin/bash
set -euo pipefail

# 1. System users
useradd -r -s /usr/sbin/nologin xray 2>/dev/null || true
useradd -r -s /usr/sbin/nologin hysteria 2>/dev/null || true
useradd -r -s /usr/sbin/nologin ptunnel 2>/dev/null || true

# 2. Directories
mkdir -p /opt/ptunnel/{backend,data}
mkdir -p /var/www/{ptu-bootstrap,ptu-bench,ptu-dl}
mkdir -p /var/lib/ptunnel-pull
mkdir -p /etc/hysteria
mkdir -p /usr/local/etc/xray
mkdir -p /etc/nginx/{sites-available,sites-enabled}
chown -R ptunnel:ptunnel /opt/ptunnel
chmod 777 /opt/ptunnel/data

# 3. Install ptunnel-pull.sh
cat > /usr/local/bin/ptunnel-pull.sh <<'PULL_EOF'
#!/bin/bash
set -uo pipefail
KEY=/root/.ssh/ptunnel_replicate
PRIMARY=86.54.42.124
SSHOPT=(-o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=12 -i "$KEY")
G() { ssh -n "${SSHOPT[@]}" "root@$PRIMARY" "$1"; }
say() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*"; }

declare -A DEST=(
  [bootstrap]=/var/www/ptu-bootstrap/bootstrap.json
  [status]=/var/www/ptu-bootstrap/status.json
  [cfips]=/var/www/ptu-bootstrap/cf-ips.json
  [latest]=/opt/ptunnel/app-release/latest.json
  [apk]=/var/www/ptu-dl/PTunnel-latest.apk
  [bin-xray]=/usr/local/bin/xray
  [bin-hysteria]=/usr/local/bin/hysteria
  [backend-pkg]=/opt/ptunnel/backend/package.json
  [backend-lock]=/opt/ptunnel/backend/package-lock.json
)
declare -A DESTDIR=(
  [backend-src]=/opt/ptunnel/backend
  [node-modules]=/opt/ptunnel/backend
  [admin]=/var/www
  [geo]=/usr/local/share
)
declare -A PROBE=(
  [backend-src]=/opt/ptunnel/backend/src
  [node-modules]=/opt/ptunnel/backend/node_modules
  [admin]=/var/www/admin
  [geo]=/usr/local/share/xray
)

remote_sha() { G "sha $1" 2>/dev/null | awk '{print $1}'; }
local_sha()  { local t="$1"; [ -e "$t" ] || { echo none; return; }
               if [ -d "$t" ]; then find "$t" -printf '%P\t%s\n' | LC_ALL=C sort | sha256sum | awk '{print $1}';
               else sha256sum "$t" | awk '{print $1}'; fi; }

changed=0
for item in "${!DEST[@]}"; do
  dest="${DEST[$item]}"; r=$(remote_sha "$item"); l=$(local_sha "$dest")
  if [ -n "$r" ] && [ "$r" != "$l" ]; then
    tmp=$(mktemp); if G "get $item" > "$tmp" && [ -s "$tmp" ]; then
      mode=644; case "$item" in bin-*) mode=755;; esac
      install -m "$mode" -o root -g root "$tmp" "$dest"
      case "$item" in bootstrap|status|cfips) chown ptunnel:ptunnel "$dest";; esac
      say "updated $item ($mode) -> $dest"; changed=1
    else say "FAILED $item"; rm -f "$tmp"; continue; fi
    rm -f "$tmp"
  fi
done
for item in "${!DESTDIR[@]}"; do
  dest="${DESTDIR[$item]}"; probe="${PROBE[$item]}"
  r=$(remote_sha "$item")
  l=$( [ -e "$probe" ] && find "$probe" -printf '%P\t%s\n' | LC_ALL=C sort | sha256sum | awk '{print $1}' || echo none )
  if [ -n "$r" ] && [ "$r" != "$l" ]; then
    tmp=$(mktemp); if G "get $item" > "$tmp"; then
      tar -C "$dest" -xf "$tmp" && say "updated $item -> $probe"; changed=1
    else say "FAILED $item"; fi
    rm -f "$tmp"
  fi
done

db_sha_file=/var/lib/ptunnel-pull/last-db.sha
install -d -m 700 /var/lib/ptunnel-pull
tmpdb=$(mktemp); if G "snap-db" > "$tmpdb" && [ -s "$tmpdb" ]; then
  r=$(sha256sum "$tmpdb" | awk '{print $1}'); l=$(cat "$db_sha_file" 2>/dev/null || echo none)
  if [ "$r" != "$l" ]; then
    install -m 640 -o ptunnel -g ptunnel "$tmpdb" /opt/ptunnel/data/ptunnel.db
    rm -f /opt/ptunnel/data/ptunnel.db-wal /opt/ptunnel/data/ptunnel.db-shm
    printf '%s\n' "$r" > "$db_sha_file"
    say "accounts updated ($r); restarting backend"
    systemctl --no-block restart ptunnel-backend 2>/dev/null || true
    changed=1
  fi
fi
rm -f "$tmpdb"
if [ "$changed" -eq 0 ]; then say "everything already in sync"; fi
exit 0
PULL_EOF
chmod +x /usr/local/bin/ptunnel-pull.sh

echo "--> Running initial pull from Germany 1..."
/usr/local/bin/ptunnel-pull.sh
echo "--> Initial sync completed!"

# 4. Install systemd timer and service for pull
cat > /etc/systemd/system/ptunnel-pull.service <<'EOF'
[Unit]
Description=PTunnel replica sync (pull from Germany #1)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/ptunnel-pull.sh
Nice=10
IOSchedulingClass=idle
NoNewPrivileges=yes
EOF

cat > /etc/systemd/system/ptunnel-pull.timer <<'EOF'
[Unit]
Description=Keep the PTunnel replica in sync

[Timer]
OnBootSec=30s
OnUnitActiveSec=60s
RandomizedDelaySec=5s
AccuracySec=5s

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now ptunnel-pull.timer
echo "--> ptunnel-pull.timer enabled!"
