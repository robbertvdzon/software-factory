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

## Notes

Use supplier-neutral naming. Do not call this page `Claude`; Claude is only one
possible supplier.
