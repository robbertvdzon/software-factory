#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
TS="$(date +%Y-%m-%dT%H-%M-%S)"
OUT="qualityrun/$TS"
BASELINE="quality/baselines/plan-07-ratchet.json"
MODULES="factory-contracts,factory-common,softwarefactory,agentworker,dashboard-backend"
mkdir -p "$OUT"

[[ -f "$BASELINE" ]] || { echo "Quality ratchet baseline ontbreekt: $BASELINE" >&2; exit 1; }
echo "→ Detekt draaien voor alle Kotlin-mainmodules…"
if ! mvn -q -Pquality -pl "$MODULES" detekt:check > "$OUT/detekt-console.log" 2>&1; then
  cat "$OUT/detekt-console.log" >&2
  exit 1
fi
python3 quality/ratchet.py collect --output "$OUT/current.json" > /dev/null
python3 quality/ratchet.py check --baseline "$BASELINE" --output "$OUT/delta.json"

python3 - "$OUT/current.json" "$OUT/quality-score.json" <<'PY'
import json, sys
current = json.load(open(sys.argv[1]))
rules = {}
for finding in current["findings"]:
    rules[finding["rule"]] = rules.get(finding["rule"], 0) + 1
result = {
    "schemaVersion": 2,
    "tool": "detekt-ratchet",
    "scope": "all-reactor-kotlin-main-modules",
    "modules": current["modules"],
    "score": len(current["findings"]) + len(current["suppressions"]),
    "totalFindings": len(current["findings"]),
    "suppressions": len(current["suppressions"]),
    "byRule": dict(sorted(rules.items())),
}
json.dump(result, open(sys.argv[2], "w"), indent=2)
open(sys.argv[2], "a").write("\n")
PY
cp "$OUT/quality-score.json" qualityrun/quality-score.json
{
  echo "# Quality ratchet — $TS"
  echo
  echo "Modules: factory-contracts, factory-common, softwarefactory, agentworker, dashboard-backend"
  echo
  echo '```json'
  cat "$OUT/delta.json"
  echo '```'
} > "$OUT/latest.md"
cp "$OUT/latest.md" qualityrun/latest.md
echo "✓ Quality ratchet groen: $OUT"
