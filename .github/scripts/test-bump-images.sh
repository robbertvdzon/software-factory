#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for workflow in dashboard-backend-image.yml dashboard-frontend-image.yml; do
  grep -A3 'name: Bump image tag in deploy/base' "$ROOT/.github/workflows/$workflow" | grep -q 'GH_TOKEN:' || {
    echo "$workflow must expose github.token to gh" >&2
    exit 1
  }
done

BARE="$TMP/origin.git"
SEED="$TMP/seed"
BIN="$TMP/bin"
mkdir -p "$SEED/.github/scripts" "$SEED/deploy/base" "$BIN"
git init --bare "$BARE" >/dev/null
git -C "$SEED" init -b main >/dev/null
git -C "$SEED" config user.name test
git -C "$SEED" config user.email test@example.invalid
cp "$ROOT/.github/scripts/bump-images.sh" "$SEED/.github/scripts/"
printf 'images:\n- name: example/backend\n  newName: example/backend\n  newTag: old\n' > "$SEED/deploy/base/kustomization.yaml"
git -C "$SEED" add .
git -C "$SEED" commit -m seed >/dev/null
git -C "$SEED" remote add origin "$BARE"
git -C "$SEED" push -u origin main >/dev/null
git -C "$BARE" symbolic-ref HEAD refs/heads/main

cat > "$BIN/kustomize" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1 $2 $3" == 'edit set image' ]]
tag="${4##*:}"
sed -i.bak "s/newTag: .*/newTag: $tag/" kustomization.yaml
rm -f kustomization.yaml.bak
EOF
chmod +x "$BIN/kustomize"

cat > "$BIN/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> "$GH_LOG"
case "$1 $2" in
  'workflow run') echo 'https://github.example/actions/runs/123' ;;
  'run watch') ;;
  'api --method') ;;
  'pr view')
    if [[ "$*" == *'mergeStateStatus'* ]]; then echo CLEAN; else echo 1111111111111111111111111111111111111111; fi
    ;;
  'pr list')
    head=''
    while [[ $# -gt 0 ]]; do
      if [[ "$1" == '--head' ]]; then head="$2"; break; fi
      shift
    done
    marker="${head//\//_}"
    if [[ -n "$head" && -f "$GH_STATE/$marker" ]]; then echo 101; fi
    ;;
  'pr create')
    while [[ $# -gt 0 ]]; do
      if [[ "$1" == '--head' ]]; then marker="${2//\//_}"; touch "$GH_STATE/$marker"; break; fi
      shift
    done
    ;;
  'pr checks')
    if [[ ! -f "$GH_STATE/checks-visible" ]]; then
      touch "$GH_STATE/checks-visible"
      echo "no checks reported on the branch" >&2
      exit 1
    fi
    ;;
esac
EOF
chmod +x "$BIN/gh"
mkdir -p "$TMP/gh-state"

run_bump() {
  local checkout="$1" run="$2" sha="$3" tag="$4"
  (cd "$checkout" && PATH="$BIN:$PATH" GH_LOG="$TMP/gh.log" GH_STATE="$TMP/gh-state" GITHUB_REPOSITORY=test/repo \
    .github/scripts/bump-images.sh backend "$run" "$sha" deploy/base "ci: bump backend to $tag" "example/backend=example/backend:$tag")
}

A="$TMP/a"
B="$TMP/b"
git clone "$BARE" "$A" >/dev/null
git clone "$BARE" "$B" >/dev/null

# A publishes an older PR branch and pauses. B then publishes and merges its newer state.
run_bump "$A" 100 1111111 sha-old
run_bump "$B" 200 2222222 sha-new
# B proactively makes A ineligible, even if A never resumes.
grep -q 'pr close 101' "$TMP/gh.log"
git -C "$B" push origin automation/image-bump-backend-200:main >/dev/null

# When A resumes, it must close its PR as superseded and may not touch main.
run_bump "$A" 100 1111111 sha-old
git --git-dir="$BARE" show main:deploy/base/kustomization.yaml > "$TMP/final-kustomization.yaml"
git --git-dir="$BARE" show main:.github/image-bumps/backend.state > "$TMP/final-state"
if ! grep -q 'sha-new' "$TMP/final-kustomization.yaml"; then
  echo 'final manifest was unexpectedly downgraded:' >&2
  sed -n '1,40p' "$TMP/final-kustomization.yaml" >&2
  exit 1
fi
grep -q 'run_id=200' "$TMP/final-state"
grep -q 'pr comment 101 .*Superseded by newer backend image run 200' "$TMP/gh.log"
grep -q 'pr close 101' "$TMP/gh.log"

# A rerun and B rerun each reuse their run-specific branch/PR instead of creating duplicates.
[[ "$(grep -c '^pr create ' "$TMP/gh.log")" -eq 2 ]]
grep -q "api --method POST repos/test/repo/statuses/.* -f state=success -f context=Backend verification" "$TMP/gh.log"
grep -q '^pr view 101 --json headRefOid' "$TMP/gh.log"
echo "bump-images integration: PASS"
