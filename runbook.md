# Runbook — Software Factory

> Dit bestand is bedoeld voor de Telegram-assistent (en mensen): het beschrijft wat dit project is,
> waar het draait, hoe je het lokaal draait/test, welke config & secrets er zijn, en hoe je
> veelvoorkomende taken aanpakt. Houd het kort en actueel; verandert het systeem, pas dit aan.

## Wat is dit

De Software Factory stuurt AI-agents aan om software-stories te bouwen via een vaste keten:
**refine → plan → develop → review → test → summary → documentation → manual-approve → merge → deploy**
(de documentation-stap en de afsluitende merge/deploy worden altijd door de factory afgedwongen;
de manual-approve-poort wordt toegevoegd bij goedkeuring=`alleen-manual-poort`/`elke-stap` en
vervalt bij `automatisch`). Stories en hun fases worden in
de **eigen tracker-database van de factory** beheerd (PostgreSQL, geen externe issue-tracker); per
story bepaalt het `Repo`-veld voor welk project/repo gewerkt wordt
(mapping staat in `projects.yaml`). Een story met een lege fase of leeg `Repo`-veld wordt **niet**
opgepakt. Stories aanmaken/aanpassen/opvragen kan via de `sf-story`-tool (zie de Telegram-assistent)
of het dashboard.

## Architectuur

- **`factory-contracts`** (module) — gedeelde agent-result- en bridgewirecontracten.
- **`factory-common`** (module) — gedeelde code tussen de modules (git/github, docs-skeleton,
  preview, support en projectconfig).
- **`softwarefactory`** (module) — de hoofd-app: orchestrator, tracker-integratie
  (`tracker`-package, backend is Postgres), Telegram-integratie. Entrypoint:
  `SoftwareFactoryApplication`. Kotlin + Spring Boot.
- **`agentworker`** (module) — de CLI die *in een Docker-container* draait per agent-taak; leest
  `/work/task.md`, roept de AI-CLI aan (claude/codex/copilot), schrijft `/work/agent-result.json`
  en bewaart daarin voor Claude het laatste bruikbare rate-limit-event.
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

Voor het losse dashboard zijn daarnaast `SF_GOOGLE_CLIENT_ID`, `SF_ALLOWED_EMAILS`
(niet-lege allowlist; geen default meer sinds SF-1551), `SF_DASHBOARD_REMEMBER_SECRET`
en `SF_BRIDGE_TOKEN` verplicht. De lokale factory gebruikt
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
- Belangrijke tabellen: `issues` (incl. `retry_after` voor automatische Claude-quotawacht),
  `issue_comments`/`issue_attachments` (comments en bijlagen bij die issues),
  `story_runs`, `agent_runs` (incl. Claude-rate-limitstatus/reset-timestamps), events;
  `agent_knowledge` (herbruikbare agent-tips per repo/rol), `processed_comments` (al verwerkte
  comments) en `system_state` (globale state zoals credits-pauzes); de Telegram-tabellen
  (`telegram_notifications`, `telegram_pending_questions`, `telegram_state`,
  `telegram_conversations`, `telegram_threads`);
  en de audit-tabellen (`audit_settings`, `audit_run`, `audit_run_job`, `audit_report`,
  `audit_project_settings` voor per-project starttijd/aantal en `audit_question` voor een
  openstaande auditvraag); en `maintenance_cleanup_runs` (historie van de nachtelijke
  release/package-opruiming, sinds SF-1913 in plaats van een Telegram-melding).
  De oudere `nightly_settings`/`nightly_run`/`nightly_run_job`-tabellen zijn ongebruikte resten van
  de vroegere nightly scheduler (module verwijderd, tabellen bewust niet gedropt).

## Externe systemen
- **Tracker-database** — bron van stories/subtaken + fases; PostgreSQL, via `PostgresTrackerClient`
  (interface `TrackerApi`). Velden o.a. `Story Phase`, `Subtask Phase`, `Repo`, `AI-supplier` en
  het optionele absolute `RetryAfter` (automatische Claude-quotawacht, los van `Paused`).
- **GitHub** — PR's/merges van de agent-runs (`SF_GITHUB_TOKEN`). Automatische en handmatige merge
  lopen door één projectpolicy; alleen groene check-runs op de actuele head worden gemerged met
  `gh pr merge --squash --match-head-commit <sha>`.
- **OpenShift** — `oc`/`kubectl` met `SF_KUBECONFIG`.
- **Telegram** — meldingen + assistent (`SF_TELEGRAM_*`, kanalen per project in `projects.yaml`).

## Veelvoorkomende taken / troubleshooting
- **"Waarom wordt story X niet opgepakt?"** Check: staat het `Repo`-veld gevuld (anders error)? Staat de
  `Story Phase` op `start` (lege fase = niet oppakken)? Staat er een error op de story? Draait er al
  een agent? Staat op de story of een subtaak `RetryAfter`, dan wacht de factory bewust op
  Claude-quota; het dashboard toont het lokale hervattijdstip. Vóór dat tijdstip niet handmatig
  herstarten. Op of erna dispatcht de factory dezelfde rol automatisch met een nieuw starttijdstip.
- **Claude-quotawacht duurt onverwacht lang:** controleer `retry_after` op story én subtaken en zoek
  in `logs/softwarefactory.log` naar `Claude quota wait scheduled`. Ontbreekt een geldige toekomstige
  Claude-resettijd, dan plant de factory steeds een hercontrole na vijftien minuten. Gebruik alleen
  bij bewust operatoringrijpen de bestaande reset/clear-error/re-implementatieactie; die wist de
  wachtstatus. `Paused` aan/uit zetten is hiervoor niet het juiste mechanisme.
- **Story handmatig starten:** zet `Story Phase` op `start`.
- **Vastgelopen/erroring story:** bekijk de error op het issue + `logs/softwarefactory.log`.
- **Audit staat op `asked`:** dat is een *eindtoestand* van die auditjob, geen vastloper — de
  auditor kon niet verder zonder menselijke beslissing en eindigde met een vraag in plaats van een
  rapport; bij díé job komt dus geen rapport meer. Dat is bewust: bleef de job niet-terminaal, dan
  zou de run nooit sluiten en zouden alle audits van alle projecten stilvallen (`AuditPlanner`). De
  vraag staat in `audit_question` en beantwoord je via het Audits-scherm in het dashboard
  (`POST /api/v1/audits/questions/answer`) of met een reply op de Telegram-melding. Na het antwoord
  plant de factory zelf een vervolgrun in die de audit afmaakt (binnen de volgende scheduler-tick,
  ~30s); handmatig herstarten is niet nodig.
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
- **Agent-event-retentie:** `AgentEventRetentionPoller` verwijdert elk uur `agent_events` ouder dan
  `SF_AGENT_EVENT_RETENTION_DAYS` (default 30), in batches van
  `SF_AGENT_EVENT_RETENTION_BATCH_SIZE` en hoogstens `SF_AGENT_EVENT_RETENTION_MAX_BATCHES` per
  ronde; de volgende tick gaat verder waar hij ophield. Dit is de logboekhistorie achter het
  Agent-log-scherm: na de retentiegrens is een oude run niet meer na te lezen. Uitzetten met
  `SF_AGENT_EVENT_RETENTION_ENABLED=false` — maar reken dan op onbeperkte groei; deze tabel was op
  2026-07-29 met 436 MB de grootste van de database, ruim de helft van het totaal.
- **Agent-run-retentie (SF-1921):** `AgentRunRetentionPoller` verwijdert elk uur `agent_runs` ouder
  dan `SF_AGENT_RUN_RETENTION_DAYS` (default 90), in batches van
  `SF_AGENT_RUN_RETENTION_BATCH_SIZE` (default 1000) en hoogstens
  `SF_AGENT_RUN_RETENTION_MAX_BATCHES` (default 20) per ronde. Dit is de bovenlaag van het
  Agent-log-scherm: na de grens is de run zelf weg, inclusief zijn events en completion-rijen (via
  `ON DELETE CASCADE`). Een lopende run (`ended_at IS NULL`) en een run met een onafgeronde
  completion (`PENDING`/`IN_PROGRESS`/`FAILED_RETRYABLE`) blijven altijd staan, ongeacht leeftijd —
  zie je zulke runs "te lang" in het scherm, dan is dat werk dat nog niet af is, geen retentiefout.
  Uitzetten met `SF_AGENT_RUN_RETENTION_ENABLED=false`.
- **Maintenance-cleanup (releases/images):** `MaintenanceCleanupScheduler` draait 's nachts
  (cron `sf.maintenance.cleanup-cron`, default `0 30 2 * * *` UTC) en ruimt per project met een
  `releaseCleanup:`-blok in `projects.yaml` oude GitHub-Releases en ghcr.io-package-versions op.
  Sinds SF-1913 gaat daar géén Telegram-bericht meer over: elke projectronde landt als rij in
  `maintenance_cleanup_runs` en is zichtbaar op het Opruimen-scherm van de dashboard-app (onder
  "Meer"). Staat er voor vannacht geen rij bij een project, dan heeft de ronde niet gedraaid;
  een rij met 0 verwijderingen betekent dat er niets op te ruimen viel. Een mislukte ronde staat er
  mét foutmelding in en blokkeert de overige projecten niet. Zet `sf.maintenance.dry-run=true` om
  alleen te loggen/registreren wat verwijderd zóú worden. De historie zelf wordt aan het eind van
  elke tick opgeruimd na `sf.maintenance.run-retention-days` (default 90).
- **Opruim-log (SF-1921):** `maintenance_cleanup_runs` is de gedeelde historie van álle
  opruimmechanismen; het Opruimen-scherm filtert op `kind` (`github-releases`, `agent-events`,
  `agent-runs`, `completion-payloads`, `workspaces`), met "alle soorten" als default. De vier
  factory-brede opruimers schrijven bewust alléén een rij bij verwijderingen of bij een fout — géén
  rij betekent daar dus "niets te doen", niet "niet gedraaid". Alleen de nachtelijke GitHub-cleanup
  schrijft ook bij 0. Wegschrijven is overal fail-soft: een mislukte insert levert hoogstens een
  warn-log op en laat de opruiming zelf gewoon slagen. De log valt zelf onder
  `sf.maintenance.run-retention-days` (default 90).

## Conventies
- Taal in code/commentaar en commits: Nederlands.
- Werk niet in iemands actieve werkmap; agents/checkouts zijn geïsoleerd.
