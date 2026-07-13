# Technische documentatie

Deze map beschrijft hoe de Software Factory code werkt op basis van de huidige hoofdbranch (`main`).

## Samenvatting

- Scheduled jobs: 4 `@Scheduled`-methodes (cost monitor, agent result poller, nightly-tick,
  nightly AI-verrijking) plus 2 eigen daemon-threads (orchestrator poller, telegram poller).
- HTTP endpoints: 39 (in de `softwarefactory`-module).
- Externe systemen: 7 hoofdgroepen.
- Maven-modules: 5 (`factory-contracts`, `factory-common`, `softwarefactory`, `agentworker`, `dashboard-backend`),
  plus de Flutter `dashboard-frontend` buiten de Maven-build. De `softwarefactory`-module
  heeft 12 directe Kotlin packages onder `nl.vdzon.softwarefactory`.

## Bestanden

- [overview.md](overview.md) - architectuur, hoofdflow en dataflow.
- [scheduled-jobs.md](scheduled-jobs.md) - alle Spring scheduled jobs.
- [endpoints.md](endpoints.md) - alle HTTP endpoints.
- [external-systems.md](external-systems.md) - externe systemen en aanroepwijze.
- [modules.md](modules.md) - modules en verantwoordelijkheden.
