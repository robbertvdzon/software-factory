# KAN-003 - Orchestrator State Machine

Story:
Als orchestrator wil ik Jira-stories betrouwbaar door de phase-machine sturen,
inclusief pause/error handling, recovery en concurrency-limieten.

Subtaken:
[x]: Poller elke 15 seconden bouwen
[x]: Phase-transities implementeren
[x]: `Paused` en `Error` skip-logica implementeren
[x]: `story_runs` lifecycle beheren
[x]: Active agent detection via Docker labels
[x]: Stuck detection met forward recovery
[x]: Stuck detection met transient retry
[x]: Hard timeout naar `Error`
[x]: Concurrency caps per rol en totaal
[x]: Developer-loopback cap

Stappen:
[x]: model phases and agent roles
[x]: implement scheduler boundary with testable services
[x]: evaluate ticket eligibility before dispatch
[x]: open or reuse active story run
[x]: dispatch next role for completed phases
[x]: detect active phases without running containers
[x]: recover forward when the DB already has a successful run
[x]: retry transient failures up to the configured cap
[x]: write `Error` for hard timeouts and loopback cap exhaustion
[x]: add state-machine unit tests

Done / rationale:
- De phase-machine is gemodelleerd met expliciete active/completed phases en
  dispatch-rollen. Onbekende `AI Phase` waarden worden niet stil als lege fase
  behandeld maar naar `Error` gezet.
- De poller is een Spring Scheduled-task met 15 seconden default interval. Hij
  draait standaard via de scheduled pollers zodra de applicatie gestart is
  omdat de echte Docker runtime pas in KAN-004 komt; de service zelf is wel
  volledig testbaar en handmatig aanroepbaar.
- `Paused` en `Error` blokkeren dispatch. Wachtfases
  `refined-with-questions-for-user` en `tested-successfully` doen niets.
- Bij dispatch opent of hergebruikt de orchestrator een actief `story_runs`
  record, zet `AI Phase` en `AgentStartedAt`, maakt een dispatch-request met
  Docker-labels (`app=factory-agent`, `story-key`, `role`) en registreert de
  gestartte agent-run in `agent_runs`.
- Active phases zonder draaiende agent gaan door stuck recovery: succesvolle
  DB-run geeft forward recovery, transient failures gaan terug naar de vorige
  completed phase tot de retry-cap, en hard timeouts schrijven `Error`.
- Concurrency caps per rol en totaal worden via de agent-runtime port
  afgedwongen. Per story mag niet meer dan een agent tegelijk draaien.
- De developer-loopback cap schrijft `Error` zodra de zesde loopback bereikt
  zou worden.
- `mvn test` is groen met state-machine tests voor skip-logica, dispatch,
  concurrency caps, forward recovery, transient retry, hard timeout en
  developer-loopback cap.
- `./factory start` is handmatig gestart om Spring wiring, Flyway en de
  scheduler-beans te controleren; de app startte succesvol en is daarna
  handmatig gestopt.
