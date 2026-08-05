# Screen Map

## Navigation

The app uses a persistent left sidebar on authenticated screens.

Primary nav:

- `Repositories`
- `Stories`
- `Agents`
- `Builds`
- `Downloads`
- `Settings`

For the Flutter/OpenShift dashboard redesign, prefer the repository-centric
navigation in [dashboard-v2.md](dashboard-v2.md).

The `Recent Merged` screen and nav item were removed (SF-1288); merged-story
history is not currently exposed as a dedicated screen.

The `Dashboard` overview screen and nav item were removed (SF-1676): the screen
was never used and has no replacement or redirect. In `dashboard-frontend` the
primary bottom nav is now `Stories`, `My actions`, `Agents` plus `Meer`
(`Projects`, `Builds`, `App-updates`, `Audits`, `Opruimen`, `Settings`). The backend chain
behind it (`/api/v1/dashboard`, bridge-operatie `dashboard.get`) stays in place
for already deployed APK versions; see [screens/dashboard.md](screens/dashboard.md).

Use `Agents`, not `Claude`, because `AI-supplier` can be `mock`, `claude`,
`openai`, `copilot` or `microsoft`.

## Routes

| Route | Screen | Purpose |
|---|---|---|
| `/login` | Login | Authenticate into the dashboard. |
| `/stories` | Stories | All tracker issues currently owned by AI. |
| `/stories/{issueKey}` | Story Detail | Full status, commands, deploy, budget and run data. |
| `/stories/{issueKey}/briefing` | Briefing | Agent comments/results in chronological order. |
| `/stories/{issueKey}/screenshots` | Screenshots | Tester screenshot gallery. |
| `/agents` | Agents | Active factory agents and interactive sessions. |
| `/builds` | Builds | GitHub Actions workflow status per managed repository (SF-876). |
| `/projects` | Projects | Per-project story counters, cost, production version and build/deploy status (SF-890). |
| `/downloads` | Downloads | APK/artifact downloads. |
| `/audits` | Audits | Per-project audit reports, score trend and a "run now" button (`AuditScreen`, replaces the former `/nightly` screen). "Open memory" pushes a full-page `AuditMemoryScreen` (SF-1676) instead of a dialog: own `AppBar` with title `Memory — <auditType>`, the standard back button, `+` as `AppBar` action, content left-aligned in a `ConstrainedBox(maxWidth: 860)`. It is a `Navigator.push(MaterialPageRoute(...))` without its own route name or deeplink; editing and deleting a tip stay small `AlertDialog`s. |
| `/maintenance` | Opruimen | Per-action overview of the factory's cleanup mechanisms (`MaintenanceScreen`, SF-1913, generalised in SF-1921, restructured per action in SF-1939). One `Panel` per kind from the fixed `cleanupKinds` list (`github-releases`, `agent-events`, `agent-runs`, `completion-payloads`, `workspaces`) — all five always visible, also without a logged round. Each block shows the action name, the result of the last round as label/value pairs (`verwijderd:` / `blijft staan:` / `duur:`, formatted by `formatCleanupDuration` as `1 m 7 s`, `43 s`, `< 1 s` or `-` when `finishedAt` is missing), when that round ran, and the `dry-run`, `handmatig` and `fout` badges where applicable; `github-releases` gets one line per project with a logged round (display only). Without a logged round the block reads `laatste ronde: geen wijzigingen gelogd`. The data comes from the `summary` field of `maintenance.cleanupsList` (last row per kind/project, from its own repository query rather than the 200-row list). Each block has a `Nu draaien` button (`Key('run-now-<kind>')`, `POST /api/v1/maintenance/run`) and a `Runs bekijken` button (`Key('view-runs-<kind>')`) that pushes `CleanupRunsScreen`: own `Scaffold`/`AppBar` titled `Rondes: <kind>`, loading `/api/v1/maintenance/cleanups?kind=<kind>` and listing the rounds newest-first with timestamp, project, "N opgeruimd / M bewaard" and the same badges. Tapping a round pushes the unchanged full-page detail screen (own `Scaffold`/`AppBar` titled `Opruimronde`, content in a `ConstrainedBox(maxWidth: 860)`) with the kept/deleted counts, the error message and — for `github-releases` only — the release/package breakdown and the deleted release tags and package versions. Above the blocks: error banners from `errors` and a single `Alles draaien` button (`Key('run-now-all')`, `kind=all`) with the combined message; the `Soort:` dropdown and the `Nu draaien:` button bar were dropped in SF-1939. Feedback goes through `showActionResult` per status (`started`/`already_running`/`disabled`/`unknown_kind`); a `Nu draaien` button is disabled while a request is in flight and while `runningKinds` reports that kind as running. After a start the screen reloads through a `GlobalKey<DataScreenState>` and keeps polling every 3s until nothing runs any more. Titles and buttons stack below 560px so the screen stays within a phone's width. No paging of the history; `Navigator.push(MaterialPageRoute(...))` without its own route name or deeplink. |
| `/settings` | Settings | User/session settings and the per-project audit-scheduler settings. |

## Common Layout

Authenticated screens share:

- Sidebar with product mark and active nav item.
- Page header with title, subtitle and optional refresh/action controls.
- Content width uses the available viewport; rows remain dense on desktop.
- Mobile collapses sidebar into a top bar and keeps tables horizontally scrollable
  only where necessary.

## Core Flow

```mermaid
flowchart LR
    Login["Login"] --> Stories["Stories"]
    Stories --> Detail["Story Detail"]
    Detail --> Briefing["Briefing"]
    Detail --> Screenshots["Screenshots"]
    Detail --> Preview["Open Preview"]
    Detail --> PullRequest["Open PR"]
    Stories --> Agents["Agents"]
    Stories --> Downloads["Downloads"]
    Stories --> Audits["Audits"]
    Audits --> Memory["Memory — auditType"]
```

## Status Language

User-facing status should be specific:

- `queued`
- `refining`
- `developing`
- `reviewing`
- `testing`
- `summarizing`
- `waiting for user`
- `paused`
- `stuck`
- `tested ok`
- `summary finished`
- `merged`

Avoid using only generic `AI in progress` when a more precise phase is known.

## Shared States

Every data-driven screen needs:

- Loading state: skeleton rows or subdued `Laden...` text.
- Empty state: compact explanation and next useful action.
- Error state: readable message, retry button, and no stack traces.
- Refresh state: manual refresh button plus timestamp where useful.
