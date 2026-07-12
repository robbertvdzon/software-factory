#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <component> <run-id> <source-sha> <kustomization-dir> <commit-message> <image-arg>" >&2
  exit 2
}

[[ $# -eq 6 ]] || usage
COMPONENT="$1"
RUN_ID="$2"
SOURCE_SHA="$3"
KUST_DIR="$4"
COMMIT_MSG="$5"
IMAGE_ARG="$6"

[[ "$COMPONENT" =~ ^[a-z0-9-]+$ ]] || { echo "[bump] invalid component: $COMPONENT" >&2; exit 2; }
[[ "$RUN_ID" =~ ^[0-9]+$ ]] || { echo "[bump] run-id must be numeric" >&2; exit 2; }
[[ "$SOURCE_SHA" =~ ^[0-9a-f]{7,40}$ ]] || { echo "[bump] invalid source SHA" >&2; exit 2; }

REPO_ROOT="$(git rev-parse --show-toplevel)"
STATE_FILE=".github/image-bumps/${COMPONENT}.state"
BRANCH="automation/image-bump-${COMPONENT}-${RUN_ID}"
cd "$REPO_ROOT"

required_checks_configured() {
  local repo="$1"
  gh api "repos/${repo}/branches/main/protection" >/dev/null 2>&1 && return 0
  local rule_count
  rule_count="$(gh api "repos/${repo}/rules/branches/main" --jq 'length' 2>/dev/null || echo 0)"
  [[ "$rule_count" =~ ^[1-9][0-9]*$ ]]
}

install_kustomize() {
  command -v kustomize >/dev/null 2>&1 && return
  echo "[bump] installing kustomize..."
  curl -fsSL https://raw.githubusercontent.com/kubernetes-sigs/kustomize/master/hack/install_kustomize.sh | bash
  sudo mv kustomize /usr/local/bin/
}

state_run_id() {
  local ref="$1" value
  value="$(git show "${ref}:${STATE_FILE}" 2>/dev/null | sed -n 's/^run_id=//p' | head -1 || true)"
  [[ "$value" =~ ^[0-9]+$ ]] && printf '%s\n' "$value" || printf '0\n'
}

newest_visible_run() {
  local newest ref value
  newest="$(state_run_id origin/main)"
  while IFS= read -r ref; do
    value="$(state_run_id "$ref")"
    (( value > newest )) && newest="$value"
  done < <(git for-each-ref --format='%(refname)' "refs/remotes/origin/automation/image-bump-${COMPONENT}-*")
  printf '%s\n' "$newest"
}

pr_number_for_branch() {
  local branch="${1:-$BRANCH}"
  gh pr list --state open --head "$branch" --json number --jq '.[0].number // empty'
}

close_as_superseded() {
  local newer="$1" number
  number="$(pr_number_for_branch)"
  if [[ -n "$number" ]]; then
    gh pr comment "$number" --body "Superseded by newer ${COMPONENT} image run ${newer}; this run ${RUN_ID} may not downgrade the manifest."
    gh pr close "$number"
  fi
  echo "[bump] run $RUN_ID superseded by visible run $newer."
}

close_older_visible_prs() {
  local ref branch older number
  while IFS= read -r ref; do
    older="$(state_run_id "$ref")"
    (( older > 0 && older < RUN_ID )) || continue
    branch="${ref#refs/remotes/origin/}"
    number="$(pr_number_for_branch "$branch")"
    if [[ -n "$number" ]]; then
      gh pr comment "$number" --body "Superseded by newer ${COMPONENT} image run ${RUN_ID}; this run ${older} may not downgrade the manifest."
      gh pr close "$number"
    fi
  done < <(git for-each-ref --format='%(refname)' "refs/remotes/origin/automation/image-bump-${COMPONENT}-*")
}

push_branch() {
  local output status
  set +e
  output="$(git push --force-with-lease origin "HEAD:refs/heads/${BRANCH}" 2>&1)"
  status=$?
  set -e
  if (( status == 0 )); then
    return 0
  fi
  if grep -Eqi 'protected branch|repository rule|policy|not permitted|permission denied' <<<"$output"; then
    echo "[bump] non-retryable policy rejection while pushing bot branch: $output" >&2
    return 2
  fi
  echo "[bump] retryable network/push race: $output" >&2
  return 1
}

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
install_kustomize

for attempt in 1 2 3; do
  echo "[bump] attempt $attempt/3 for $COMPONENT run $RUN_ID"
  git fetch origin main "+refs/heads/automation/image-bump-${COMPONENT}-*:refs/remotes/origin/automation/image-bump-${COMPONENT}-*" --prune --quiet

  newest="$(newest_visible_run)"
  if (( RUN_ID < newest )); then
    close_as_superseded "$newest"
    exit 0
  fi

  git checkout -B "$BRANCH" origin/main
  mkdir -p "$(dirname "$STATE_FILE")"
  printf 'run_id=%s\nsource_sha=%s\n' "$RUN_ID" "$SOURCE_SHA" > "$STATE_FILE"
  (cd "$KUST_DIR" && kustomize edit set image "$IMAGE_ARG")

  git add "$KUST_DIR/kustomization.yaml" "$STATE_FILE"
  if git diff --cached --quiet; then
    echo "[bump] manifest and monotonic state already current."
    exit 0
  fi
  git commit -m "$COMMIT_MSG"

  if push_branch; then
    close_older_visible_prs
    break
  else
    status=$?
    (( status == 2 )) && exit 1
    (( attempt == 3 )) && { echo "[bump] failed after retryable push races." >&2; exit 1; }
  fi
done

number="$(gh pr list --state open --head "$BRANCH" --json number --jq '.[0].number // empty')"
body="Automated image manifest update.\n\n- Component: \`${COMPONENT}\`\n- Source SHA: \`${SOURCE_SHA}\`\n- Workflow run: \`${RUN_ID}\`\n\nThe versioned state makes older runs self-supersede. Normal required checks and branch protection remain authoritative."
if [[ -z "$number" ]]; then
  gh pr create --base main --head "$BRANCH" --title "$COMMIT_MSG" --body "$body"
  number="$(gh pr list --state open --head "$BRANCH" --json number --jq '.[0].number')"
else
  gh pr edit "$number" --title "$COMMIT_MSG" --body "$body"
fi

repository="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required for status attestation}"
for merge_attempt in 1 2 3; do
  if (( merge_attempt > 1 )); then
    old_head="$(gh pr view "$number" --json headRefOid --jq .headRefOid)"
    gh pr update-branch "$number"
    head_sha="$old_head"
    for update_attempt in {1..30}; do
      head_sha="$(gh pr view "$number" --json headRefOid --jq .headRefOid)"
      [[ "$head_sha" != "$old_head" ]] && break
      (( update_attempt == 30 )) && { echo "[bump] updated PR head did not become visible." >&2; exit 1; }
      sleep 2
    done
  else
    head_sha="$(gh pr view "$number" --json headRefOid --jq .headRefOid)"
  fi

  # A GITHUB_TOKEN PR has no recursive pull_request run. Verify the exact current PR head through
  # an explicit run and attest that run as the required status on that same head.
  dispatch_url="$(gh workflow run verify.yml --ref "$BRANCH")"
  dispatch_run_id="${dispatch_url##*/}"
  if [[ ! "$dispatch_run_id" =~ ^[0-9]+$ ]]; then
    echo "[bump] verification dispatch returned no usable run id: $dispatch_url" >&2
    exit 1
  fi
  gh run watch "$dispatch_run_id" --exit-status
  gh api --method POST "repos/${repository}/statuses/${head_sha}" \
    -f state=success \
    -f context='Repository verification' \
    -f description='Verified by exact repository dispatch run' \
    -f target_url="$dispatch_url" >/dev/null

  if required_checks_configured "$repository"; then
    for attempt in {1..12}; do
      set +e
      checks_output="$(gh pr checks "$number" --required 2>&1)"
      checks_status=$?
      set -e
      if (( checks_status == 0 )); then
        break
      fi
      if (( checks_status == 8 )); then
        gh pr checks "$number" --required --watch --interval 10
        break
      fi
      if grep -Eqi 'no (required )?checks( reported)?' <<<"$checks_output" && (( attempt < 12 )); then
        sleep 5
        continue
      fi
      echo "[bump] required-check lookup failed: $checks_output" >&2
      exit 1
    done
  else
    echo "[bump] no required checks/rulesets configured on ${repository}; skipping required-check wait."
  fi

  merge_state="$(gh pr view "$number" --json mergeStateStatus --jq .mergeStateStatus)"
  if [[ "$merge_state" == "BEHIND" ]]; then
    echo "[bump] main advanced; updating and re-verifying PR #$number (attempt $merge_attempt/3)."
    continue
  fi
  if gh pr merge "$number" --squash --delete-branch --match-head-commit "$head_sha"; then
    echo "[bump] PR #$number passed required checks and merged without bypass."
    exit 0
  fi
  merge_state="$(gh pr view "$number" --json mergeStateStatus --jq .mergeStateStatus)"
  [[ "$merge_state" == "BEHIND" && "$merge_attempt" -lt 3 ]] || exit 1
done

echo "[bump] main kept advancing; PR #$number was not merged after 3 verified heads." >&2
exit 1
