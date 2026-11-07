#!/bin/sh
# Refuse to start with a config we cannot read: a half-started core that answers nothing is worse than
# a container that fails and is restarted visibly.
set -eu
: "${HYSTERIA_CONFIG:=/etc/hysteria/config.yaml}"
[ -r "$HYSTERIA_CONFIG" ] || { echo "config not readable: $HYSTERIA_CONFIG"; exit 1; }
exec hysteria server -c "$HYSTERIA_CONFIG"
