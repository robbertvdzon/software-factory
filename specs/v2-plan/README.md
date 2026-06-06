# Software Factory v2 — Story/Subtask herontwerp

> Dit is het **overzichtsdocument** van het v2-herontwerp. Het beschrijft hoe de
> flow uiteindelijk moet werken. De implementatie is opgeknipt in fases; elke
> fase heeft een eigen bestand in deze map (`fase-0-*.md` t/m `fase-7-*.md`) dat
> naar dit document terugverwijst. Lees dit eerst, refine het, en pak daarna de
> fases één voor één op.

## 1. Waarom

De huidige factory (zie `../specs.md`) behandelt een **story als atomaire
eenheid**: één story doorloopt één lineaire state machine
`refining → developing ⇄ reviewing ⇄ testing → summarizing`. Er is geen
subtaak-concept.

We willen naar een model waarin:

1. de **story apart** kan worden verwerkt — eerst refinen, dán een plan met
   subtaken maken — met een **inspectiepunt** vóór er code geschreven wordt;
2. elke **subtaak apart** wordt verwerkt, met een eigen type
   (development/review/test/manual) en een eigen kleine pipeline.

## 2. Doelarchitectuur op hoofdlijnen

Eén poller → een **dunne router** op issue-type → drie **coördinatoren** die de
bestaande gedeelde services (dispatch, workspace, docker, cost, recovery)
hergebruiken. Géén drie losse `@Scheduled`-loops: dan vechten ze om dezelfde
poll, dezelfde concurrency-caps en dezelfde docker-runtime.

```
OrchestratorService (dunne router)
 ├─ issue.type == STORY
 │     StoryPhase REFINING..PLANNED   -> StoryRefinementCoordinator   (refine + plan)
 │     StoryPhase DEVELOPING          -> StoryDevelopmentCoordinator  (subtaken aanmaken + sequencen)
 │     StoryPhase SUMMARIZING         -> bestaande summarizer
 └─ issue.type == SUBTASK
       SubtaskExecutionCoordinator    (per-type pipeline op de gedeelde branch)
```

Gedeelde services blijven ongewijzigd van verantwoordelijkheid: dispatch,
`StoryWorkspaceApi`, `DockerAgentRuntime`, cost/budget, recovery/timeout.

## 3. Twee-laags state machine

### Story-niveau (`StoryPhase`)

```
refining
  ⇄ refined-with-questions  ⇄ questions-answered      (bestaande user-vragen-loop)
  → refined
  → planning
  → planned        ──(GATE: mens zet de story op 'developing')──┐
                                                                ▼
  → developing     (= subtaken draaien, sequentieel)
  → summarizing
  → done
```

De **gate** tussen `planned` en `developing` is doel #1: de story wordt los
gerefined en gepland, je inspecteert het plan + de aangemaakte subtaken, en pas
dan start development (handmatig veld of `@factory:command:develop`).

### Subtask-niveau (`SubtaskPhase`), per type

```
development : developing → developed → done        (+ manual feedback / user-vragen-loop)
review      : reviewing  → review-finished | review-with-findings
test        : testing    → tested-ok        | tested-with-findings
manual      : awaiting-human → done                (geen agent; mens flipt 'm)
```

## 4. Belangrijke ontwerpbeslissingen (vastgelegd)

| # | Beslissing | Reden |
|---|-----------|-------|
| 1 | **Refiner en planner zijn twee aparte `AgentRole`s** (`PLANNER` toevoegen). | Verschillende taak en output: refiner verbetert story-tekst + stelt user-vragen; planner produceert de subtask-breakdown. Scherpere prompts. |
| 2 | **Gate tussen `planned` en `developing`.** | Story los kunnen refinen/plannen en het plan inspecteren vóór development (doel #1). |
| 3 | **Review en test zijn ALTIJD losse subtaken.** Dev-subtask = puur ontwikkelen. | Kleine, uniforme state machines per type; geen dubbeling tussen "reviewer in dev-loop" en "losse review-subtask". |
| 4 | **Alle subtaken werken op één gedeelde story-branch/PR.** | Subtaken zijn stappen binnen één feature. Dwingt sequentiële verwerking af (twee agents op één branch kan niet) — `isAnyAgentRunningForStory` dekt dat al. |
| 5 | **De planner maakt subtaken NIET zelf in YouTrack aan.** Hij *declareert* ze; de orchestrator *materialiseert* ze. | Agents zijn afgeschermd van YouTrack: ze schrijven alleen `agent-result.json`, de orchestrator schrijft naar YouTrack. Houdt de Docker-veiligheidsgrens intact (geen creds in de container) en idempotentie aan orchestrator-kant. |
| 6 | **Findings-loopback via een nieuwe dev-subtask.** Een review/test die problemen vindt, declareert findings → orchestrator spawnt een nieuwe `development`-subtask. | Uniform met het planner-mechanisme (beslissing 5), volledig traceerbaar. Met een cap (analoog aan `maxDeveloperLoopbacks`) tegen eindeloos re-reviewen. |

## 5. Hoe subtaken worden aangemaakt (datastroom)

```
PLANNER-agent (in Docker)
   └─ schrijft agent-result.json met: subtasks: [{ type, title, description }, ...]
        │
        ▼
AgentRunCompletionService (orchestrator-kant)
   └─ youTrackApi.createSubtask(parentKey, type, title, description)   (idempotent: alleen als nog geen subtaken)
        │
        ▼
YouTrack: sub-issues met `Subtask Type`, parent-link en de WORK_TAG
        │
        ▼
poller pikt subtaken op (zelfde tag), router → SubtaskExecutionCoordinator
```

Hetzelfde kanaal wordt hergebruikt voor de **findings-loopback**: reviewer/tester
declareert findings in `agent-result.json`, de orchestrator maakt er een nieuwe
`development`-subtask van.

## 6. Triggering & werkdetectie

Werk wordt al **door een tag** getriggerd (`WORK_TAG` in
`YouTrackClient.findWorkIssues()`); de lifecycle draait op het `AI Phase`-veld.
Subtaken krijgen dezelfde tag mee en worden zo automatisch opgepikt. Een
subtask onderscheidt zich van een story doordat hij een parent-link en/of een
`Subtask Type`-veld heeft → daaruit leidt de router het `IssueType` af.

## 7. Gedeelde branch / workspace

`StoryWorkspaceService.prepare()` cloont alleen als er nog geen `.git` is. Een
subtask-dispatch keyt z'n `StoryRun` op de **parent-story**, zodat workspace,
branch en PR vanzelf worden hergebruikt. Cost/budget blijft aggregeren op
story-niveau.

## 8. Implementatievolgorde (fases)

| Fase | Bestand | Kern | Levert |
|------|---------|------|--------|
| 0 | `fase-0-youtrack-modellering.md` | `Subtask Type`-veld, parent-link, `createSubtask`, WORK_TAG op subtaken | Fundament |
| 1 | `fase-1-enum-split-router.md` | `AiPhase` → `StoryPhase`/`SubtaskPhase`, router (gedrag gelijk) | Structuur |
| 2 | `fase-2-refinement-loskoppelen.md` | `PLANNER`-rol + gate | **Doel #1** |
| 3 | `fase-3-subtask-creatie.md` | planner declareert → orchestrator materialiseert | Subtaken bestaan |
| 4 | `fase-4-story-development-coordinator.md` | subtaken sequencen + summarize | Story-coördinatie |
| 5 | `fase-5-subtask-execution-coordinator.md` | per-type pipelines + findings-loopback | Subtask-uitvoering |
| 6 | `fase-6-shared-machinery.md` | StoryRun-hergebruik, `agent_runs.subtask_key`, recovery/cost | Subtask-bewuste machinerie |
| 7 | `fase-7-opruimen.md` | oude pad weg, PR-comment-route | Afronding |

Fase 1–2 zijn klein en geven al direct doel #1. Fase 3–6 zijn het echte
subtask-werk, elk afzonderlijk testbaar. Fase 7 is opruimen.

## 9. Betrokken code (referentie)

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/AiPhase.kt`
- `.../orchestrator/services/OrchestratorService.kt`
- `.../youtrack/TrackerModels.kt`, `.../youtrack/YouTrackApi.kt`, `.../youtrack/clients/YouTrackClient.kt`
- `.../runtime/RuntimeApi.kt`, `.../runtime/services/AgentRunCompletionService.kt`
- `.../orchestrator/RunRepositories.kt` + `resources/db/migration`
- `.../runtime/workspaces/StoryWorkspaceService.kt`
- `.../orchestrator/services/AiRouting.kt`, `.../runtime/docker/DockerAgentRuntime.kt`

## 10. Openstaand (niet blokkerend)

- PR-comment feedback-route (fase 7): late `@factory` PR-comments → nieuwe
  development-subtask i.p.v. story-phase terugzetten.
- Of elke dev-subtask z'n eigen review/test krijgt, of de planner één review/test
  aan het eind plant — bewust aan de planner overgelaten.
