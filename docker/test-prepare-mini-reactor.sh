#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cp "$ROOT/pom.xml" "$TMP/agent.xml"
"$ROOT/docker/prepare-mini-reactor.sh" "$TMP/agent.xml" agentworker
grep -q '<module>factory-common</module>' "$TMP/agent.xml"
grep -q '<module>agentworker</module>' "$TMP/agent.xml"
! grep -q '<module>softwarefactory</module>' "$TMP/agent.xml"
! grep -q '<module>dashboard-backend</module>' "$TMP/agent.xml"

cp "$ROOT/pom.xml" "$TMP/backend.xml"
"$ROOT/docker/prepare-mini-reactor.sh" "$TMP/backend.xml" dashboard-backend
grep -q '<module>factory-common</module>' "$TMP/backend.xml"
grep -q '<module>dashboard-backend</module>' "$TMP/backend.xml"
! grep -q '<module>softwarefactory</module>' "$TMP/backend.xml"
! grep -q '<module>agentworker</module>' "$TMP/backend.xml"

if "$ROOT/docker/prepare-mini-reactor.sh" "$TMP/backend.xml" unknown 2>/dev/null; then
  echo 'unsupported target unexpectedly accepted' >&2
  exit 1
fi
echo 'mini-reactor tests: PASS'
