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

## Cost / budget / tokens

- **AI Tokens Used**: per issue bijhouden (story én elke subtask). Het
  story-totaal = som van story + alle subtaken.
- **Budget** (`AI Token Budget`, cap): blijft op **story-niveau**; pauzeert op
  story-niveau wanneer overschreden. `StoryRun` aggregeert de kosten al.

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
- De subtask-agent ontvangt de parent story-tekst als context.

## Klaar wanneer

Subtaken draaien met eigen run-administratie, recovery en parent-key-serialisatie,
maar delen branch, workspace, PR en budget op story-niveau.
