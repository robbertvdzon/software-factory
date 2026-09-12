#!/usr/bin/env bash
set -euo pipefail

# Rooktest van de lokale Compose-keten: Postgres, de Software Factory als container en de
# frontend. Bewijst dat het image bouwt en start, Flyway migreert, de dashboard-API fail-closed
# is zonder sessietoken en antwoordt met een geldig token.

ROOT="$(git rev-parse --show-toplevel)"
PROJECT="software-factory-smoke-$$"
TMP="$(mktemp -d)"
ENV_FILE="$TMP/smoke.env"
SECRETS_FILE="$TMP/smoke-secrets.env"
BACKEND_PORT="${SF_SMOKE_BACKEND_PORT:-19090}"
FRONTEND_PORT="${SF_SMOKE_FRONTEND_PORT:-19080}"
POSTGRES_PORT="${SF_SMOKE_POSTGRES_PORT:-15432}"
REMEMBER_SECRET="$(openssl rand -hex 32)"

cleanup() {
  docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f "$ROOT/docker/docker-compose.yml" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

cat > "$ENV_FILE" <<EOF
SF_LOCAL_POSTGRES_PORT=$POSTGRES_PORT
SF_LOCAL_DASHBOARD_BACKEND_PORT=$BACKEND_PORT
SF_LOCAL_DASHBOARD_FRONTEND_PORT=$FRONTEND_PORT
SF_COMPOSE_SECRETS=$SECRETS_FILE
SF_GOOGLE_CLIENT_ID=smoke.apps.googleusercontent.com
EOF
# Placeholder-secrets: de factory start zonder echte GitHub- of Runtime-toegang; de rooktest raakt
# geen story, repository of Telegram.
cat > "$SECRETS_FILE" <<EOF
SF_GITHUB_TOKEN=smoke-placeholder
SF_DATABASE_SCHEMA=software_factory_smoke
SF_TRACKER_PROJECTS=SMOKE
SF_GOOGLE_CLIENT_ID=smoke.apps.googleusercontent.com
SF_ALLOWED_EMAILS=smoke@example.com
SF_DASHBOARD_REMEMBER_SECRET=$REMEMBER_SECRET
EOF
chmod 600 "$ENV_FILE" "$SECRETS_FILE"

compose=(docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f "$ROOT/docker/docker-compose.yml")
"${compose[@]}" up -d --build

for _ in {1..90}; do
  curl --silent --fail "http://localhost:$BACKEND_PORT/healthz" >/dev/null && break
  sleep 2
done
curl --silent --fail "http://localhost:$BACKEND_PORT/healthz" >/dev/null || { "${compose[@]}" logs software-factory-backend | tail -80 >&2; exit 1; }

unauth_code="$(curl --silent --output /dev/null --write-out '%{http_code}' "http://localhost:$BACKEND_PORT/api/v1/status")"
[[ "$unauth_code" == 401 ]] || { echo "expected unauthenticated 401, got $unauth_code" >&2; exit 1; }

expires="$(( $(date +%s) + 3600 ))"
identity="smoke@example.com:$expires"
signature="$(printf '%s' "$identity" | openssl dgst -sha256 -hmac "$REMEMBER_SECRET" -hex | awk '{print $NF}')"
token="$(printf '%s:%s' "$identity" "$signature" | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
AUTH_CONFIG="$TMP/curl-auth.conf"
printf 'header = "Authorization: Bearer %s"\n' "$token" > "$AUTH_CONFIG"
chmod 600 "$AUTH_CONFIG"
unset token signature identity REMEMBER_SECRET

code="$(curl --silent --config "$AUTH_CONFIG" --output "$TMP/status.json" --write-out '%{http_code}' "http://localhost:$BACKEND_PORT/api/v1/status")"
[[ "$code" == 200 ]] && grep -Eq '"connected"[[:space:]]*:[[:space:]]*true' "$TMP/status.json" \
  || { echo "authenticated status failed: $code" >&2; cat "$TMP/status.json" >&2; exit 1; }

frontend_code="$(curl --silent --output /dev/null --write-out '%{http_code}' "http://localhost:$FRONTEND_PORT/api/v1/status")"
[[ "$frontend_code" == 401 ]] || { echo "expected frontend proxy to reach the backend (401), got $frontend_code" >&2; exit 1; }
echo 'local quickstart smoke: PASS (healthz=200 unauth=401 auth=200 connected=true proxy=401)'
