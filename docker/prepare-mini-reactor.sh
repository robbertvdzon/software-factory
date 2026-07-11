#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 2 ]] || { echo "usage: $0 <root-pom> <target-module>" >&2; exit 2; }
pom="$1"
target="$2"
[[ -f "$pom" ]] || { echo "mini-reactor: root POM not found: $pom" >&2; exit 2; }
[[ "$target" == agentworker || "$target" == dashboard-backend ]] || {
  echo "mini-reactor: unsupported target module: $target" >&2
  exit 2
}

tmp="${pom}.mini"
awk -v target="$target" '
  /<module>[^<]+<\/module>/ {
    module = $0
    sub(/^.*<module>/, "", module)
    sub(/<\/module>.*$/, "", module)
    seen[module] = 1
    if (module != "factory-common" && module != target) next
  }
  { print }
  END {
    if (!seen["factory-common"] || !seen[target]) exit 42
  }
' "$pom" > "$tmp" || {
  status=$?
  rm -f "$tmp"
  if [[ $status -eq 42 ]]; then
    echo "mini-reactor: root POM must contain factory-common and $target" >&2
  fi
  exit "$status"
}
mv "$tmp" "$pom"
