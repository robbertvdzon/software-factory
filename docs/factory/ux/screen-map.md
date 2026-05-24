# Screen Map

## Navigation

The app uses a persistent left sidebar on authenticated screens.

Primary nav:

- `Dashboard`
- `Stories`
- `Agents`
- `Recent merged`
- `Downloads`
- `Settings`

Use `Agents`, not `Claude`, because `AI-supplier` can be `mock`, `claude`,
`openai` or `microsoft`.

## Routes

| Route | Screen | Purpose |
|---|---|---|
| `/login` | Login | Authenticate into the dashboard. |
| `/` or `/dashboard` | Dashboard | Operational overview and recent activity. |
| `/stories` | Stories | All YouTrack issues currently owned by AI. |
| `/stories/{issueKey}` | Story Detail | Full status, commands, deploy, budget and run data. |
| `/stories/{issueKey}/briefing` | Briefing | Agent comments/results in chronological order. |
| `/stories/{issueKey}/screenshots` | Screenshots | Tester screenshot gallery. |
| `/agents` | Agents | Active factory agents and interactive sessions. |
| `/merged` | Recent Merged | Recently merged PRs and usage totals. |
| `/downloads` | Downloads | APK/artifact downloads. |
| `/settings` | Settings | User/session settings and logout. |

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
    Login["Login"] --> Dashboard["Dashboard"]
    Dashboard --> Stories["Stories"]
    Stories --> Detail["Story Detail"]
    Detail --> Briefing["Briefing"]
    Detail --> Screenshots["Screenshots"]
    Detail --> Preview["Open Preview"]
    Detail --> YouTrack["Open YouTrack"]
    Detail --> PullRequest["Open PR"]
    Dashboard --> Agents["Agents"]
    Dashboard --> Merged["Recent Merged"]
    Dashboard --> Downloads["Downloads"]
```

## Status Language

User-facing status should be specific:

- `queued`
- `refining`
- `developing`
- `reviewing`
- `testing`
- `waiting for user`
- `paused`
- `stuck`
- `tested ok`
- `merged`

Avoid using only generic `AI in progress` when a more precise phase is known.

## Shared States

Every data-driven screen needs:

- Loading state: skeleton rows or subdued `Laden...` text.
- Empty state: compact explanation and next useful action.
- Error state: readable message, retry button, and no stack traces.
- Refresh state: manual refresh button plus timestamp where useful.
