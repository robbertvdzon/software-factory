# Dashboard

## Status: removed (SF-1676)

This screen no longer exists in the app. In `dashboard-frontend` both the
`Dashboard` nav item and `lib/screens/dashboard_overview_screen.dart` were
removed in SF-1676; there is no replacement screen and no redirect. The primary
bottom nav is now `Stories`, `My actions`, `Agents` plus `Meer`.

The backend chain (`/api/v1/dashboard`, bridge-operatie `dashboard.get`,
`DashboardApi.dashboard()`, `DashboardQueryService.dashboard()`) is deliberately
kept: already deployed APK versions still call it. Cleaning that up is a separate
later story.

The rest of this document and `../wireframes/dashboard.html` are kept as design
history for the original web UX; do not read them as a description of the
current app.

## Purpose

Give a fast operational overview of production state, downloadable artifacts and
recent build activity.

## Layout

- Page header: `Dashboard`, subtitle `Overzicht van builds en productie`.
- Small build/version line for the dashboard itself.
- Production panel with current main branch build and latest merge.
- Builds/services table.
- APK/artifact cards.
- Recent builds list.

## Data

- Current production branch and commit.
- Latest merge to main.
- Build/service statuses.
- APK artifacts with build time, size and download URL.
- Recent CI/build runs.

## Actions

- Refresh.
- Open production preview.
- Open related build or PR details.
- Download APK/artifact.

## States

- Service pending/running/pass/fail.
- No artifacts available.
- Build provider unavailable.

## Notes

This page is not the primary AI-control surface. It should stay compact and
mostly read-only.
