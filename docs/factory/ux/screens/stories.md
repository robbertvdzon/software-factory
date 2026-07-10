# Stories

## Purpose

Show all tracker issues currently owned by the factory: issues in `Develop`
with `AI-supplier` filled and not `none`.

## Layout

- Page header: `Stories`.
- Subtitle: `Stories die de AI op dit moment behandelt`.
- Table/list with one row per issue.
- Columns: story, status, supplier, phase, runs, tokens, AI level, budget, cost,
  detail action.

## Data

- Issue key and summary.
- Repo (`Repo`-veld, met terugval op de run-`targetRepo`).
- `AI-supplier`.
- `AI Phase`.
- derived status (`queued`, `running`, `stuck`, `paused`, `waiting`).
- Per-row timestamp: for a finished story the completion time (`updatedAt`), otherwise the
  creation time (`createdAt`). The `/api/v1/stories`-response exposes `createdAt`/`updatedAt`
  on `fields`.
- Agent run count.
- Token totals and budget.
- Estimated cost.

## Sorting & filtering (SF-818)

- The list is always sorted by story number descending (highest/newest first), regardless of the
  active filters or search term.
- Filter bar: the todo/bezig/klaar bucket chips, a **repo filter** (distinct repos of the shown
  stories plus "alle repos"), and a case-insensitive **search field** matching a substring of the
  story title or the story key (e.g. `910` or `sf-910` matches `SF-910`). The three combine with
  AND and are persisted via SharedPreferences
  (`stories_filter_buckets`, `stories_filter_repo`, `stories_filter_search`; the old
  `stories_filter_project` is gone).

## New story dialog (SF-818)

- No Project field: the factory is single-project, so the dialog no longer sends a `projectKey`.
  The backend falls back to the single configured project for key generation (`SF-###`).
- Remaining fields: title (required), description, repo, AI-supplier/-model, direct starten,
  auto-approve.

## Actions

- Refresh.
- Open story detail.
- Create new story (see dialog above).
- Filters: bucket, repo, title/story-key search.

## States

- Empty: `Geen stories in beheer van AI`.
- Stuck rows should be visually distinct and state why.
- Budget near limit should show warning text and progress.

## Notes

Avoid the generic label `AI IN PROGRESS` when a concrete phase is known. Prefer
`developing`, `reviewing`, `testing`, `stuck`, etc.
