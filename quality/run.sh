#!/usr/bin/env bash
#
# Quality-run (stap 1): Detekt op alleen de main-source van softwarefactory.
# Produceert:
#   qualityrun/<timestamp>/  -> detekt.xml, detekt.md, quality-score.json, latest.md
#   qualityrun/latest.md     -> altijd de laatste run (voor snel kijken)
#
# Detekt parseert broncode -> geen compile/test/DB nodig (snel).
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TS="$(date +%Y-%m-%dT%H-%M-%S)"
OUT="qualityrun/$TS"
mkdir -p "$OUT"

echo "→ Detekt draaien (alleen main-code, tests uitgesloten)…"
# report-only: detekt mag de exitcode niet laten falen (maxIssues staat hoog),
# maar we vangen een eventuele non-zero toch af zodat de wrapper doorloopt.
mvn -q -pl softwarefactory -P quality detekt:check || true

REPORTS="softwarefactory/target/detekt"
cp "$REPORTS/detekt.xml" "$OUT/detekt.xml" 2>/dev/null || { echo "GEEN detekt.xml gevonden — detekt is niet gedraaid"; exit 1; }
cp "$REPORTS/detekt.md"  "$OUT/detekt.md"  2>/dev/null || true

# --- metrics uit de checkstyle-achtige detekt.xml halen --------------------
# Elk <error ... source="detekt.RuleName"/> is één bevinding.
TOTAL="$(grep -c '<error ' "$OUT/detekt.xml" 2>/dev/null || true)"
SUPPRESS="$( { grep -REo '@Suppress|@SuppressWarnings|// *detekt:disable|// *ktlint-disable' softwarefactory/src/main/kotlin 2>/dev/null || true; } | wc -l | tr -d ' ')"

# per-rule telling -> JSON-fragment
BYRULE="$(grep -o 'source="[^"]*"' "$OUT/detekt.xml" \
  | sed 's/source="//; s/"$//' \
  | sort | uniq -c | sort -rn \
  | awk '{printf "%s    \"%s\": %s", (NR>1?",\n":""), $2, $1}')"

# --- quality-score.json (machine) ------------------------------------------
cat > "$OUT/quality-score.json" <<JSON
{
  "timestamp": "$TS",
  "tool": "detekt",
  "scope": "main",
  "totalFindings": ${TOTAL:-0},
  "suppressions": ${SUPPRESS:-0},
  "byRule": {
$BYRULE
  }
}
JSON
cp "$OUT/quality-score.json" qualityrun/quality-score.json

# --- latest.md (mens) ------------------------------------------------------
{
  echo "# Quality run — $TS"
  echo
  echo "**Tool:** detekt · **Scope:** main-code (tests uitgesloten)"
  echo
  echo "| Metric | Waarde |"
  echo "|---|---|"
  echo "| Totaal bevindingen | ${TOTAL:-0} |"
  echo "| Suppressies (@Suppress / disable) | ${SUPPRESS:-0} |"
  echo
  echo "## Top regels (meeste bevindingen)"
  echo
  grep -o 'source="[^"]*"' "$OUT/detekt.xml" \
    | sed 's/source="//; s/"$//' | sort | uniq -c | sort -rn | head -20 \
    | awk '{printf "- **%s** — %s\n", $2, $1}'
  echo
  echo "_Volledig detekt-rapport: \`$OUT/detekt.md\`_"
} > "$OUT/latest.md"
cp "$OUT/latest.md" qualityrun/latest.md

echo "✓ Klaar:"
echo "  $OUT/latest.md            (mens)"
echo "  $OUT/quality-score.json   (machine)"
echo "  qualityrun/latest.md      (snelkoppeling naar laatste)"
