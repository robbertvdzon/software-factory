# Recent Merged

## Purpose

List recently merged factory PRs with enough usage/cost data to review the
finished work.

## Layout

- Page header: `Recent merged`.
- Subtitle: `Alle PR's die naar main zijn gemerged`.
- Dense table with issue/PR, status, merged age, run count, tokens and cost.
- Row action opens story detail in read-only/completed mode.

## Data

- Issue key and summary.
- PR number and URL.
- Merge timestamp.
- Final status.
- Run count.
- Token totals.
- Cost estimate.

## Actions

- Refresh.
- Open completed story detail.
- Open PR.

## States

- No merged stories.
- PR data unavailable.

## Notes

This screen is primarily audit/history. It should be sortable later by date,
cost and project.
