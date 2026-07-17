# Agents

## Purpose

Show active factory agents and optional interactive AI sessions.

## Layout

- Page header: `Agents`.
- Section `Factory agents`.
- Section `Interactive sessions`.
- Row cards with icon, name/story, role/status, age and action.

## Data

- Running containers by story and role.
- Started time and age (SF-1038: shown on every tile, active and recent — active runs count up live/on each poll, finished runs show the fixed total duration).
- Container name.
- Interactive sessions if supported.
- Max parallel caps and active counts.

## Actions

- Refresh.
- Open story detail for a factory agent.
- Tap an agent tile to open its log detail view (SF-1038): shows the captured
  `docker-stdout`/`docker-stderr` output for that run, auto-updating while the
  run is still active and loaded once (no further updates) for a finished run.
  An explicit empty state is shown when no log events are available yet.
  Each line is parsed client-side as JSON (SF-1061; both the Claude
  `stream-json` and Codex `--json` event schemas are recognized): assistant
  text renders fully readable, tool calls/results render as a single collapsed
  summary line (tool name + short preview) that can be expanded to the full
  payload, and lines that fail to parse fall back to the raw text unchanged.
  The screen uses the regular app theme (`AppTheme`/`SfColors`), not a
  dedicated dark/green-on-black terminal look.
- Stop interactive session.
- Start new interactive session if enabled.

## States

- No active factory agents.
- Agent stuck/running longer than hard timeout.
- Session cap reached.

## Notes

Use supplier-neutral naming. Do not call this page `Claude`; Claude is only one
possible supplier.
