# Runbook — Software Factory

> Dit bestand is bedoeld voor de Telegram-assistent (en mensen): het beschrijft wat dit project is,
> waar het draait, hoe je het lokaal draait/test, welke config & secrets er zijn, en hoe je
> veelvoorkomende taken aanpakt. Houd het kort en actueel; verandert het systeem, pas dit aan.

## Wat is dit

De Software Factory stuurt AI-agents aan om software-stories te bouwen via een vaste keten:
**refine → plan → develop → review → test → summary → documentation → manual-approve → merge → deploy**
(de documentation-stap en de afsluitende merge/deploy worden altijd door de factory afgedwongen;
de manual-approve-poort is per project uit te zetten en vervalt bij silent stories). Stories en hun fases worden in
de **eigen tracker-database van de factory** beheerd (PostgreSQL, geen externe issue-tracker); per
story bepaalt het `Repo`-veld voor welk project/repo gewerkt wordt
(mapping staat in `projects.yaml`). Een story met een lege fase of leeg `Repo`-veld wordt **niet**
opgepakt. Stories aanmaken/aanpassen/opvragen kan via de `sf-story`-tool (zie de Telegram-assistent)
of het dashboard.

## Architectuur

- **`factory-common`** (module) — gedeelde code tussen de modules (git/github, docs-skeleton,
  preview, support, het agent-result-contract).
- **`softwarefactory`** (module) — de hoofd-app: orchestrator, tracker-integratie
  (`tracker`-package, backend is Postgres), Telegram-integratie. Entrypoint:
  `SoftwareFactoryApplication`. Kotlin + Spring Boot.
- **`agentworker`** (module) — de CLI die *in een Docker-container* draait per agent-taak; leest
  `/work/task.md`, roept de AI-CLI aan (claude/codex/copilot), schrijft `/work/agent-result.json`.
- **`dashboard-backend`** + **`dashboard-frontend`** — de dashboard-UI (Flutter). Leest dezelfde
  `projects.yaml` (of `SF_PROJECTS_FILE`) voor de repo-lijst; machine-lokale acties (workspace in
  IntelliJ openen) vereisen `SF_DASHBOARD_LOCAL_MODE=true` (default uit, dus veilig in k8s).
- **Agents draaien in Docker** (`agent:local` image, zie `Dockerfile.agent`), aangestuurd door
  `DockerAgentRuntime` via `docker run`, met de werkmap gemount op `/work`.
- **Orchestrator** gebruikt een vaste poll-interval met event-driven wake (`OrchestratorPoller`);
  fase-velden in de tracker-database sturen
  het werk (lege fase = niet starten, `start` = oppakken).

## Waar draait het

- **De factory zelf:** lokaal, vanuit IntelliJ (`SoftwareFactoryApplication`). Niet in productie/cluster.
- **PostgreSQL (incl. tracker-tabellen):** zie SF_DATABASE_URL uit secrets.env

## Overige infra
- **OpenShift/OKD:** De software factory zelf gebruik openshift niet, maar hij deployed het daar soms wel via github actions, dat is te controleren in SF_KUBECONFIG.

## Lokaal draaien & testen

- **Build:** Maven vanaf de root: `mvn test` (snelle unit-run) of `mvn verify` (incl.
  e2e/Testcontainers; Docker vereist). Eén module bouwen kan met `mvn -pl softwarefactory -am test`
  (de `-am` bouwt `factory-common` mee).
- **Draaien:** vanuit IntelliJ de `SoftwareFactoryApplication`-run, `mvn -pl softwarefactory
  spring-boot:run`, of permanent via `factory-loop.sh` als macOS LaunchAgent (start
  automatisch bij inloggen) — zie [docs/onboarding-senior-developer.md](docs/onboarding-senior-developer.md)
  sectie 7 voor het plist-bestand en de start/stop/status-commando's.
- **Webserver (interne endpoints):** standaard poort 8080 (niet expliciet gezet in `application.yml`).
  Het Kotlin HTML-dashboard is verwijderd (SF-825); gebruik de Flutter-frontend (`dashboard-backend`/
  `dashboard-frontend`) voor de UI.
- **Afhankelijkheden om te draaien:** een bereikbare PostgreSQL (zie secrets), en Docker
  (voor de agents). Flyway draait de DB-migraties automatisch bij opstart.
- **Logs:** `logs/softwarefactory.log` (roterend).

### Testerbewijs en verification-config

Iedere actieve target-repo moet op de actuele default branch een geldige
`.factory/verification.yaml` (`version: 1`) hebben. De agentworker voert na een AI-claim `tested`
de argv-commands zelf uit; de factory accepteert alleen complete `passed`/exit-0 evidence voor de
ongewijzigde HEAD en exacte worktree-treehash. Missing/unknown config, tool-missing, timeout,
non-zero, gemanipuleerd proza en revisionmismatch resetten de keten naar development.

Valideer rollout met exact de productieparser:

```bash
mvn -q -pl factory-common -DskipTests package dependency:build-classpath -Dmdep.outputFile=/tmp/factory-cp
java -cp "factory-common/target/classes:$(tr -d '\n' </tmp/factory-cp)" \
  nl.vdzon.softwarefactory.verification.VerificationConfigValidatorCli \
  /pad/naar/repo [/pad/naar/volgende-repo]
```

Bij reject: lees de `[FACTORY VERIFICATION]`/`[FACTORY EVIDENCE REJECTED]`-diagnose. Herstel config,
tooling, testfailure of revisionverschil; zet nooit tijdelijk fail-open en keur flaky/pre-existing
of omgevingsfouten niet goed.

## Config & secrets
Geladen door `SecretsEnvLoader` in lagen (laagste eerst, env-vars winnen altijd):
1. `properties.default.env` (committed, defaults) → 2. `properties.env` (lokaal) → 3. `secrets.env` (lokaal, geheim).
Plus `projects.yaml` (naam → repo + Telegram-kanaal + verplichte `merge.requiredChecks`), naast
`secrets.env`. De factory start niet wanneer een projectrepository geen niet-lege mergepolicy heeft.

**Verplichte secrets** (`SF_GITHUB_TOKEN`, `SF_DATABASE_URL`, `SF_DATABASE_SCHEMA`). **Optioneel o.a.**:
`SF_TRACKER_PROJECTS`, `SF_KUBECONFIG`, `SF_AI_OAUTH_TOKEN` (of `SF_AI_CREDENTIALS_DIR`),
`SF_TELEGRAM_BOT_TOKEN`, `SF_TELEGRAM_CHAT_ID`, `SF_FACTORY_API_TOKEN` (nodig voor `/api/restart` en
de `sf-story`-tool van de assistent).

Voor het losse dashboard zijn daarnaast `SF_GOOGLE_CLIENT_ID`,
`SF_DASHBOARD_REMEMBER_SECRET` en `SF_BRIDGE_TOKEN` verplicht. De lokale factory gebruikt
`SF_BRIDGE_URLS=ws://localhost:9090/bridge`. Canonieke quickstart en teardown:

```bash
./factory local-services
./factory start
./factory local-services-stop
```

`docker/smoke-local-quickstart.sh` controleert geïsoleerd health, unauthenticated `401`,
authenticated `200` met `connected=true` en ruimt altijd op.

> Bestanden staan lokaal (gitignored). Voor de assistent worden ze read-only beschikbaar in
> `/softwarefactory/private/`.

## Database
- PostgreSQL; verbinding via `SF_DATABASE_URL`, schema `SF_DATABASE_SCHEMA`.
- Migraties: Flyway, `softwarefactory/src/main/resources/db/migration` (`V1..Vn`).
- Belangrijke tabellen: `story_runs`, `agent_runs`, events; de Telegram-tabellen
  (`telegram_notifications`, `telegram_pending_questions`, `telegram_state`, `telegram_conversations`);
  en de nightly-scheduler-tabellen (`nightly_settings`, `nightly_run`, `nightly_run_job`).

## Externe systemen
- **Tracker-database** — bron van stories/subtaken + fases; PostgreSQL, via `PostgresTrackerClient`
  (interface `TrackerApi`). Velden o.a. `Story Phase`, `Subtask Phase`, `Repo`, `AI-supplier`.
- **GitHub** — PR's/merges van de agent-runs (`SF_GITHUB_TOKEN`). Automatische en handmatige merge
  lopen door één projectpolicy; alleen groene check-runs op de actuele head worden gemerged met
  `gh pr merge --squash --match-head-commit <sha>`.
- **OpenShift** — `oc`/`kubectl` met `SF_KUBECONFIG`.
- **Telegram** — meldingen + assistent (`SF_TELEGRAM_*`, kanalen per project in `projects.yaml`).

## Veelvoorkomende taken / troubleshooting
- **"Waarom wordt story X niet opgepakt?"** Check: staat het `Repo`-veld gevuld (anders error)? Staat de
  `Story Phase` op `start` (lege fase = niet oppakken)? Staat er een error op de story? Draait er al een agent?
- **Story handmatig starten:** zet `Story Phase` op `start`.
- **Vastgelopen/erroring story:** bekijk de error op het issue + `logs/softwarefactory.log`.
- **Merge wacht:** queued/in-progress is normaal en wordt opnieuw gepolld. Missing/skipped/
  cancelled/failed of een API-/parsefout is blocked; controleer de exacte checknaam onder
  `merge.requiredChecks` en de check-runs op de actuele PR-head. Een nieuwe push na groen bewijs
  veroorzaakt veilig een nieuwe beoordeling.
- **Fase-overzicht:** zie `StoryPhase` / `SubtaskPhase` in `core/`.
- **Work-cleanup:** `WorkCleanupPoller` scant elk uur de vier beheerde `work/`-roots. Actieve
  story-, agent- en assistantpaden zijn hard uitgesloten, ook als hun mtime ouder is dan
  `SF_WORK_CLEANUP_RETENTION_DAYS` (default 7; exact op de grens mag alleen inactief weg).
  Een fout bij het bepalen van actieve paden slaat de hele tick over. Entryfouten worden apart
  gelogd en symlinks worden niet buiten de beheerde root gevolgd. Controleer bij twijfel
  `logs/softwarefactory.log` op `Work cleanup skipped` of `Work cleanup failed`; zet de scheduler
  tijdelijk uit met `SF_WORK_CLEANUP_ENABLED=false`, niet door handmatig actieve mappen te wissen.

## Conventies
- Taal in code/commentaar en commits: Nederlands.
- Werk niet in iemands actieve werkmap; agents/checkouts zijn geïsoleerd.
