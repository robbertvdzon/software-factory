#!/usr/bin/env bash
#
# Draait de Software Factory in een lus: bij elke (her)start eerst een verse `git pull`, dan
# `mvn spring-boot:run`. Zodra de factory stopt start de lus 'm opnieuw — TENZIJ de factory een
# stop-signaal heeft achtergelaten (work/.factory-stop, geschreven door de Stop-knop in de UI).
#
# Bediening:
#   - Herstart-knop in de UI  -> app stopt (exit 0)            -> deze lus start 'm opnieuw.
#   - Stop-knop in de UI      -> app schrijft work/.factory-stop -> deze lus stopt ook.
#   - Ctrl-C in deze terminal -> stopt de lus meteen (één keer is genoeg).
#
# `git pull` gebruikt --no-rebase (merge) en GEEN reset: je eventueel openstaande lokale changes
# blijven staan. Bij een merge-conflict draait de lus de huidige code verder; los het conflict zelf op.

set -u
cd "$(dirname "$0")" || exit 1

# Zorg dat tools (mvn, git, docker, oc) gevonden worden, ook bij start via Finder/.command/Dock —
# die laden je shell-profiel (.zshrc) niet, dus PATH zou anders te kaal zijn.
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

STOP_FILE="work/.factory-stop"
LOCK_FILE="work/.factory-loop.pid"

mkdir -p work

# Single-instance: draait er al een factory-loop met een levende PID, stop dan meteen.
# (Een stale lock — proces bestaat niet meer — wordt genegeerd en overschreven.)
if [ -f "$LOCK_FILE" ] && kill -0 "$(cat "$LOCK_FILE" 2>/dev/null)" 2>/dev/null; then
  echo "[loop] Er draait al een Software Factory (PID $(cat "$LOCK_FILE")). Deze instantie stopt."
  exit 1
fi
echo $$ > "$LOCK_FILE"

# Lock opruimen bij élke exit (ook na Ctrl-C, want de INT-handler doet 'exit').
cleanup() { rm -f "$LOCK_FILE"; }
trap cleanup EXIT

# Eén Ctrl-C stopt de hele lus netjes (anders zou de lus de app gewoon weer opstarten).
trap 'echo; echo "[loop] gestopt (Ctrl-C)."; exit 0' INT

# Oud stop-signaal opruimen bij het starten van de lus.
rm -f "$STOP_FILE"

# Zorg eenmalig dat Docker + de Postgres-container draaien voordat de factory start.
# De factory verbindt met de lokale Postgres (localhost:5432) uit docker/docker-compose.yml.
ensure_docker_and_postgres() {
  # 1) Docker-daemon draaien? Zo niet: Docker Desktop starten en wachten tot 'ie er is.
  if ! docker info >/dev/null 2>&1; then
    echo "[loop] Docker draait niet — Docker Desktop starten…"
    open -a Docker >/dev/null 2>&1 || open -a "Docker Desktop" >/dev/null 2>&1 || true
    for _ in $(seq 1 60); do
      docker info >/dev/null 2>&1 && break
      sleep 2
    done
    if ! docker info >/dev/null 2>&1; then
      echo "[loop] Docker-daemon kwam niet op — start Docker handmatig en probeer opnieuw."
      exit 1
    fi
  fi

  # 2) Postgres-container starten (idempotent: doet niks als 'ie al draait).
  # Eerst een kale `docker start`: die heeft geen compose-interpolatie nodig. `docker compose up`
  # struikelde hier namelijk over de :?-verplichte dashboard-secrets in de compose-file (die staan
  # alleen in secrets.env, niet in de omgeving van launchd of een kale shell) — waardoor Postgres
  # na een reboot nooit automatisch startte en dat handmatig moest. Alleen als de container nog
  # niet bestaat valt dit terug op compose, met dummy-waarden voor die secrets: die zijn alleen
  # nodig om de file te interpoleren, de postgres-service zelf gebruikt ze niet.
  echo "[loop] Postgres-container controleren/starten…"
  if ! docker start software-factory-postgres >/dev/null 2>&1; then
    echo "[loop] container bestaat nog niet — aanmaken via docker compose…"
    SF_GOOGLE_CLIENT_ID="unused" SF_DASHBOARD_REMEMBER_SECRET="unused" SF_BRIDGE_TOKEN="unused" \
      docker compose -f docker/docker-compose.yml up -d postgres
  fi

  # 3) Wachten tot Postgres healthy is (anders kan de factory niet verbinden).
  echo "[loop] wachten tot Postgres healthy is…"
  for _ in $(seq 1 60); do
    [ "$(docker inspect software-factory-postgres --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ] && break
    sleep 2
  done
  if [ "$(docker inspect software-factory-postgres --format '{{.State.Health.Status}}' 2>/dev/null)" != "healthy" ]; then
    echo "[loop] Postgres werd niet healthy — check 'docker logs software-factory-postgres'."
    exit 1
  fi
  echo "[loop] Postgres is healthy."
}

ensure_docker_and_postgres

while true; do
  echo "[loop] $(date '+%Y-%m-%d %H:%M:%S') — git pull…"
  BEFORE=$(git rev-parse HEAD 2>/dev/null || echo "")
  if ! git pull --no-rebase; then
    echo "[loop] git pull mislukt (open changes/conflict?) — draai de huidige code verder."
  fi
  AFTER=$(git rev-parse HEAD 2>/dev/null || echo "")

  # Agent-/assistant-images herbouwen als de pull image-relevante paden raakte: de agentworker zit
  # ín agent:local, en assistant:local is FROM agent:local. De orchestrator (softwarefactory-module)
  # zelf gaat via mvn spring-boot:run en heeft geen image-rebuild nodig. Anders niet — bouwen kost tijd.
  if [ -n "$BEFORE" ] && [ "$BEFORE" != "$AFTER" ] &&
     git diff --name-only "$BEFORE" "$AFTER" | grep -qE '^(agentworker/|factory-common/|pom\.xml|Dockerfile\.(agent|assistant))'; then
    echo "[loop] image-relevante wijzigingen gedetecteerd — agent:local + assistant:local herbouwen…"
    if ! ./factory build-images; then
      echo "[loop] image-build mislukt — draai met de bestaande images verder."
    fi
  fi

  echo "[loop] factory start (mvn spring-boot:run)…"
  # factory-common eerst lokaal installeren: `mvn -pl softwarefactory` bouwt alleen die module en
  # zou anders een verouderde (of ontbrekende) factory-common uit ~/.m2 pakken.
  mvn -q -DskipTests -pl factory-common install
  mvn -pl softwarefactory spring-boot:run

  if [ -f "$STOP_FILE" ]; then
    rm -f "$STOP_FILE"
    echo "[loop] stop-signaal gezien — de lus stopt. Start dit script opnieuw om verder te gaan."
    break
  fi

  echo "[loop] factory gestopt — herstart over 2s (Ctrl-C om de lus te stoppen)…"
  sleep 2
done
