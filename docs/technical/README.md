# Technische documentatie

Deze map beschrijft hoe de Software Factory code werkt op basis van de huidige hoofdbranch (`main`).

## Samenvatting

- Scheduled jobs: cost monitor, agent result poller, audit-tick, work cleanup poller, Telegram-
  resultaatmelding poller (`@Scheduled`) plus 2 eigen daemon-threads (orchestrator poller,
  telegram poller). Zie `scheduled-jobs.md` voor het volledige overzicht.
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
- [module-dependencies.md](module-dependencies.md) - gegenereerde Modulith-matrix en dependencydiagram.
- [quality-ratchet.md](quality-ratchet.md) - Detekt-gate, fingerprint-normalisatie en baselineformaat.
