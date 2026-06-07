# Software Factory v2 — Story/Subtask herontwerp

> Dit is het **overzichtsdocument** van het v2-herontwerp. Het beschrijft hoe de
> flow uiteindelijk moet werken. De implementatie is opgeknipt in fases; elke
> fase heeft een eigen bestand in deze map (`fase-0-*.md` t/m `fase-7-*.md`) dat
> naar dit document terugverwijst. Lees dit eerst, refine het, en pak daarna de
> fases één voor één op.
>
> **Status:** dit document is bijgewerkt na de brainstorm-ronde (juni 2026) waarin
> we het model tegen de echte YouTrack-instance hebben gevalideerd. Concrete
> veld- en fase-namen zijn voorstellen; hernoemen kan, de structuur staat.

## 1. Waarom

De huidige factory (zie `../specs.md`) behandelt een **story als atomaire
eenheid**: één story doorloopt één lineaire state machine
`refining → developing ⇄ reviewing ⇄ testing → summarizing`. Er is geen
subtaak-concept.

We willen naar een model waarin:

1. de **story apart** kan worden verwerkt — eerst refinen, dán een plan met
   subtaken maken — met een **inspectie-gate** vóór er code geschreven wordt;
2. elke **subtaak apart** wordt verwerkt, met een eigen type
   (development/review/test/manual) en een eigen kleine pipeline die op de
   gedeelde story-branch draait.

## 2. Doelarchitectuur op hoofdlijnen

Eén poller → een **dunne router** op issue-type → coördinatoren die de bestaande
gedeelde services (dispatch, workspace, docker, cost, recovery) hergebruiken.
Géén losse `@Scheduled`-loops per niveau: dan vechten ze om dezelfde poll,
dezelfde concurrency-caps en dezelfde docker-runtime.

```
OrchestratorService (dunne router op IssueType)
 ├─ issue is STORY  (Subtask Type leeg)
 │     StoryPhase REFINING..PLANNED   -> StoryRefinementCoordinator   (refine + plan)
 │     StoryPhase DEVELOPING          -> StoryDevelopmentCoordinator  (subtaken sequencen)
 │     StoryPhase SUMMARIZING         -> bestaande summarizer
 └─ issue is SUBTASK (Subtask Type gezet)
       SubtaskExecutionCoordinator    (één uniforme per-type pipeline op de gedeelde branch)
```

Gedeelde services blijven ongewijzigd van verantwoordelijkheid: dispatch,
`StoryWorkspaceApi`, `DockerAgentRuntime`, cost/budget, recovery/timeout.

## 3. Twee-laags state machine

We gebruiken **twee aparte phase-velden** (zie beslissing 1), zodat een mens bij
het (handmatig) bewerken van een issue alleen de relevante fases ziet en er geen
naam-collisions tussen story- en subtask-niveau ontstaan.

### Story-niveau (`Story Phase`-veld)

```
refining
  ⇄ refined-with-questions  ⇄ questions-answered      (bestaande user-vragen-loop)
  → refined            (refiner klaar; mens kan story-tekst handmatig bijstellen)
  → planning
  → planned            ──(GATE: label ai-refined; mens zet label op ai-development)──┐
                                                                                     ▼
  → developing         (= subtaken draaien, sequentieel)
  → summarizing
  → done
```

De **gate** is doel #1: de story wordt los gerefined en gepland, je inspecteert
het plan + de aangemaakte subtaken (en past ze desgewenst handmatig aan), en pas
dán start development. Het gate-signaal loopt via een **label** (zie sectie 6),
niet via een vrij veld.

### Subtask-niveau (`Subtask Phase`-veld), één uniforme machine

Alle AI-subtaken delen dezelfde vorm: **[primaire rol] → [verify-loop met een
developer tot ok] → done**. Alleen de startrol verschilt. De loopback is
**intern aan de subtask** (geen nieuwe subtask, zie beslissing 6).

```
development : developing → developed → reviewing → reviewed-ok → done
              (review-with-findings → developing → reviewing ...   = interne fix-loop)

review      : reviewing  → reviewed-ok → done                      (story-brede review)
              (review-with-findings → developing → reviewing ...)

test        : testing    → tested-ok  → done                       (story-brede test)
              (tested-with-findings → developing → testing ...)

manual      : awaiting-human → done    (geen agent; mens flipt 'm)
```

- `*-with-findings` is **geen eindfase**: het routeert naar een interne
  developer-stap en daarna terug naar de primaire rol, tot `*-ok`.
- Na elke AI-stap zit een **manual-verify checkpoint**: de mens kan het
  AI-oordeel controleren/overrulen vóór er wordt doorgegaan (zie beslissing 7).
- Elke subtask-agent kan ook vragen stellen aan de gebruiker (subtask-niveau
  user-vragen-loop, analoog aan de refiner).
- Een **cap** (`AI Max Developer Loopbacks`, bestaand veld) begrenst het aantal
  interne fix-rondes.

### Twee review-niveaus (belangrijk)

1. **Per-subtask review** — ingebouwd in elke `development`-subtask (de
   `reviewing`-stap hierboven). Reviewt alleen díe subtask.
2. **Story-brede review/test** — losse `review`- en `test`-subtaken die de
   planner aan het eind van de lijst plant en die de hele story dekken.

## 4. Belangrijke ontwerpbeslissingen (vastgelegd)

| # | Beslissing | Reden |
|---|-----------|-------|
| 1 | **Twee phase-velden**: `Story Phase` op stories, `Subtask Phase` op subtaken (geen gedeeld `AI Phase`-veld). | Bij handmatig aanmaken/bewerken toont de dropdown alleen relevante waarden; geen naam-collisions (beide kennen `developing`); menselijk leesbaar. De router takt tóch al af op IssueType. |
| 2 | **IssueType = SUBTASK desda het `Subtask Type`-veld gezet is** (development/review/test/manual); anders STORY. | Robuuste discriminator die wij zelf zetten. De Subtask-link is onbetrouwbaar als discriminator (een story onder een epic heeft óók zo'n link); die gebruiken we alleen om de parent-story terug te vinden. |
| 3 | **Twee review-niveaus** (zie §3): ingebouwde review per dev-subtask + losse story-brede review/test. Dev-subtask is dus *niet* puur ontwikkelen. | Elke subtask wordt direct gevalideerd, én er is een eindcontrole over de hele story. Vervangt de oude "review = altijd losse subtask"-formulering. |
| 4 | **Alle subtaken werken op één gedeelde story-branch/PR.** | Subtaken zijn stappen binnen één feature. Dwingt sequentiële verwerking af (twee agents op één branch kan niet) — `isAnyAgentRunningForStory` (op parent-key) dekt dat. |
| 5 | **De planner maakt subtaken NIET zelf in YouTrack aan.** Hij *declareert* ze; de orchestrator *materialiseert* ze. | Agents zijn afgeschermd van YouTrack: ze schrijven alleen `agent-result.json`, de orchestrator schrijft naar YouTrack. Houdt de Docker-veiligheidsgrens intact (geen creds in de container) en idempotentie aan orchestrator-kant. |
| 6 | **Findings-loopback is INTERN aan de subtask.** Een review/test die problemen vindt routeert naar een interne developer-stap en daarna terug naar de primaire rol, tot ok. | Self-contained subtask, schoon board, geen proliferatie van fix-subtaken. (Vervangt de eerdere "nieuwe dev-subtask per finding".) Cross-subtask re-review is bewust geparkeerd (§10). |
| 7 | **Manual-verify checkpoints na elke AI-stap.** | De mens kan elk AI-oordeel (developed/reviewed-ok/tested-ok) controleren en overrulen vóór doorgaan. |
| 8 | **AI Level vervalt; vervangen door `AI Model` + `AI Reasoning Effort`.** | Level was een lekke cross-supplier abstractie. De dispatch droeg model+effort al expliciet mee; de subtask-split maakt per-rol-tiering overbodig (elke subtask ís één rol). Planner zet model/effort per subtask. |

## 5. Hoe subtaken worden aangemaakt (datastroom)

```
PLANNER-agent (in Docker)
   └─ schrijft agent-result.json met:
        subtasks: [{ type, title, description, model?, effort? }, ...]
        (incl. de story-brede review/test als laatste items)
        │
        ▼
AgentRunCompletionService (orchestrator-kant)
   └─ per spec: youTrackApi.createSubtask(parentKey, spec)
        - POST /api/issues (project, summary=title, description)
        - customFields: Subtask Type, Subtask Phase (begin), AI Model, Reasoning Effort
        - YouTrack Type = Task   (zodat het een kaart wordt, geen swimlane)
        - WORK_TAG zetten
        - link: command "parent for <subtaskKey>" op de parent
        - idempotent: sla aangemaakte subtask-keys op; rerun maakt geen duplicaten
        │
        ▼
YouTrack: sub-issues met Subtask Type, parent-link en WORK_TAG
        │
        ▼
poller pikt subtaken op (zelfde tag), router → SubtaskExecutionCoordinator
```

Het `createSubtask`-pad is tegen de echte YouTrack-instance gevalideerd
(POST-issue + customFields + tag + commands-link).

## 6. Triggering, lifecycle-labels & gate

Werk wordt door **labels** getriggerd; het fijnmazige verloop staat in de
phase-velden. De story-lifecycle:

- **`ai-refinement`** — story: start refinement (refiner + planner).
- **`ai-refined`** — story: refinement + plan klaar = **GATE**. De orchestrator
  doet niets; de mens inspecteert plan + subtaken en past desgewenst handmatig
  aan.
- **`ai-development`** — story: development actief. De
  `StoryDevelopmentCoordinator` zet hetzelfde label **per subtask** zodra die aan
  de beurt is; de `SubtaskExecutionCoordinator` pikt die op.

Een issue onderscheidt zich als STORY of SUBTASK via het **`Subtask Type`-veld**
(beslissing 2). `findWorkIssues()` levert na fase 0 zowel stories als subtaken op
(zelfde WORK_TAG); de router splitst.

## 7. Gedeelde branch / workspace / budget

`StoryWorkspaceService.prepare()` cloont alleen als er nog geen `.git` is. Een
subtask-dispatch keyt z'n `StoryRun` op de **parent-story**, zodat workspace,
branch en PR vanzelf worden hergebruikt.

- **Tokens Used**: per issue (story én elke subtask een eigen waarde); het
  story-totaal = som van story + alle subtaken.
- **Budget** (cap): alleen op story-niveau.
- **AI-supplier**: story-niveau als default, per subtask overschrijfbaar.
- **AI Model / Reasoning Effort**: per issue; planner zet per subtask.

## 8. Velden-overzicht (story vs subtask)

| Veld | Story | Subtask |
|------|-------|---------|
| Story Phase / Subtask Phase | `Story Phase` | `Subtask Phase` |
| Subtask Type | leeg | development / review / test / manual |
| YouTrack Type | User Story (swimlane) | Task (kaart) |
| AI-supplier | ✓ (default) | overschrijfbaar |
| AI Model + AI Reasoning Effort | ✓ | ✓ (planner zet) |
| AI Token Budget | ✓ (cap) | — |
| AI Tokens Used | ✓ | ✓ (story-totaal = som) |
| AI Max Developer Loopbacks | (evt.) | ✓ (cap interne fix-loop) |
| Priority, Paused, Error, AgentStartedAt | ✓ | ✓ |

## 9. Implementatievolgorde (fases)

| Fase | Bestand | Kern | Levert |
|------|---------|------|--------|
| 0 | `fase-0-youtrack-modellering.md` | velden (2 phase-velden, Subtask Type, Model/Effort, AI Level weg), Type=User Story/Task, `createSubtask`, lifecycle-labels | Fundament |
| 1 | `fase-1-enum-split-router.md` | `AiPhase` → `StoryPhase`/`SubtaskPhase`, router op IssueType (gedrag gelijk) | Structuur |
| 2 | `fase-2-refinement-loskoppelen.md` | `PLANNER`-rol + label-gate + manual-adjust checkpoints | **Doel #1** |
| 3 | `fase-3-subtask-creatie.md` | planner declareert (incl. story-brede review/test) → orchestrator materialiseert, idempotent | Subtaken bestaan |
| 4 | `fase-4-story-development-coordinator.md` | subtaken sequencen via label + summarize | Story-coördinatie |
| 5 | `fase-5-subtask-execution-coordinator.md` | één uniforme per-type pipeline + interne loopback + manual-verify | Subtask-uitvoering |
| 6 | `fase-6-shared-machinery.md` | parent-keyed serialisatie/recovery (vóór fase 5 live), subtask-context, tokens/budget | Subtask-bewuste machinerie |
| 7 | `fase-7-opruimen.md` | oude pad weg, PR-comment-route | Afronding |

Fase 1–2 zijn klein en geven al direct doel #1. **Let op de volgorde-koppeling
tussen fase 5 en 6**: de parent-keyed serialisatie- en recovery-stukken uit fase
6 moeten live zijn vóórdat fase 5 echte subtask-agents laat draaien, anders kun
je twee agents op één branch krijgen. Behandel die stukken als voorwaarde voor
fase 5 (of fase 5+6 als één blok).

## 10. Betrokken code (referentie)

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/AiPhase.kt`
- `.../orchestrator/services/OrchestratorService.kt`
- `.../orchestrator/services/AiRouting.kt`
- `.../youtrack/TrackerModels.kt`, `.../youtrack/YouTrackApi.kt`, `.../youtrack/clients/YouTrackClient.kt`
- `.../runtime/RuntimeApi.kt`, `.../runtime/services/AgentRunCompletionService.kt`
- `.../orchestrator/RunRepositories.kt` + `resources/db/migration`
- `.../runtime/workspaces/StoryWorkspaceService.kt`
- `.../runtime/docker/DockerAgentRuntime.kt`

## 11. Openstaand (bewust geparkeerd, niet blokkerend)

- **Cross-subtask re-review**: als de interne fix in een test-subtask veel
  verandert, zou een eerdere (story-brede) review opnieuw moeten draaien. Plan:
  de **agent declareert** "significante wijziging, re-review aanbevolen" in
  `agent-result.json`; de orchestrator heropent/append dan die review-subtask. De
  orchestrator raadt dit niet zelf. Later toevoegen.
- **PR-comment feedback-route** (fase 7): late `@factory` PR-comments → nieuwe
  development-subtask i.p.v. story-phase terugzetten.
- **Expliciete ordering van subtaken** buiten de aanmaakvolgorde van de planner.
