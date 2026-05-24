# Dashboard

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
