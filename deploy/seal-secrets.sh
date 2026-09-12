#!/usr/bin/env bash
set -euo pipefail

# Maakt deploy/base/sealed-secret.yaml uit alle SF_*-sleutels in deploy/secrets-cluster.env.
# De projectcatalogus staat in de database (Settings-scherm) en gaat niet meer mee.

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${SF_SEAL_SOURCE:-${DEPLOY_DIR}/secrets-cluster.env}"
# Het sealed-secrets public cert leeft alleen in robberts-infrastructure (gedeeld met alle apps);
# een lokale kopie kon stil verouderen omdat de sealed-secrets-key periodiek roteert.
CERT="${DEPLOY_DIR}/../../robberts-infrastructure/manifests/cluster-bootstrap/cluster-cert.pem"
OUT="${DEPLOY_DIR}/base/sealed-secret.yaml"
NAMESPACE="${SF_DASHBOARD_NAMESPACE:-software-factory}"
SECRET_NAME="${SF_SECRET_NAME:-software-factory-secrets}"

command -v kubeseal >/dev/null 2>&1 || { echo "Error: kubeseal niet gevonden in PATH." >&2; exit 1; }
[[ -f "$SRC" ]] || { echo "Error: secret source bestaat niet: $SRC (kopieer deploy/secrets-cluster.env.example)." >&2; exit 1; }
[[ -f "$CERT" ]] || { echo "Error: $CERT bestaat niet; ververs met: kubeseal --fetch-cert > $CERT" >&2; exit 1; }

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

{
  cat <<HEADER
apiVersion: v1
kind: Secret
metadata:
  name: ${SECRET_NAME}
  namespace: ${NAMESPACE}
type: Opaque
stringData:
HEADER

  count=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" || "$line" =~ ^# ]] && continue
    [[ "$line" != *=* ]] && continue

    key="${line%%=*}"
    val="${line#*=}"
    val="${val%\"}"; val="${val#\"}"
    val="${val%\'}"; val="${val#\'}"
    [[ "$key" =~ ^SF_[A-Z0-9_]+$ ]] || { echo "Error: onverwachte sleutel '$key' in $SRC." >&2; exit 1; }

    printf '  %s: |-\n' "$key"
    printf '%s\n' "$val" | sed 's/^/    /'
    count=$((count + 1))
  done < "$SRC"

  (( count > 0 )) || { echo "Error: geen SF_*-sleutels gevonden in $SRC." >&2; exit 1; }
  echo "[seal] $count sleutels uit $SRC -> $OUT" >&2
} > "$tmp"

kubeseal --cert "$CERT" -o yaml < "$tmp" > "$OUT"
echo "[seal] klaar: $OUT" >&2
