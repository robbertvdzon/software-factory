# Software Factory Web UX

This folder describes the intended web interface before implementation. It is
based on the supplied dashboard screenshots and the product behavior in
`specs/specs.md`.

## Goals

- Give the user one local operational dashboard for the factory.
- Make active AI work visible: phase, latest agent, budget, cost and blockers.
- Make completed work inspectable: PR, preview, briefing, screenshots and runs.
- Keep controls explicit and low-risk for destructive actions.
- Stay implementation-friendly for a server-rendered Spring Boot UI.

## Visual Direction

- Quiet operational UI, not a marketing site.
- Left sidebar navigation, dense content area, restrained typography.
- Light surfaces with subtle borders and pale purple accent states.
- Cards only for repeated rows, major grouped panels and modal-like blocks.
- Tables and rows should be easy to scan; avoid oversized hero sections.
- Status color is supportive, never the only signal.

## Implementation Preference

Prefer server-rendered HTML from the existing backend:

- Spring MVC controllers.
- Thymeleaf or Kotlin HTML DSL templates.
- Plain CSS with lucide-style icons or inline icon library support.
- Optional HTMX for partial refresh of lists, command results and status panels.

Avoid a full SPA unless later requirements need complex client-side state.

## File Map

- [screen-map.md](screen-map.md): navigation, routing and screen relationships.
- [dashboard-v2.md](dashboard-v2.md): repository-centric dashboard redesign
  for the Flutter/OpenShift dashboard.
- [screens/](screens/): per-screen UX specification.
- [wireframes/](wireframes/): static HTML wireframes with dummy data.
- [wireframes2/](wireframes2/): static HTML wireframes for the new
  repository-centric dashboard UX.

## Primary Screens

- [Login](screens/login.md)
- [Dashboard](screens/dashboard.md)
- [Stories](screens/stories.md)
- [Story Detail](screens/story-detail.md)
- [Briefing](screens/briefing.md)
- [Screenshots](screens/screenshots.md)
- [Agents](screens/agents.md)
- [Recent Merged](screens/merged.md)
- [Downloads](screens/downloads.md)
- [Settings](screens/settings.md)
