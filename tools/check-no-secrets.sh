#!/usr/bin/env bash
# Fails when a tracked file looks like it carries a credential. Deliberately pattern-based and boring:
# it is the CI gate that makes "no secrets in the repo" enforceable instead of aspirational.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
files=$(git ls-files)
[ -z "$files" ] && { echo "nothing tracked yet"; exit 0; }
hits=$(printf '%s\n' "$files" | xargs grep -nEI \
  --exclude='*.md' --exclude='check-no-secrets.sh' \
  '(BEGIN (RSA|OPENSSH|EC|DSA|PRIVATE) KEY|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36}|xox[baprs]-[A-Za-z0-9-]{10,}|-----BEGIN|password[[:space:]]*[:=][[:space:]]*[^"'\''[:space:]]{6,}|secret[[:space:]]*[:=][[:space:]]*[^"'\''[:space:]]{6,})' \
  2>/dev/null | grep -viE 'example|placeholder|CHANGEME|xxx+|your-|token_placeholder' || true)
if [ -n "$hits" ]; then
  echo "possible secrets found:"; printf '%s\n' "$hits"; exit 1
fi
echo "no secrets found in tracked content"
