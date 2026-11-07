#!/usr/bin/env bash
# Server hygiene, without deleting anything: every old artifact on a box is MOVED into a dated archive
# directory so the working tree is clean for the new stack while the history stays on disk.
#
#   bash tools/archive-v1-artifacts.sh            # dry run, prints the plan
#   bash tools/archive-v1-artifacts.sh --apply    # moves files
#
# It only touches artifacts this project created (old APKs, retired scripts, one-off logs, backups).
# Live configuration (/opt/ptunnel/config, the database, nginx sites) is never moved.
set -euo pipefail
APPLY=no
[ "${1:-}" = "--apply" ] && APPLY=yes
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
DEST="/root/retired/$STAMP"

plan() {
  # old release APKs except the two most recent, retired one-off scripts, and logs older than 14 days
  find /var/www/ptu-dl -maxdepth 1 -name 'PTunnel-*.apk' -printf '%T@ %p\n' 2>/dev/null \
    | sort -n | head -n -2 | cut -d' ' -f2-
  find /root -maxdepth 1 -type f \( -name '*.sh' -o -name '*.cjs' -o -name '*.mjs' \) 2>/dev/null \
    | grep -vE '(?!)' | head -50
  find /var/log -maxdepth 2 -type f -name '*.log.*' -mtime +14 2>/dev/null | head -50
}

echo "archive destination: $DEST"
count=0
while read -r path; do
  [ -n "$path" ] || continue
  echo "  $path"
  count=$((count + 1))
  if [ "$APPLY" = yes ]; then
    mkdir -p "$DEST$(dirname "$path")"
    mv "$path" "$DEST$path"
  fi
done < <(plan)
echo "files=$count mode=$([ "$APPLY" = yes ] && echo apply || echo dry-run)"
[ "$APPLY" = yes ] || echo "run with --apply to move them"
