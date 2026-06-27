# Runbook — Software Factory

> Dit bestand is bedoeld voor de Telegram-assistent (en mensen): het beschrijft wat dit project is,
> waar het draait, hoe je het lokaal draait/test, welke config & secrets er zijn, en hoe je
> veelvoorkomende taken aanpakt. Houd het kort en actueel; verandert het systeem, pas dit aan.

## Wat is dit

De Software Factory stuurt AI-agents aan om software-stories te bouwen via een vaste keten:
**refine → plan → develop → review → test → summary → merge**. Stories en hun fases worden in
**YouTrack** beheerd; per story bepaalt het `Repo`-veld voor welk project/repo gewerkt wordt
(mapping staat in `projects.yaml`). Een story met een lege fase of leeg `Repo`-veld wordt **niet**
opgepakt.

## Architectuur

- **`softwarefactory`** (module) — de hoofd-app: orchestrator, YouTrack-integratie, web-dashboard,
  Telegram-integratie. Entrypoint: `SoftwareFactoryApplication`. Kotlin + Spring Boot.
- **`agentworker`** (module) — de CLI die *in een Docker-container* draait per agent-taak; leest
  `/work/task.md`, roept de AI-CLI aan (claude/codex/copilot), schrijft `/work/agent-result.json`.
- **`dashboard-backend`** + **`dashboard-frontend`** — een aparte, nieuwere dashboard-UI (los van het
  ingebouwde dashboard in `softwarefactory`).
- **Agents draaien in Docker** (`agent:local` image, zie `Dockerfile.agent`), aangestuurd door
  `DockerAgentRuntime` via `docker run`, met de werkmap gemount op `/work`.
- **Orchestrator** pollt adaptief (`OrchestratorPoller`); fase-velden in YouTrack sturen het werk
  (lege fase = niet starten, `start` = oppakken).

## Waar draait het

- **De factory zelf:** lokaal, vanuit IntelliJ (`SoftwareFactoryApplication`). Niet in productie/cluster.
- **YouTrack:** op de OpenShift-cluster — API SF_YOUTRACK_BASE_URL (uit secrets.env)
  publieke URL SF_YOUTRACK_PUBLIC_URL (uit secrets.env) (via Cloudflare).
- **PostgreSQL:** zie SF_DATABASE_URL uit secrets.env

## Overige infra
- **OpenShift/OKD:** De software factory zelf gebruik openshift niet, maar hij deployed het daar soms wel via github actions, dat is te controleren in SF_KUBECONFIG.

## Lokaal draaien & testen

- **Build:** Maven (`mvn -pl softwarefactory compile` / `mvn -pl softwarefactory test`).
- **Draaien:** vanuit IntelliJ de `SoftwareFactoryApplication`-run, of `mvn -pl softwarefactory spring-boot:run`
- **Webserver/dashboard:** standaard poort 8080 (niet expliciet gezet in `application.yml`)
  Login via `SF_DASHBOARD_PASSWORD`.
- **Afhankelijkheden om te draaien:** een bereikbare PostgreSQL + YouTrack (zie secrets), en Docker
  (voor de agents). Flyway draait de DB-migraties automatisch bij opstart.
- **Logs:** `logs/softwarefactory.log` (roterend).

## Config & secrets
Geladen door `SecretsEnvLoader` in lagen (laagste eerst, env-vars winnen altijd):
1. `properties.default.env` (committed, defaults) → 2. `properties.env` (lokaal) → 3. `secrets.env` (lokaal, geheim).
Plus `projects.yaml` (naam → repo + Telegram-kanaal), naast `secrets.env`.

**Verplichte secrets** (`SF_YOUTRACK_BASE_URL`, `SF_YOUTRACK_TOKEN`, `SF_GITHUB_TOKEN`,
`SF_DATABASE_URL`, `SF_DATABASE_SCHEMA`). **Optioneel o.a.**: `SF_KUBECONFIG`, `SF_AI_OAUTH_TOKEN`
(of `SF_AI_CREDENTIALS_DIR`), `SF_TELEGRAM_BOT_TOKEN`, `SF_TELEGRAM_CHAT_ID`.

> Bestanden staan lokaal (gitignored). Voor de assistent worden ze read-only beschikbaar in
> `/softwarefactory/private/`.

## Database
- PostgreSQL; verbinding via `SF_DATABASE_URL`, schema `SF_DATABASE_SCHEMA`.
- Migraties: Flyway, `softwarefactory/src/main/resources/db/migration` (`V1..Vn`).
- Belangrijke tabellen: `story_runs`, `agent_runs`, events; de Telegram-tabellen
  (`telegram_notifications`, `telegram_pending_questions`, `telegram_state`, `telegram_conversations`);
  en de nightly-scheduler-tabellen (`nightly_settings`, `nightly_run`, `nightly_run_job`).

## Externe systemen
- **YouTrack** — bron van stories/subtaken + fases. Client: `YouTrackClient`. Custom fields o.a.
  `Story Phase`, `Subtask Phase`, `Repo`, `AI-supplier`.
- **GitHub** — PR's/merges van de agent-runs (`SF_GITHUB_TOKEN`); merge = `gh pr merge --squash`.
- **OpenShift** — `oc`/`kubectl` met `SF_KUBECONFIG`; o.a. waar YouTrack draait.
- **Telegram** — meldingen + assistent (`SF_TELEGRAM_*`, kanalen per project in `projects.yaml`).

## Veelvoorkomende taken / troubleshooting
- **"Waarom wordt story X niet opgepakt?"** Check: staat het `Repo`-veld gevuld (anders error)? Staat de
  `Story Phase` op `start` (lege fase = niet oppakken)? Staat er een error op de story? Draait er al een agent?
- **Story handmatig starten:** zet `Story Phase` op `start`.
- **Vastgelopen/erroring story:** bekijk de error op het issue + `logs/softwarefactory.log`.
- **Fase-overzicht:** zie `StoryPhase` / `SubtaskPhase` in `core/`.

## Conventies
- Taal in code/commentaar en commits: Nederlands.
- Werk niet in iemands actieve werkmap; agents/checkouts zijn geïsoleerd.
