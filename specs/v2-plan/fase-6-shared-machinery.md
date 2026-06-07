# Fase 6 — Gedeelde machinerie subtask-bewust maken

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.
>
> **Volgorde:** de parent-keyed serialisatie + recovery hieronder zijn een
> **voorwaarde** voor fase 5 (anders kunnen twee agents op één branch draaien).
> Trek die stukken naar voren / doe fase 5+6 als één blok.

## Doel

Dispatch, recovery, timeout en cost laten werken **per subtask**, terwijl branch,
workspace en PR **gedeeld** blijven op story-niveau.

## Wijzigingen

- **Serialisatie op parent-key (voorwaarde voor fase 5):** een subtask-dispatch
  checkt `isAnyAgentRunningForStory(parentKey)` — niet de subtask-key — zodat er
  nooit twee agents op dezelfde branch draaien. Docker-labels (`story-key`) op
  een subtask-run gebruiken eveneens de **parent-key**.
- **StoryRun-hergebruik:** een subtask-dispatch keyt z'n `StoryRun` op de
  **parent-story** (`storyRunRepository.openOrCreate(parentKey, ...)`), zodat
  workspace/branch/PR hergebruikt worden. `StoryWorkspaceService.prepare()` cloont
  niet opnieuw (alleen als er nog geen `.git` is) — dat werkt dus vanzelf.
- **`agent_runs.subtask_key`:** nieuwe kolom toevoegen (migratie in
  `resources/db/migration`) zodat runs per subtask traceerbaar zijn. Dit kan ook
  de opslag zijn voor de idempotente subtask-creatie (fase 3).
- **Recovery/timeout/transient-retry** in `recoverActivePhase` werken per
  subtask-run i.p.v. per story (voorwaarde voor fase 5).
- **Subtask-context meegeven aan de agent** (`dispatchRequest` /
  `DockerAgentRuntime`):
  - `Subtask Type`, parent-key, eigen description;
  - **de gerefinede story-tekst van de parent** (de subtask-agent heeft de
    story-context nodig, niet alleen de subtask-beschrijving);
  - relevante commentaren/findings (voor de interne fix-developer).
- **Model/effort per subtask** doorgeven aan de dispatch (uit de subtask-velden,
  fase 0/3).

## Cross-cutting: pauze, budget, credits, fouten, timeout

Deze concerns bestaan al in de huidige factory; ze gelden **vóór** elke
status→actie. Belangrijk in v2 is de **scoping** (story- vs subtask-niveau). Een
issue wordt alleen verwerkt als het niet gepauzeerd, binnen budget, niet
credits-paused en zonder `Error` is.

- **`Paused`-veld → story-niveau.** Budget en pauze horen bij de **parent-story**.
  - Een subtask-dispatch checkt het `Paused`-veld van de **parent** (niet alleen
    z'n eigen) → een gepauzeerde story zet de hele keten stil.
  - Gezet door de mens (`@factory:command:pause`/`kill`) of het systeem bij budget
    100%; opgeheven via `resume`/`BUDGET=N`/`CONTINUE`.
- **Budget (`AI Token Budget`) → story-niveau (cap).** De `CostMonitorPoller` en de
  dispatch vergelijken **som van `Tokens Used`** (story + alle subtaken, via
  `StoryRun`) met het budget. 75/90% → comment; **100%** → `Paused = true` op de
  story. `Tokens Used` wordt per issue bijgehouden; het totaal = som.
- **Credits-exhausted → systeembreed.** Een agent-outcome `credits-exhausted` zet
  een systeembrede pauze tot een tijdstip; zolang die loopt worden álle issues
  (stories én subtaken) overgeslagen. Ongewijzigd t.o.v. nu.
- **`Error`-veld → per issue.** Permanente fouten (config, hard timeout, retry-cap,
  agent-fout, git-sync) zetten `Error` op het **betreffende issue**. Een subtask
  met `Error` **stalt de keten** (fase 4 tagt de volgende pas na `clear-error` of
  herstel). De story-gates blokkeren analoog bij een `Error` op de story.
- **Timeout & transient-retry → per subtask-run.** `AgentStartedAt` (per issue) +
  `hardTimeout` → permanente `Error`. Transient fouten (rate-limit, http 429/500,
  container zonder result-file, timeout) → reset naar de vorige status + `Error`
  legen + opnieuw, tot `maxTransientRetries`. `recoverActivePhase` werkt nu per
  subtask-run (keyt op `agent_runs.subtask_key` + parent-key voor de container-check).

## Aandachtspunten

- Concurrency: blijf serialiseren per story (parent-key) — past op de
  gedeelde-branch-eis.
- Bestaande caps (`maxParallel*`) blijven op factory-/story-niveau gelden.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/RunRepositories.kt`
- `.../orchestrator/repositories/RunRepositories.kt`
- `softwarefactory/src/main/resources/db/migration/` (nieuwe migratie)
- `.../runtime/workspaces/StoryWorkspaceService.kt` (controle: hergebruik)
- `.../runtime/docker/DockerAgentRuntime.kt` (subtask-context env, parent-key label)
- `.../orchestrator/services/OrchestratorService.kt` (recovery + serialisatie per subtask)

## Test

- Twee subtaken van één story delen dezelfde branch/PR.
- Serialisatie: een tweede subtask-dispatch wacht terwijl er een agent voor de
  story draait (parent-key guard).
- Recovery van een vastgelopen subtask-agent werkt (timeout/transient-retry).
- Budget telt op over subtaken heen (som van tokens) en pauzeert op story-niveau.
- Een gepauzeerde story (`Paused=true`) slaat ook subtask-dispatch over.
- Een `Error` op een subtask stalt de keten tot `clear-error`; story gaat niet
  verder met de volgende subtask.
- De subtask-agent ontvangt de parent story-tekst als context.

## Klaar wanneer

Subtaken draaien met eigen run-administratie, recovery en parent-key-serialisatie,
maar delen branch, workspace, PR en budget op story-niveau.
