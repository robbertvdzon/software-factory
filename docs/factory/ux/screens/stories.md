# Stories

## Purpose

Show all YouTrack issues currently owned by the factory: issues in `Develop`
with `AI-supplier` filled and not `none`.

## Layout

- Page header: `Stories`.
- Subtitle: `Stories die de AI op dit moment behandelt`.
- Table/list with one row per issue.
- Columns: story, status, supplier, phase, runs, tokens, AI level, budget, cost,
  detail action.

## Data

- Issue key and summary.
- YouTrack project key.
- `AI-supplier`.
- `AI Phase`.
- derived status (`queued`, `running`, `stuck`, `paused`, `waiting`).
- Agent run count.
- Token totals and budget.
- Estimated cost.

## Actions

- Refresh.
- Open story detail.
- Optional filters: supplier, status, project.

## States

- Empty: `Geen stories in beheer van AI`.
- Stuck rows should be visually distinct and state why.
- Budget near limit should show warning text and progress.

## Notes

Avoid the generic label `AI IN PROGRESS` when a concrete phase is known. Prefer
`developing`, `reviewing`, `testing`, `stuck`, etc.
