#!/bin/sh
# Validate before serving: a broken config must fail the container, not half-start it.
set -eu
: "${XRAY_CONFIG:=/etc/xray/config.json}"
xray -test -config "$XRAY_CONFIG" >/dev/null
exec xray run -config "$XRAY_CONFIG"
