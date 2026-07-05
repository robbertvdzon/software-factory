#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "usage: $0 <kustomization-dir> <commit-message> <image-arg> [<image-arg> ...]" >&2
  exit 2
fi

KUST_DIR="$1"
COMMIT_MSG="$2"
shift 2
IMAGE_ARGS=("$@")

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

if ! command -v kustomize >/dev/null 2>&1; then
  echo "[bump] installing kustomize..."
  curl -fsSL https://raw.githubusercontent.com/kubernetes-sigs/kustomize/master/hack/install_kustomize.sh | bash
  sudo mv kustomize /usr/local/bin/
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

MAX_ATTEMPTS=5
for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "[bump] attempt $attempt/$MAX_ATTEMPTS"

  git fetch origin main --quiet
  git reset --hard origin/main

  (
    cd "$KUST_DIR"
    # shellcheck disable=SC2068
    kustomize edit set image ${IMAGE_ARGS[@]}
  )

  if git diff --quiet; then
    echo "[bump] no manifest change."
    exit 0
  fi

  git add "$KUST_DIR/kustomization.yaml"
  git commit -m "$COMMIT_MSG"

  if git push origin HEAD:main; then
    echo "[bump] success on attempt $attempt."
    exit 0
  fi

  echo "[bump] push rejected; retrying..."
  sleep "$((attempt * 2))"
done

echo "[bump] failed after $MAX_ATTEMPTS attempts." >&2
exit 1
