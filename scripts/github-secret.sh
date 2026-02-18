#!/usr/bin/env bash
set -euo pipefail

REPO="one-year-gap/infra"

decode_b64() {
  base64 --decode 2>/dev/null || base64 -D
}

for file in config/prod.vars.json config/prod.secrets.json; do
  jq -r 'to_entries[] | @base64' "$file" | while read -r row; do
    kv="$(printf '%s' "$row" | decode_b64)"
    k="$(printf '%s' "$kv" | jq -r '.key')"
    v="$(printf '%s' "$kv" | jq -r '.value')"
    gh secret set "$k" --repo "$REPO" --body "$v"
  done
done