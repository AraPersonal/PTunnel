#!/usr/bin/env bash
# ==============================================================================
# PTunnel Cascading Fleet Update Script
# Triggered by Main Server to propagate container updates to all fleet workers.
# ==============================================================================
set -euo pipefail

TAG="${1:-main}"
REPO="${2:-arapersonal/ptunnel}"

echo "=========================================================="
echo " Starting Cascading Fleet Update (Tag: ${TAG}, Repo: ${REPO})"
echo "=========================================================="

DB="/opt/ptunnel/data/ptunnel.db"
if [ ! -f "$DB" ]; then
  echo "Notice: Database not found at $DB; skipping worker cascade."
  exit 0
fi

# Fetch active worker nodes from servers table (excluding srv_main)
WORKERS=$(sqlite3 "$DB" "SELECT host FROM servers WHERE id != 'srv_main' AND is_active = 1;" || true)

if [ -z "$WORKERS" ]; then
  echo "No active worker nodes found to update."
  exit 0
fi

for HOST in $WORKERS; do
  echo "--> Propagating update to Worker Node: ${HOST}..."
  if ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 root@"${HOST}" \
    "if [ -x /usr/local/bin/ptunnel-pull.sh ]; then /usr/local/bin/ptunnel-pull.sh; fi; if [ -f /opt/ptunnel/compose.yml ]; then docker compose -f /opt/ptunnel/compose.yml pull --quiet && docker compose -f /opt/ptunnel/compose.yml up -d --remove-orphans; fi"; then
    echo "    Worker ${HOST} successfully updated!"
  else
    echo "    Warning: Update failed or timed out for worker ${HOST}."
  fi
done

echo "=========================================================="
echo " Fleet Update Cascade Completed"
echo "=========================================================="

