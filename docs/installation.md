# Installation and running

Everything you need to get Software Factory running on your own machine. For what the factory
*does*, see the [README](../README.md); for day-to-day operations see [runbook.md](../runbook.md).

## Requirements

- JDK 21
- Maven
- Docker Desktop or a working Docker Engine
- GitHub token with access to the target repositories
- No local Flutter SDK is needed for the dashboard frontend; the Docker build
  uses a Flutter builder image.

## 1. Create secrets

Create a local `secrets.env` at the root of this repo:

```bash
cp secrets.env.example secrets.env
```

Then fill in at least these values:

```env
SF_GITHUB_TOKEN=...
SF_GOOGLE_CLIENT_ID=...apps.googleusercontent.com
SF_ALLOWED_EMAILS=you@example.com
SF_DASHBOARD_REMEMBER_SECRET=...
SF_BRIDGE_TOKEN=...
SF_BRIDGE_URLS=ws://localhost:9090/bridge
```

The example already points to the local Docker Postgres:

```env
SF_DATABASE_URL=postgresql://software_factory:software_factory@localhost:5432/software_factory
SF_DATABASE_SCHEMA=software_factory_dev
```

The application always polls the tracker database as soon as it runs. Make sure PostgreSQL and
the required secrets are correct before starting the application.

## 2. Linking projects to repos

The repo a story works on comes from a config file next to `secrets.env`:

```bash
cp projects.yaml.example projects.yaml
```

Fill in a name and git repo per logical project:

```yaml
projects:
  - name: personal-feed
    repo: git@github.com:robbertvdzon/personal-feed.git
```

On a story, you pick one of these project names in the **`Repo`** field; the factory uses the
matching repo. The choices come straight from `projects.yaml`. A single project can hold stories
for multiple repos this way; subtasks automatically inherit the repo from their parent story. A
story with an empty `Repo` field is not picked up and gets an `Error`.

Which projects get scanned is determined by `SF_TRACKER_PROJECTS` (empty = all). The config
file's path can be overridden with `SF_PROJECTS_FILE`.

## 3. Start Docker services

Start PostgreSQL, dashboard-backend, and dashboard-frontend:

```bash
./factory local-services
```

PostgreSQL then runs on `localhost:5432`.

The external dashboard runs on:

```text
http://localhost:9080
```

The dashboard-backend is directly reachable at:

```text
http://localhost:9090
```

Then start the factory; it connects outbound with the same bridge token:

```bash
./factory start
```

Repeatable health/auth/bridge smoke test with isolated containers and automatic teardown:

```bash
docker/smoke-local-quickstart.sh
```

## 4. Build the code

Build and test the Maven projects from the root. Fast unit run:

```bash
mvn test
```

Full safety net including e2e/Testcontainers tests (Docker required):

```bash
mvn verify
```

A story is only ready to merge once this full safety net returns exit code 0. The GitHub check
`tools/verify-repository` is the local full gate (versioned command ID:
`repository-verification/v1`). The GitHub check `Repository verification` evaluates the same
backend, Flutter, and agent image components. `projects.yaml` contains the exact
`merge.requiredChecks` per repo: queued/in-progress waits without an Error; missing, skipped,
cancelled, or red blocks fail-closed. Green proof only counts for the current PR head, and that
SHA is the atomic merge precondition. "Pre-existing" test failures are not an exception: they
go back to development for a fix or human escalation.

Or build packages:

```bash
mvn package
```

The Flutter dashboard frontend is separate from the Maven build.

## 5. Build agent images

Software Factory starts agent runs via local Docker images. Build these on
every machine where you run the main application:

```bash
./factory build-images
```

This creates:

```text
agent:local
```

A single shared image for all agent roles. Without this step, an agent run fails
with a Docker error saying `agent:local` cannot be found.

## 6. Start Software Factory

Start the application from the root, so `./secrets.env` is found:

```bash
mvn -f softwarefactory/pom.xml spring-boot:run
```

Or use the helper script:

```bash
./factory start
```

The local web interface runs by default on:

```text
http://localhost:8080
```

## 7. Create a story

- Via the dashboard, or via the Telegram assistant (`sf-story create ...`).
- On a story: choose a `Repo` (from `projects.yaml`, see step 2), set
  `AI-supplier` (e.g. `claude` or `mock`), and set `Story Phase` to `start` to
  have it picked up.
- New stories default to the `Als deployed` notification preset, stored as the concrete event set
  `QUESTION`, `MANUAL_ACTION_REQUIRED`, `ERROR`, `DEPLOYED`. Choose another preset or edit the
  individual events on story detail when different behavior is desired.

## Running permanently via a LaunchAgent (macOS)

`factory-loop.sh` can also run as a macOS LaunchAgent instead of being started manually.
**This is how it runs on Robbert's laptop**: the factory then starts automatically as soon as
the laptop boots and you log in, and also restarts automatically after a crash — without
needing to keep a terminal open. See
[onboarding-senior-developer.md](onboarding-senior-developer.md) section 7 for the
plist file to set it up.

If it's already running as a LaunchAgent, use these commands instead of starting the script
again yourself:

```bash
# Is it running?
launchctl list | grep factory-loop
```

```bash
# Follow live logs
tail -f ~/git/softwarefactory/work/factory-loop.log
```

```bash
# (Re)start — also needed after a Stop via the UI, since the LaunchAgent
# won't restart it automatically in that case
launchctl kickstart -k gui/$(id -u)/nl.vdzon.factory-loop
```

## Maven modules

The root `pom.xml` is the Maven parent and aggregator for five modules:

- **`factory-contracts`** — lightweight wire contracts for agent results and bridge frames.
- **`factory-common`** — shared tooling/config code (git, github, docs/skeleton, preview,
  support, `AgentRole`, `ProjectConfiguration`).
- **`softwarefactory`** — the main application: orchestrator, pipeline, tracker
  (`tracker` package, its own Postgres tables), built-in HTML dashboard, Telegram, audits.
- **`agentworker`** — the CLI that runs inside the agent Docker container.
- **`dashboard-backend`** — JSON API for the Flutter `dashboard-frontend`
  (which itself sits outside the Maven build).

Tests are split: `mvn test` runs the fast unit suite; `mvn verify` additionally runs
the e2e/Testcontainers tests (requires a running Docker).

## Handy commands

Start a local AI coding agent with Ollama + OpenHands:

```bash
LOCAL_WORKSPACE="$(pwd)" docker compose -f docker/local-ai/docker-compose.yml up -d --build
```

See [../docker/local-ai/README.md](../docker/local-ai/README.md) for the full
setup and usage instructions.

Start all local services:

```bash
docker compose up -d --build
```

Stop all local services:

```bash
docker compose stop
```

Start only PostgreSQL:

```bash
./factory local-db
```

Stop only PostgreSQL:

```bash
./factory local-db-stop
```
