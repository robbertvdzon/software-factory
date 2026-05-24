# Briefing

## Purpose

Show the results produced by all agents for a story, including summaries,
failures, findings and tester reports.

## Layout

- Back button and title `Briefing - <issueKey>`.
- Story summary.
- Chronological run cards.
- Each card header: role, run number if relevant, timestamp, outcome badge.
- Body renders agent comment text with basic markdown-like formatting.
- Long JSON blocks or tips updates are collapsed by default.

## Data

- Agent role.
- Agent run id/sequence.
- Outcome (`success`, `failed`, `feedback`, `bug`, `credits-exhausted`).
- Timestamp.
- Summary/comment body.
- Optional structured event payloads.

## Actions

- Back to story detail.
- Expand/collapse run body.
- Copy text from a run.
- Open related screenshot or PR if referenced.

## States

- No briefing yet.
- Failed run should show failure badge and short reason at the top.
- Very long content should remain readable and not make the page unusable.

## Notes

This view can be text-heavy, but it should not look like raw logs. Use cards,
badges and collapsible sections to preserve scanability.
