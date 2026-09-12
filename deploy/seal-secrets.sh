#!/usr/bin/env bash
set -euo pipefail

# Maakt deploy/base/sealed-secret.yaml: alle SF_*-sleutels uit deploy/secrets-cluster.env
# plus projects.yaml (als sleutel SF_PROJECTS_YAML, door de Deployment als bestand gemount).
# De repository is publiek en projects.yaml bevat chat-id's, daarom gaat ook dat bestand versleuteld
# mee in plaats van als ConfigMap.

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${SF_SEAL_SOURCE:-${DEPLOY_DIR}/secrets-cluster.env}"
PROJECTS="${SF_SEAL_PROJECTS:-${DEPLOY_DIR}/projects-cluster.yaml}"
# Het sealed-secrets public cert leeft alleen in robberts-infrastructure (gedeeld met alle apps);
# een lokale kopie kon stil verouderen omdat de sealed-secrets-key periodiek roteert.
CERT="${DEPLOY_DIR}/../../robberts-infrastructure/manifests/cluster-bootstrap/cluster-cert.pem"
OUT="${DEPLOY_DIR}/base/sealed-secret.yaml"
NAMESPACE="${SF_DASHBOARD_NAMESPACE:-software-factory}"
SECRET_NAME="${SF_SECRET_NAME:-software-factory-secrets}"

command -v kubeseal >/dev/null 2>&1 || { echo "Error: kubeseal niet gevonden in PATH." >&2; exit 1; }
[[ -f "$SRC" ]] || { echo "Error: secret source bestaat niet: $SRC (kopieer deploy/secrets-cluster.env.example)." >&2; exit 1; }
[[ -f "$PROJECTS" ]] || { echo "Error: projectconfiguratie bestaat niet: $PROJECTS." >&2; exit 1; }
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
    [[ "$key" == "SF_PROJECTS_YAML" ]] && { echo "Error: SF_PROJECTS_YAML komt uit $PROJECTS, niet uit $SRC." >&2; exit 1; }

    printf '  %s: |-\n' "$key"
    printf '%s\n' "$val" | sed 's/^/    /'
    count=$((count + 1))
  done < "$SRC"

  (( count > 0 )) || { echo "Error: geen SF_*-sleutels gevonden in $SRC." >&2; exit 1; }
  printf '  SF_PROJECTS_YAML: |-\n'
  sed 's/^/    /' "$PROJECTS"
  echo "[seal] $count sleutels uit $SRC en $PROJECTS -> $OUT" >&2
} > "$tmp"

kubeseal --cert "$CERT" -o yaml < "$tmp" > "$OUT"
echo "[seal] klaar: $OUT" >&2
