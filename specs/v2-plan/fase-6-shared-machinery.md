# Fase 6 — Gedeelde machinerie subtask-bewust maken

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

Dispatch, recovery, timeout en cost laten werken **per subtask**, terwijl branch,
workspace en PR **gedeeld** blijven op story-niveau.

## Wijzigingen

- **StoryRun-hergebruik:** een subtask-dispatch keyt z'n `StoryRun` op de
  **parent-story** (`storyRunRepository.openOrCreate(parentKey, ...)`), zodat
  workspace/branch/PR hergebruikt worden. `StoryWorkspaceService.prepare()` cloont
  niet opnieuw (alleen als er nog geen `.git` is) — dat werkt dus vanzelf.
- **`agent_runs.subtask_key`:** nieuwe kolom toevoegen (migratie in
  `resources/db/migration`) zodat runs per subtask traceerbaar zijn.
- **Recovery/timeout/transient-retry** in `recoverActivePhase` werken per
  subtask-run i.p.v. per story.
- **Cost/budget** blijft aggregeren op **story-niveau** (`StoryRun`).
- **Subtask-context** meegeven aan de agent: type, parent-key en de eigen
  description als env in `dispatchRequest` / `DockerAgentRuntime`.

## Aandachtspunten

- Concurrency: blijf serialiseren per story (`isAnyAgentRunningForStory`) — past op
  de gedeelde-branch-eis.
- Let op de bestaande caps (`maxParallel*`) — die blijven op factory-/story-niveau
  gelden.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/RunRepositories.kt`
- `.../orchestrator/repositories/RunRepositories.kt`
- `softwarefactory/src/main/resources/db/migration/` (nieuwe migratie)
- `.../runtime/workspaces/StoryWorkspaceService.kt` (controle: hergebruik)
- `.../runtime/docker/DockerAgentRuntime.kt` (subtask-context env)
- `.../orchestrator/services/OrchestratorService.kt` (recovery per subtask)

## Test

- Twee subtaken van één story delen dezelfde branch/PR.
- Recovery van een vastgelopen subtask-agent werkt (timeout/transient-retry).
- Budget telt op over subtaken heen en pauzeert op story-niveau.

## Klaar wanneer

Subtaken draaien met eigen run-administratie en recovery, maar delen branch,
workspace, PR en budget op story-niveau.
