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

## Sorting & filtering (SF-818, sorting updated in SF-2137)

- The list is always sorted by creation time descending (`fields.createdAt`, most recently created
  story first), regardless of the active filters or search term: sorting happens before filtering,
  so filters only remove rows and never change the relative order.
- `createdAt` is parsed as a `DateTime` (not compared as text), so differing offsets or notations
  cannot produce a wrong order.
- Fallbacks: stories with an equal `createdAt` are ordered by story number descending (Dart's
  `List.sort` is not guaranteed stable); stories without a usable `createdAt` (missing, empty or
  unparsable — only reachable while a new frontend talks to an older backend) sort last, among
  themselves by story number descending.
- The sort key is deliberately not the timestamp shown on the row: a finished story still shows its
  completion time (`updatedAt`) while it is positioned by its creation time, so a finished story can
  sit above a newer-looking one. There is no sort selector; the order is fixed.
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
  and the three story-options axes (SF-1261, replacing the old single `auto-approve` toggle):
  `Vragen toestaan` (switch, default on), `Goedkeuring`
  (`automatisch`/`alleen-manual-poort`/`elke-stap`, default `automatisch`) and `Meldingen` with
  exactly three frontend-only presets: `Alleen als ik nodig ben`, `Als deployed` (default), and
  `Na elke stap`. The request contains the resulting concrete event array, never the preset name.
  `elke-stap` without `APPROVAL_REQUIRED` shows a non-blocking warning.
- `Hotfix` switch (SF-1959, default **off**, key `create-story-hotfix`): sends `hotfix: true` in the
  `POST /api/v1/stories` payload. A hotfix story skips refine/plan/review/test/documentation. The
  flag can only be set here (and on the other create routes); it is not editable afterwards on an
  existing story. A hotfix story shows exactly three subtasks — `hotfix`, `merge`, `deploy`. The
  `hotfix` subtask renders its own single "Hotfix" step in the phase stepper
  (`lib/phase_stepper.dart`, `case 'hotfix'`), green on `hotfix-approved`.

## Actions

- Refresh.
- Open story detail.
- Create new story (see dialog above).
- Filters: bucket, repo, title/story-key search.

## States

- Empty: `Geen stories in beheer van AI`.
- Stuck rows should be visually distinct and state why.
- Budget near limit should show warning text and progress.
- A story with its own `retryAfter`, or with a subtask that has `retryAfter`, shows an amber
  `quota-wacht` badge and the unambiguous local date/time “Gepauzeerd wegens Claude-quota tot …”,
  not a blocked/error badge.

## Notes

Avoid the generic label `AI IN PROGRESS` when a concrete phase is known. Prefer
`developing`, `reviewing`, `testing`, `stuck`, etc.
