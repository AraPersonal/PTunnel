#!/bin/sh
# AmneziaWG container entrypoint
set -eu

: "${AWG_CONFIG:=/etc/amnezia/amneziawg/wg0.conf}"
[ -r "$AWG_CONFIG" ] || { echo "AmneziaWG config not readable: $AWG_CONFIG"; exit 1; }

# Enable IPv4 forwarding inside network namespace if available
sysctl -w net.ipv4.ip_forward=1 2>/dev/null || true

echo "Starting AmneziaWG interface with config: $AWG_CONFIG"
if [ -x /usr/local/bin/awg-quick ]; then
  awg-quick up "$AWG_CONFIG"
else
  amneziawg-go wg0
  awg setconf wg0 "$AWG_CONFIG"
  ip link set up dev wg0
fi

cleanup() {
  echo "Shutting down AmneziaWG..."
  awg-quick down "$AWG_CONFIG" 2>/dev/null || ip link set down dev wg0 2>/dev/null || true
  exit 0
}

trap cleanup SIGTERM SIGINT

# Keep running and monitor interface
while true; do
  sleep 30 &
  wait $!
done
