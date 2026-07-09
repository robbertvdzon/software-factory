# Dashboard UX V2 - Repository-Centric Factory Dashboard

## Goal

The dashboard is the remote control room for the Software Factory. It should
answer three questions quickly:

- Which repositories are managed by the factory?
- What is the current build/release state of each repository?
- What AI work is active, blocked, merged or ready to download?

This design should replace the temporary Flutter dashboard layout. The visual
style should follow the older Flutter dashboard in
`personal-news-feed-by-claude-code/frontend-dashboard`: Material 3, left
sidebar on desktop, drawer on mobile, quiet cards, dense tables and pale
indigo selection states.

## Example Managed Repositories

The dashboard should use these repositories as example data and as the first
real integration target:

| Project | Name | Repository | Current CI / release state |
|---|---|---|---|
| `PNF` | Personal News Feed | `robbertvdzon/personal-news-feed-by-claude-code` | Has many workflows and Android APK releases. |
| `SP` | Sample project | `robbertvdzon/sample-build-project` | No GitHub Actions workflows or releases found. |
| `SF` | Software Factory | `robbertvdzon/software-factory` | Has dashboard backend/frontend image workflows. |

Repository discovery should come from `projects.yaml`: each configured project
has a name and a git repository, for example:

```yaml
projects:
  - name: personal-news-feed-by-claude-code
    repo: git@github.com:robbertvdzon/personal-news-feed-by-claude-code.git
```

GitHub builds, releases and APK downloads are shown only for GitHub
repositories. Non-GitHub repositories still appear in the dashboard and can be
handled by the factory when git credentials are available to the worker.

## Information Architecture

Primary navigation:

- `Dashboard`
- `Repositories`
- `Stories`
- `Agents`
- `Builds`
- `Downloads`
- `Settings`

The old `Recent merged` screen should become a filtered view inside
`Repositories` and `Stories`, not a primary navigation item. The important
question is usually "what changed in this repo?", not "show every merged PR in
one global list".

## Screen 1 - Dashboard

Purpose: show whether the factory and managed repos need attention.

HTML wireframe: [dashboard.html](wireframes2/dashboard.html)

Desktop wireframe:

```text
+----------------------+------------------------------------------------------+
| SF Software Factory  | Dashboard                                      refresh |
|                      | Remote overview of managed repositories                |
| Dashboard          * |                                                      |
| Repositories         | +-----------+ +-----------+ +-----------+ +-----------+ |
| Stories              | | Repos  3  | | Active 1 | | Agents 0  | | APKs  1  | |
| Agents               | +-----------+ +-----------+ +-----------+ +-----------+ |
| Builds               |                                                      |
| Downloads            | Repositories                                         |
| Settings             | +--------------------------------------------------+ |
|                      | | PNF  Personal News Feed     main  last build OK  | |
|                      | |      APK available          10 workflows         | |
|                      | +--------------------------------------------------+ |
|                      | | SF   Software Factory       master last build OK | |
|                      | |      no APK                 2 workflows          | |
|                      | +--------------------------------------------------+ |
|                      | | SP   Sample project         main  no workflows   | |
|                      | |      AI story active        setup needed         | |
|                      | +--------------------------------------------------+ |
|                      |                                                      |
|                      | Attention                                            |
|                      | +--------------------------------------------------+ |
|                      | | SP-3 blocked: Geen actieve container gevonden...  | |
|                      | +--------------------------------------------------+ |
+----------------------+------------------------------------------------------+
```

Dashboard content:

- Repository cards sorted by attention: blocked first, active second, healthy
  last.
- A small "Attention" list with blocked stories, failed builds, expired APKs or
  missing workflows.
- Latest APK card if available.
- No deep build logs here; link to repository detail or builds.

## Screen 2 - Repositories

Purpose: canonical overview of all factory-managed repositories.

HTML wireframe: [repositories.html](wireframes2/repositories.html)

Desktop wireframe:

```text
+----------------------+------------------------------------------------------+
| Sidebar              | Repositories                                  refresh |
|                      | 3 repositories from projects.yaml                     |
|                      |                                                      |
|                      | Filters: [All] [Active AI] [Has APK] [No CI] [Failed] |
|                      |                                                      |
|                      | Repository       Project   Branch   CI       APK      |
|                      | ----------------------------------------------------- |
|                      | Personal News...  PNF       main     OK       yes      |
|                      | Software Factory SF        master   OK       no       |
|                      | Sample project   SP        main     missing  no       |
|                      |                                                      |
+----------------------+------------------------------------------------------+
```

Repository row fields:

- Project key and project name.
- GitHub repository owner/name.
- Default branch.
- Latest workflow conclusion.
- Number of workflows.
- Latest APK/release status.
- Active story count.
- Last merged PR.

Row actions:

- Open repository detail.
- Open GitHub.

## Screen 3 - Repository Detail

Purpose: one place for everything related to a repository.

Example: `PNF - Personal News Feed`

HTML wireframe: [repository-detail.html](wireframes2/repository-detail.html)

```text
+----------------------+------------------------------------------------------+
| Sidebar              | PNF - Personal News Feed                       GitHub |
|                      | robbertvdzon/personal-news-feed-by-claude-code       |
|                      |                                                      |
|                      | [Overview] [Buildstraat] [Stories] [Releases/APKs]   |
|                      |                                                      |
|                      | Overview                                             |
|                      | +-----------+ +-----------+ +-----------+ +---------+ |
|                      | | Branch    | | Workflows | | Latest CI | | APK     | |
|                      | | main      | | 10        | | OK        | | yes     | |
|                      | +-----------+ +-----------+ +-----------+ +---------+ |
|                      |                                                      |
|                      | Latest activity                                      |
|                      | - KAN-68 Build container images succeeded            |
|                      | - KAN-67 Build Android APK succeeded                 |
|                      | - Latest APK: personal-news-feed.apk, 55 MB          |
+----------------------+------------------------------------------------------+
```

Tabs:

- `Overview`: repo summary, active factory work, latest release/build.
- `Buildstraat`: workflows and recent runs.
- `Stories`: tracker stories for this repo.
- `Releases/APKs`: release assets and downloadable APKs.

## Screen 4 - Buildstraat

Purpose: make CI/CD understandable per repository.

HTML wireframe: [builds.html](wireframes2/builds.html)

For `PNF`, show:

```text
Workflow                         Last result   Branch   Event   Duration
---------------------------------------------------------------------------
Build Android APK                success       main     push    5m28s
Build container images           success       main     push    2m16s
Build cost-monitor image         success       main     push    ...
Build dashboard-frontend image   success       main     push    ...
Build jira-poller image          success       main     push    ...
Build preview-ns-labeller image  success       main     push    ...
Build claude-runner image        success       main     push    ...
Build status-dashboard image     success       main     push    ...
Build claude-tester image        success       main     push    ...
Validate PR                      success       ai/KAN-68 pull_request 10s
```

For `SF`, show:

```text
Workflow                         Last result   Branch   Event   Duration
---------------------------------------------------------------------------
Build dashboard-backend image    success       master   push    35s
Build dashboard-frontend image   success       master   push    2m04s
```

For `SP`, show an empty state:

```text
No GitHub Actions workflows found.
This repository can still be handled by the factory, but it has no visible
buildstraat yet.
```

**SF-876 status:** implemented as a standalone `Builds` nav screen (grouped by repo, with
project-filter pills) instead of a `Buildstraat` tab on the repository-detail screen — that
detail screen with tabs does not exist yet (see `docs/factory/ux/screens/builds.md`). The
repository-detail tabs remain a future story. Failing default-branch runs also surface in an
"Aandacht nodig" section on the Dashboard overview screen.

Run detail should show:

- Workflow name.
- Run title.
- Branch.
- Event.
- Status and conclusion.
- Started/completed time.
- Link to GitHub run.
- Jobs and step summaries when available.

## Screen 5 - Downloads

Purpose: show user-downloadable artifacts, especially APK files.

HTML wireframe: [downloads.html](wireframes2/downloads.html)

Important: APKs should come primarily from GitHub Releases, not GitHub Actions
artifacts. The `PNF` APK workflow publishes release assets:

```text
latest release: apk-20260523-053714-b75e205
assets:
- personal-news-feed-20260523-053714-b75e205.apk
- personal-news-feed.apk
size: about 55 MB
```

Downloads wireframe:

```text
+----------------------+------------------------------------------------------+
| Sidebar              | Downloads                                    refresh |
|                      | APKs and user-facing release assets                  |
|                      |                                                      |
|                      | +--------------------------------------------------+ |
|                      | | Android APK                                      | |
|                      | | Personal News Feed                              | |
|                      | | personal-news-feed.apk · 55 MB · 2026-05-23     | |
|                      | | [Download APK] [Open release]                   | |
|                      | +--------------------------------------------------+ |
|                      |                                                      |
|                      | +--------------------------------------------------+ |
|                      | | Software Factory                                | |
|                      | | No APK release found                            | |
|                      | +--------------------------------------------------+ |
|                      |                                                      |
|                      | +--------------------------------------------------+ |
|                      | | Sample project                                  | |
|                      | | No releases found                               | |
|                      | +--------------------------------------------------+ |
+----------------------+------------------------------------------------------+
```

Rules:

- Show release assets ending in `.apk`.
- Prefer an asset with a stable name like `personal-news-feed.apk`.
- Also show timestamped assets for traceability.
- Show Actions artifacts only in an "Advanced artifacts" section because many
  are internal `.dockerbuild` files.

## Screen 6 - Stories

Purpose: keep the current factory workflow visible, but with repository context.

Table columns:

- Story key.
- Summary.
- Repository.
- Phase.
- Status.
- Active agent.
- PR.
- Cost/tokens.
- Blocker.

Filters:

- Repository.
- Phase.
- Blocked.
- Active.
- Merged.

For `SP-3`, the row should clearly show:

```text
SP-3  Create first app  sample-build-project  reviewed-with-feedback...  blocked
```

The current error should be shown as a blocker, not as a raw red wall:

```text
Blocked
No active container found for reviewing. Manual triage needed.
```

## Screen 7 - Story Detail

Purpose: inspect and control one factory story.

Keep the current story detail concepts, but make it visually closer to the old
Flutter dashboard:

- Header with story key, summary, repository and phase chips.
- Primary actions in one toolbar: pause, resume, merge, delete,
  re-implement.
- Blocker panel if present.
- PR/repository links.
- Agent run timeline.
- Budget/cost panel.
- Screenshots and briefing as tabs or secondary links.

## Mobile Behavior

Use the same behavior as the older Flutter dashboard:

- Desktop/tablet: persistent left sidebar.
- Phone: top app bar with drawer.
- Repository lists become stacked rows, not horizontal tables.
- Story details keep command buttons in a wrapped toolbar.
- Downloads remain card-based and easy to tap.

## Backend API Shape Needed

The dashboard backend should expose repository-centric endpoints:

```text
GET /api/v1/repositories
GET /api/v1/repositories/{owner}/{repo}
GET /api/v1/repositories/{owner}/{repo}/workflows
GET /api/v1/repositories/{owner}/{repo}/runs
GET /api/v1/repositories/{owner}/{repo}/releases
GET /api/v1/downloads
```

Data sources:

- `projects.yaml`: managed repo discovery and project metadata.
- GitHub repositories: default branch and repo metadata.
- GitHub Actions workflows/runs: buildstraat.
- GitHub Releases: APK downloads.
- GitHub Actions artifacts: advanced/internal artifacts only.
- Software Factory database: story runs, agent runs, cost/tokens and blockers.

## Implementation Direction

Reuse from the old Flutter dashboard:

- `AppShell` navigation pattern.
- `AppTheme`.
- Riverpod providers.
- Typed `models.dart` with tolerant `fromJson` parsing.
- Downloads card pattern.
- Releases/build table pattern.

Do not copy the old PNF-specific backend model directly. The new model should
be repository-centric and should work for PNF, SP and SF from the start.
