# Projects

## Purpose

Per-project operational overview: story counters, cost, active agents, the
current production version, and (since SF-890) build/deploy status — so a
user does not have to switch to the Builds screen to see whether main is
green, whether a build is running, or whether production is up to date.

## Layout

- Page header: `Projects`.
- One panel per configured project (`projects.yaml`), each with:
  - Name and repo URL, plus a "Force deploy" button (only for projects with
    a `RestRestart` deploy-configuratie).
  - Chips: `todo` / `bezig` / `klaar` story counters, active-agent count,
    cost (`$`).
  - `Live: <branch> · <commitShort> (<commitDate>)` when a production
    version is known (`prdVersion`, only for projects with a deploy-config).
  - Build-status row (SF-890): last main-build timestamp, an active-build
    badge (`Main-build actief` / `PR-build actief`, or `Geen actieve build`
    when neither is running), and a sync badge comparing `prdVersion` to the
    latest main-build (see States below).

## Data

Served by `GET /api/v1/projects` (bridge op `projects.list`, proxied to
`DashboardQueryService.projectsOverview()`), per project
(`ProjectOverviewItem`):

- `name`, `repoUrl`, story counters, `totalCostUsd`, `activeAgentCount`.
- `prdVersion` (nullable `PrdVersionInfo`: `commitShort`/`commitDate`/`branch`)
  and `hasDeployConfig`.
- `buildStatus` (`ProjectBuildStatus`, SF-890):
  - `lastMainBuildAt`: timestamp of the latest completed workflow run with
    `event == push` on the repo's default branch (across all workflows).
  - `mainBuildActive` / `prBuildActive`: is there a run with status
    `queued`/`in_progress` for, respectively, the default branch
    (`event == push`) or an open PR (`event == pull_request`).
  - `syncStatus`: `IN_SYNC` / `OUT_OF_SYNC` / `UNAVAILABLE` — prefix-tolerant
    comparison of `prdVersion.commitShort` against the last completed
    main-build's commit sha
    (`DashboardQueryService.buildStatusFor`/`shaPrefixMatch`, same recipe
    as the deploy-verification sha-matching in `DeploySubtaskHandler`).

## Actions

- Force deploy (projects with a deploy-config).
- Refresh (pull-to-refresh / automatic on the `changed` SSE push).

## States

- No projects configured: "Geen projecten geconfigureerd."
- `syncStatus == IN_SYNC`: badge "In sync met main" (good tone).
- `syncStatus == OUT_OF_SYNC`: badge "Loopt achter op main" (warn tone).
- `syncStatus == UNAVAILABLE`: badge "Geen productieversie beschikbaar"
  (neutral tone) — shown both for projects without a deploy-config and for
  projects where the comparison cannot (yet) be made (no `prdVersion` or no
  known main-build sha).
- No active build for main or a PR: "Geen actieve build" (not treated as an
  error state).

## Notes

Build/version data for `buildStatus` reuses the existing GitHub Actions
client (`GitHubActionsClient.latestRunsPerWorkflow`/`defaultBranch`) already
used by the Builds screen (`ux/screens/builds.md`); no new REST endpoints or
bridge operations were added for this. The full per-workflow run history
detail remains on the separate Builds screen.
