#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
PROJECT="software-factory-smoke-$$"
TMP="$(mktemp -d)"
ENV_FILE="$TMP/smoke.env"
FACTORY_LOG="$TMP/factory.log"
BACKEND_PORT="${SF_SMOKE_BACKEND_PORT:-19090}"
FRONTEND_PORT="${SF_SMOKE_FRONTEND_PORT:-19080}"
POSTGRES_PORT="${SF_SMOKE_POSTGRES_PORT:-15432}"
FACTORY_PORT="${SF_SMOKE_FACTORY_PORT:-18080}"
BRIDGE_SECRET="$(openssl rand -hex 24)"
REMEMBER_SECRET="$(openssl rand -hex 32)"
FACTORY_PID=''

cleanup() {
  [[ -z "$FACTORY_PID" ]] || kill "$FACTORY_PID" 2>/dev/null || true
  docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f "$ROOT/docker/docker-compose.yml" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

cat > "$ENV_FILE" <<EOF
SF_LOCAL_POSTGRES_PORT=$POSTGRES_PORT
SF_LOCAL_DASHBOARD_BACKEND_PORT=$BACKEND_PORT
SF_LOCAL_DASHBOARD_FRONTEND_PORT=$FRONTEND_PORT
SF_GOOGLE_CLIENT_ID=smoke.apps.googleusercontent.com
SF_ALLOWED_EMAILS=smoke@example.com
SF_DASHBOARD_REMEMBER_SECRET=$REMEMBER_SECRET
SF_DASHBOARD_REMEMBER_DAYS=30
SF_DASHBOARD_COOKIE_SECURE=false
SF_BRIDGE_TOKEN=$BRIDGE_SECRET
EOF
chmod 600 "$ENV_FILE"

compose=(docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f "$ROOT/docker/docker-compose.yml")
"${compose[@]}" up -d --build

for _ in {1..60}; do
  curl --silent --fail "http://localhost:$BACKEND_PORT/healthz" >/dev/null && break
  sleep 2
done
curl --silent --fail "http://localhost:$BACKEND_PORT/healthz" >/dev/null

export SF_GITHUB_TOKEN=smoke-placeholder
export SF_DATABASE_URL="postgresql://software_factory:software_factory@localhost:$POSTGRES_PORT/software_factory"
export SF_DATABASE_SCHEMA=software_factory_smoke
export SF_BRIDGE_URLS="ws://localhost:$BACKEND_PORT/bridge"
export SF_BRIDGE_TOKEN="$BRIDGE_SECRET"
export SERVER_PORT="$FACTORY_PORT"
(cd "$ROOT" && ./factory start >"$FACTORY_LOG" 2>&1) &
FACTORY_PID=$!

unauth_code="$(curl --silent --output /dev/null --write-out '%{http_code}' "http://localhost:$BACKEND_PORT/api/v1/status")"
[[ "$unauth_code" == 401 ]] || { echo "expected unauthenticated 401, got $unauth_code" >&2; exit 1; }

expires="$(( $(date +%s) + 3600 ))"
identity="smoke@example.com:$expires"
signature="$(printf '%s' "$identity" | openssl dgst -sha256 -hmac "$REMEMBER_SECRET" -hex | awk '{print $NF}')"
token="$(printf '%s:%s' "$identity" "$signature" | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
AUTH_CONFIG="$TMP/curl-auth.conf"
printf 'header = "Authorization: Bearer %s"\n' "$token" > "$AUTH_CONFIG"
chmod 600 "$AUTH_CONFIG"
unset token signature identity BRIDGE_SECRET REMEMBER_SECRET

connected=false
for _ in {1..90}; do
  code="$(curl --silent --config "$AUTH_CONFIG" --output "$TMP/status.json" --write-out '%{http_code}' "http://localhost:$BACKEND_PORT/api/v1/status")"
  if [[ "$code" == 200 ]] && grep -Eq '"connected"[[:space:]]*:[[:space:]]*true' "$TMP/status.json"; then
    connected=true
    break
  fi
  sleep 2
done
[[ "$connected" == true ]] || { echo 'authenticated bridge never reached connected=true' >&2; tail -80 "$FACTORY_LOG" >&2; exit 1; }
echo 'local quickstart smoke: PASS (healthz=200 unauth=401 auth=200 connected=true)'
