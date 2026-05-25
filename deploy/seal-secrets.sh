#!/usr/bin/env bash
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${DEPLOY_DIR}/.." && pwd)"
SRC="${SF_SEAL_SOURCE:-}"
if [[ -z "$SRC" ]]; then
  if [[ -f "${DEPLOY_DIR}/secrets-cluster.env" ]]; then
    SRC="${DEPLOY_DIR}/secrets-cluster.env"
  else
    SRC="${ROOT_DIR}/secrets.env"
  fi
fi
CERT="${DEPLOY_DIR}/cluster-cert.pem"
OUT="${DEPLOY_DIR}/base/sealed-secret-dashboard.yaml"
NAMESPACE="${SF_DASHBOARD_NAMESPACE:-software-factory}"
SECRET_NAME="${SF_DASHBOARD_SECRET_NAME:-softwarefactory-dashboard-secrets}"

if ! command -v kubeseal >/dev/null 2>&1; then
  echo "Error: kubeseal niet gevonden in PATH." >&2
  exit 1
fi

if [[ ! -f "$SRC" ]]; then
  echo "Error: secret source bestaat niet: $SRC" >&2
  echo "Maak root secrets.env of deploy/secrets-cluster.env aan." >&2
  exit 1
fi

if [[ ! -f "$CERT" ]]; then
  echo "[seal] deploy/cluster-cert.pem ontbreekt; ophalen via huidige kubeconfig..." >&2
  kubeseal --fetch-cert > "$CERT"
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

{
  cat <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: ${SECRET_NAME}
  namespace: ${NAMESPACE}
type: Opaque
stringData:
EOF

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

    case "$key" in
      SF_YOUTRACK_BASE_URL|SF_YOUTRACK_TOKEN|SF_YOUTRACK_PROJECTS|SF_GITHUB_TOKEN|SF_DATABASE_URL|SF_DATABASE_SCHEMA|SF_DASHBOARD_USERNAME|SF_DASHBOARD_PASSWORD|DASHBOARD_ADMIN_PASSWORD|SF_DASHBOARD_REMEMBER_SECRET|SF_DASHBOARD_COOKIE_SECURE)
        printf '  %s: |-\n' "$key"
        printf '%s\n' "$val" | sed 's/^/    /'
        count=$((count + 1))
        ;;
    esac
  done < "$SRC"

  if (( count == 0 )); then
    echo "Error: geen dashboard secret keys gevonden in $SRC." >&2
    exit 1
  fi
  echo "[seal] $count entries uit $SRC -> $OUT" >&2
} > "$tmp"

kubeseal --cert "$CERT" -o yaml < "$tmp" > "$OUT"
echo "[seal] klaar: $OUT" >&2
