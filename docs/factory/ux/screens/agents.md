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
- Started time and age.
- Container name.
- Interactive sessions if supported.
- Max parallel caps and active counts.

## Actions

- Refresh.
- Open story detail for a factory agent.
- Stop interactive session.
- Start new interactive session if enabled.

## States

- No active factory agents.
- Agent stuck/running longer than hard timeout.
- Session cap reached.

## Implemented (Flutter dashboard-frontend, SF-1010)

- Each agent tile (active and recent) shows the started time and elapsed/total duration
  (`formatTimestamp`/`formatDuration` in `api_client.dart`): live-updating for active runs, fixed
  `durationMs` for finished runs.
- Tapping a tile opens a detail screen (`AgentLogScreen`) with the captured
  `docker-stdout`/`docker-stderr` log for that agent run (`GET /api/v1/agents/{agentRunId}/log`,
  bridge operation `agent.log`), polling every few seconds while the run has no outcome yet and
  stopping once it does. An empty/error state is shown when no log events exist yet.
- "Interactive sessions" (spec-only above) is not implemented; this page only covers factory
  agent runs.

## Notes

Use supplier-neutral naming. Do not call this page `Claude`; Claude is only one
possible supplier.
