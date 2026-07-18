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
  The log list shows a visible scrollbar (SF-1084, a `Scrollbar` widget around
  the `ListView`) once the content is longer than the visible area. Auto-scroll
  behaviour: the view always jumps to the bottom on the initial load; for an
  active run (polling every 3s), a new poll only auto-scrolls to the bottom
  when the user was already (approximately, within a small pixel tolerance)
  at the bottom — if the user has scrolled up to read earlier lines, the
  scroll position is preserved and new lines no longer force a jump back down.
  Above the log list, a row of filter chips (SF-1105) lets the user toggle
  each event type independently: `Tekst` (assistant text), `Tool-aanroepen`
  (tool calls), `Tool-resultaten` (tool results), `Systeem` (system/thinking)
  and `Overig` (unparsable raw lines). Only `Tekst` is enabled by default; the
  other four start off. Toggling a chip filters the already-loaded events
  in-memory (no new fetch) and resets any expanded tool cards, since item
  indices can shift when the visible list changes. If the active filter
  combination hides every event, an explicit empty state is shown instead of
  a blank area. The filter selection is not persisted between screen visits;
  it always resets to the default (`Tekst` only) on next open.
- Stop interactive session.
- Start new interactive session if enabled.

## States

- No active factory agents.
- Agent stuck/running longer than hard timeout.
- Session cap reached.

## Notes

Use supplier-neutral naming. Do not call this page `Claude`; Claude is only one
possible supplier.
