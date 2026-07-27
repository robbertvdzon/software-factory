# Software Factory

Software Factory is a self-built platform that lets AI agents carry out software development,
while you stay in control. You create a story, the factory refines it, drafts a plan, and then
works through it subtask by subtask — development, review, test, documentation, merge, and
deploy — without you ever having to open a pull request yourself. Wherever a human is needed, the
factory simply asks.

## What can Software Factory do?

### From idea to story, then straight to work

Create a story through the dashboard, or just by telling the Telegram assistant, pick which
project/repo it belongs to, and set it to `start`. The factory first does a **refinement** step
(sharpening the story) and a **planning** step (deciding the approach and declaring the
subtasks), then works through those subtasks one by one on a shared story branch: development →
review → test → summary → documentation → (optional manual approval) → merge → deploy. Per
project, you decide how much automation you trust — fully automatic, or with an approval gate
right before the merge.

### Is the AI stuck? It just asks

A refiner, planner, or subtask agent that isn't sure about something doesn't guess: it puts the
story or subtask on hold and asks the lead developer — a human — a question, via both the
dashboard and Telegram. As soon as you answer, the agent picks up right where it left off.

### Every night, an audit that creates its own follow-up work

Besides the work you request, a read-only **audit** runs every night per project: code quality,
security, ADR compliance, consistency, documentation, or test coverage. The audit never changes
anything itself, but writes a report and — if it finds something structural — proposes at most 1
small follow-up story to fix it. That story then simply goes through the normal
refine → plan → subtasks flow above. Start time and the number of audits per night are
configurable per project.

### Everything in one overview

The dashboard gives you an at-a-glance view of the whole factory: which **agents** are active
right now and how long they've been running, the **builds** and deploy status per project and
branch, and a **projects** overview with live components and downloads. No more keeping a
separate tab open per repo — it's all in one place.

### Telegram: your own factory assistant in your pocket

Besides notifications (a question, a failure, "done"), you can also just talk to the factory in
Telegram: check a story's status, create a new story, steer a running story — a real assistant
with memory, not a fixed command list. Questions the AI asks you can also be answered directly
from Telegram, without needing to open the dashboard.

## Reading guide

Read in this order, depending on what you're looking for:

1. [runbook.md](runbook.md) — operations: what runs where, config/secrets, troubleshooting.
2. [docs/factory/functional-spec.md](docs/factory/functional-spec.md) — **what** the factory does (functionally).
3. [docs/factory/technical-spec.md](docs/factory/technical-spec.md) — **how** it's built (stack, modules, config).
4. [docs/onboarding-senior-developer.md](docs/onboarding-senior-developer.md) — onboarding for new (senior) developers: the mental model, the main flow, the reasoning behind the architecture, test strategy, cookbooks, and a review checklist.
5. [docs/kwaliteitsanalyse.md](docs/kwaliteitsanalyse.md) — the quality analysis and the recent refactor (phases 1 through 4, July 2026).

In addition: [docs/technical/](docs/technical/) (generated technical reference) and
[specs/specs.md](specs/specs.md) (historical archive).

## Process overview

Software Factory works with a **two-tier model** in its own tracker database (Postgres):

1. **Story level** (`Story Phase`, see `core/StoryPhase.kt`) — the refinement process:
   a refiner sharpens the story, a planner drafts an implementation plan and
   declares the subtasks.
2. **Subtask level** (`Subtask Type` + `Subtask Phase`, see `core/contracts/WorkflowModels.kt`
   and `core/SubtaskPhase.kt`) — the execution: the declared subtasks are carried out one
   by one on a shared story branch.

Work starts explicitly: a story (or subtask) is **only picked up once its phase is set to
`start`**; an empty phase means "not started yet." There are no more work tags/labels. The repo
being worked on comes from the story's `Repo` field, which points to a project in
`projects.yaml` (see §1b).

### Story level: refine and plan

```mermaid
flowchart TD
    START["start"] --> REF["refining<br/>(refiner agent)"]
    REF -->|"questions"| REFQ["refined-with-questions<br/>waiting for user answer"]
    REFQ -->|"questions-answered"| REF
    REF --> REFINED["refined"]
    REFINED -->|"approval (or automatic)"| REFAPP["refined-approved"]
    REFAPP --> PLAN["planning<br/>(planner agent)"]
    PLAN -->|"questions"| PLANQ["planned-with-questions<br/>waiting for user answer"]
    PLANQ -->|"planning-questions-answered"| PLAN
    PLAN --> PLANNED["planned<br/>subtasks are created"]
    PLANNED -->|"approval (or automatic)"| PLANAPP["planning-approved"]
    PLANAPP -->|"Start developing"| INPROG["in-progress<br/>subtask chain runs"]
```

Rejection is also possible: `refined-rejected`/`planning-rejected` send the refiner/planner
back to work with the rejection reason.

### Subtask level: the chain

The planner declares subtasks of type `development`, `review`, `test`,
`manual`, and `summary`. The factory additionally always enforces these closing subtasks
per story (in `SubtaskPlanMaterializer`):

- `documentation` — a documenter agent updates the docs (always on);
- `manual-approve` — a manual approval gate right before the merge (can be turned off per
  project via `projects.yaml`; always skipped when approval mode = `automatic`, SF-1261);
- `merge` — automatic squash-merge of the story PR;
- `deploy` — deploy according to `projects.yaml` (skip / rest-restart / openshift-watch).

> **Audits (`.factory/nightly/`):** every morning, at most 1 audit runs per project — a
> read-only agent run that does **not** go through the development pipeline above (no Subtask,
> no tracker story for the audit itself). Start time and the number of audits per night are
> configurable per project; multiple audits for the same project always run one after another,
> never at the same time. An audit writes a report and proposes at most 1 follow-up story; that
> story *does* go through the normal pipeline above. See `.factory/nightly/README.md`.

```mermaid
flowchart LR
    DEV["development"] --> REV["review"] --> TEST["test"] --> SUM["summary"]
    SUM --> DOC["documentation"] --> APPR["manual-approve"] --> MERGE["merge"] --> DEP["deploy"]
```

Every AI subtask follows the same pattern on the `Subtask Phase` field:
`start → *-ing → (*-with-questions ↔ *-questions-answered) → *-ed → *-approved`
(or `*-rejected` for a loopback to the developer). Once a subtask reaches its
terminal phase, the chain sets the next subtask to `start`. With approval mode =
`automatic`/`manual-gate-only`, the approval steps proceed automatically; the
`manual-approve` gate always asks a human once it's materialized
(approval mode = `manual-gate-only`/`every-step`), but is always
skipped with `automatic` (SF-1261, see also `docs/factory/functional-spec.md`). A
test finding (`test-rejected`) resets the whole chain, capped at
`SF_MAX_TEST_CHAIN_RESETS` (default 3).

During execution, the working document lives at
`docs/stories/worklog/<key>-worklog.md`; the summarizer produces the final text and the
factory writes the final document to `docs/stories/<key>-<slug>.md`.

## Running permanently via a LaunchAgent (macOS)

`factory-loop.sh` can also run as a macOS LaunchAgent instead of being started manually.
**This is how it runs on Robbert's laptop**: the factory then starts automatically as soon as
the laptop boots and you log in, and also restarts automatically after a crash — without
needing to keep a terminal open. See
[docs/onboarding-senior-developer.md](docs/onboarding-senior-developer.md) section 7 for the
plist file to set it up.

If it's already running as a LaunchAgent, use these commands instead of starting the script
again yourself:

```bash
# Is it running?
launchctl list | grep factory-loop

# Follow live logs
tail -f ~/git/softwarefactory/work/factory-loop.log

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

## 1b. Linking projects to repos

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

## 2. Start Docker services

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

## 3. Build the code

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

## 4. Build agent images

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

## 5. Start Software Factory

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

## Handy commands

Start a local AI coding agent with Ollama + OpenHands:

```bash
LOCAL_WORKSPACE="$(pwd)" docker compose -f docker/local-ai/docker-compose.yml up -d --build
```

See [docker/local-ai/README.md](docker/local-ai/README.md) for the full
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

## 6. Create a story

- Via the dashboard, or via the Telegram assistant (`sf-story create ...`).
- On a story: choose a `Repo` (from `projects.yaml`, see §1b), set
  `AI-supplier` (e.g. `claude` or `mock`), and set `Story Phase` to `start` to
  have it picked up.
