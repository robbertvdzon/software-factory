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

STOP_FILE="work/.factory-stop"

# Eén Ctrl-C stopt de hele lus netjes (anders zou de lus de app gewoon weer opstarten).
trap 'echo; echo "[loop] gestopt (Ctrl-C)."; exit 0' INT

# Oud stop-signaal opruimen bij het starten van de lus.
rm -f "$STOP_FILE"

while true; do
  echo "[loop] $(date '+%Y-%m-%d %H:%M:%S') — git pull…"
  if ! git pull --no-rebase; then
    echo "[loop] git pull mislukt (open changes/conflict?) — draai de huidige code verder."
  fi

  echo "[loop] factory start (mvn spring-boot:run)…"
  mvn -pl softwarefactory spring-boot:run

  if [ -f "$STOP_FILE" ]; then
    rm -f "$STOP_FILE"
    echo "[loop] stop-signaal gezien — de lus stopt. Start dit script opnieuw om verder te gaan."
    break
  fi

  echo "[loop] factory gestopt — herstart over 2s (Ctrl-C om de lus te stoppen)…"
  sleep 2
done
