# Builds

## Purpose

Make GitHub Actions build status visible per managed repository (SF-876), and
surface failing default-branch builds on the Dashboard overview so they don't
require a separate visit.

## Layout

- Page header: `Builds`.
- Project-filter pills (one per managed repo/project, plus "Alle").
- One panel per repo, grouped: repo/project name, a column-title row
  (Workflow / Resultaat / Branch / Event / Duur, added SF-890), then a row
  per workflow with those columns and an "Open" link to the GitHub run.
- A repo without workflows shows an explanatory empty state instead of a
  table (and no column-title row).
- A condensed version of the same build info (last main-build timestamp,
  active-build badges, in-sync/out-of-sync badge) is embedded per project on
  the Projects screen (SF-890, see `ux/screens/projects.md`), so this screen
  remains the full per-repo/per-workflow detail view.

## Data

- Per workflow: name, status, conclusion, branch, event, duration, updated
  timestamp, link to the GitHub Actions run (`html_url`).
- Data is the *latest* run per workflow, not full run history.

## Actions

- Filter by project (pill).
- Open a run on GitHub.
- Refresh (pull-to-refresh / automatic on the `changed` SSE push).

## States

- No repositories configured: "No GitHub Actions workflows found for the
  configured repositories."
- A configured repo without any workflow runs: "No GitHub Actions workflows
  found. This repository can still be handled by the factory, but it has no
  visible buildstraat yet."

## Dashboard attention section

The Dashboard overview screen shows an "Aandacht nodig" section when any
managed repo's latest run on its default branch has `conclusion == failure`
(repo/project name, workflow name, link to the failing run). The section is
hidden entirely when there is nothing to flag.

## Notes

Data comes from `GET /api/v1/builds` (aggregated) and
`GET /api/v1/repositories/{owner}/{repo}/workflows` /
`GET /api/v1/repositories/{owner}/{repo}/runs` (single repo), both proxied
through the bridge to `FactoryDashboardService.builds()` /
`.buildsFor(owner, repo)`. See `docs/ontwerp-bridge-dashboard.md` §5
(`builds.list` / `builds.runs`). No auto-retry or Telegram notification on a
failed build — that is out of scope for this screen.
