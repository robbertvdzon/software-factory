# Technische documentatie

Deze map beschrijft hoe de Software Factory code werkt op basis van de huidige hoofdbranch (`main`).

## Samenvatting

- Scheduled jobs: 3
- Externe systemen: 6 hoofdgroepen
- Applicatiemodules: 15 directe Kotlin packages onder `nl.vdzon.softwarefactory`
  (module `softwarefactory`), naast de losse modules `agentworker` en
  `dashboard-backend` en de Flutter `dashboard-frontend`.

## Bestanden

- [overview.md](overview.md) - architectuur, hoofdflow en dataflow.
- [scheduled-jobs.md](scheduled-jobs.md) - alle Spring scheduled jobs.
- [endpoints.md](endpoints.md) - alle HTTP endpoints.
- [external-systems.md](external-systems.md) - externe systemen en aanroepwijze.
- [modules.md](modules.md) - modules en verantwoordelijkheden.
