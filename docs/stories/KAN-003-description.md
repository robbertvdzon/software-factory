# KAN-003 - Orchestrator State Machine

Story:
Als orchestrator wil ik Jira-stories betrouwbaar door de phase-machine sturen,
inclusief pause/error handling, recovery en concurrency-limieten.

Subtaken:
[ ]: Poller elke 15 seconden bouwen
[ ]: Phase-transities implementeren
[ ]: `Paused` en `Error` skip-logica implementeren
[ ]: `story_runs` lifecycle beheren
[ ]: Active agent detection via Docker labels
[ ]: Stuck detection met forward recovery
[ ]: Stuck detection met transient retry
[ ]: Hard timeout naar `Error`
[ ]: Concurrency caps per rol en totaal
[ ]: Developer-loopback cap

Stappen:
[ ]: model phases and agent roles
[ ]: implement scheduler boundary with testable services
[ ]: evaluate ticket eligibility before dispatch
[ ]: open or reuse active story run
[ ]: dispatch next role for completed phases
[ ]: detect active phases without running containers
[ ]: recover forward when the DB already has a successful run
[ ]: retry transient failures up to the configured cap
[ ]: write `Error` for hard timeouts and loopback cap exhaustion
[ ]: add state-machine unit tests

Done / rationale:
- Nog niet geimplementeerd.
