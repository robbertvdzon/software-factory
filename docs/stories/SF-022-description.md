# SF-022 - Web Interface Implementation

## Story

Implement the first Software Factory web interface from the UX specification in
`docs/factory/ux/`, served by the existing Spring Boot backend.

## Plan

[x]: create this story document
[x]: add server-rendered web routes
[x]: read dashboard data from YouTrack and Postgres
[x]: add session login and logout
[x]: add shared HTML renderer and CSS
[x]: add command endpoints for story actions
[x]: add focused tests
[x]: run tests and verify locally in browser

## Work Log

- Created this story document before code changes so the implementation work is
  traceable.
- Added Spring MVC routes for login, dashboard, stories, story detail,
  briefing, screenshots, agents, recent merged, downloads and settings.
- Added a dashboard repository that reads story-runs, agent-runs and
  agent-events from the configured factory Postgres schema.
- Added local session login/logout with optional `SF_DASHBOARD_USERNAME` and
  `SF_DASHBOARD_PASSWORD`.
- Added server-rendered HTML views and shared CSS based on the UX wireframes.
- Added story command POST endpoints that queue plain YouTrack command comments
  for the existing orchestrator flow.
- Added focused tests for HTML rendering/escaping and local session
  authentication.
- Ran `mvn test`; all 86 tests passed.
- Started the app on `http://localhost:8080`, logged in with the default local
  dashboard credentials and verified the dashboard and story detail pages in the
  browser.
