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
   subtaken maken — met een **goedkeuringsstap** vóór er code geschreven wordt;
2. elke **subtaak apart** wordt verwerkt, met een eigen type
   (development/review/test/manual) en een eigen kleine pipeline die op de
   gedeelde story-branch draait.

## 2. Doelarchitectuur op hoofdlijnen

Eén poller → een **dunne router** op het `Type`-veld → coördinatoren die de
bestaande gedeelde services (dispatch, workspace, docker, cost, recovery)
hergebruiken. Géén losse `@Scheduled`-loops per niveau: dan vechten ze om dezelfde
poll, dezelfde concurrency-caps en dezelfde docker-runtime.

```
poller (findWorkIssues) = issues met tag 'ai-refinement' (stories) OF 'ai-development' (subtaken)

OrchestratorService (router op het Type-veld)
 ├─ Type == User Story  (STORY — heeft dus tag 'ai-refinement')
 │     Story Phase != PLANNING_APPROVED  -> StoryRefinementCoordinator   (refiner/planner + approve/reject)
 │     Story Phase == PLANNING_APPROVED  -> niets (refinement klaar; orchestrator laat de story los)
 └─ Type == Task  (SUBTASK — heeft dus tag 'ai-development')
       -> SubtaskExecutionCoordinator  (uniforme per-type pipeline)
       (volgorde = keten: mens tagt de 1e subtask; completion-handler tagt na elke DONE de volgende)
```

Een story zónder de tag `ai-refinement` wordt dus **niet** opgepakt; een subtask
zónder `ai-development` evenmin.

**Op story-niveau draait alleen agent-werk tijdens het refine-proces** (refiner +
planner — twee aparte stappen, elk met een eigen goedkeuringsstap). De story
wordt **nooit** voor development gepolld; alleen **subtaken** (Type `Task`) dragen
de tag `ai-development`. De volgorde is een **keten** (Optie A): de mens tagt de
eerste subtask, en de completion-handler tagt telkens de volgende sibling zodra de
huidige `DONE` is. De **summary is een aparte subtask** (type `summary`, bestaande
`SUMMARIZER`-rol) die als laatste draait — er is dus geen `SUMMARIZING`-fase op
story-niveau, en ook geen `DEVELOPING`/`DONE` story-phase (de story-phase modelt
puur de refinement-lifecycle, t/m `PLANNING_APPROVED`).

Gedeelde services blijven ongewijzigd van verantwoordelijkheid: dispatch,
`StoryWorkspaceApi`, `DockerAgentRuntime`, cost/budget, recovery/timeout.

## 3. Twee-laags state machine

We gebruiken **twee aparte phase-velden** (zie beslissing 1), zodat een mens bij
het (handmatig) bewerken van een issue alleen de relevante fases ziet en er geen
naam-collisions tussen story- en subtask-niveau ontstaan.

### Story-niveau (`Story Phase`-veld) — puur de refinement-lifecycle

Twee soorten mens-interactie, bewust gescheiden:
- **vragen-loop** = *AI vraagt*, mens antwoordt → AI draait opnieuw;
- **goedkeuringsstap** = *AI is klaar*, mens beoordeelt → goed/afgekeurd.

Per status, wat de orchestrator doet (**voorwaarde: de story heeft de tag
`ai-refinement`**; anders pikt de poller 'm niet op):

| Status | Wat de orchestrator doet |
|---|---|
| _(geen status)_ | start de refine-agent, zet status op `refining` |
| `refining` | niets; refine-agent draait (liveness/timeout bewaken). Bij klaar zet de completion-handler `refined-with-questions` (mét vragen) of `refined` (zónder) |
| `refined-with-questions` | niets; wacht tot de mens antwoordt (comment) en zelf `questions-answered` zet |
| `questions-answered` | start de refine-agent (met de antwoorden), zet status op `refining` |
| `refined` | niets; wacht tot de mens `refined-rejected` of `refined-approved` zet |
| `refined-rejected` | start de refine-agent (met mens-feedback uit comments/description), zet status op `refining` |
| `refined-approved` | start de planning-agent, zet status op `planning` |
| `planning` | niets; planning-agent draait. Bij klaar: `planned-with-questions` (mét vragen), óf de completion-handler **materialiseert de subtaken** (createSubtask) en zet `planned` (zónder) |
| `planned-with-questions` | niets; wacht tot de mens antwoordt en `planning-questions-answered` zet |
| `planning-questions-answered` | start de planning-agent (met de antwoorden), zet status op `planning` |
| `planned` | niets; wacht tot de mens `planning-rejected` of `planning-approved` zet |
| `planning-rejected` | start de planning-agent (met mens-feedback), zet status op `planning`; planner **reconcilieert** bestaande subtaken |
| `planning-approved` | niets meer; refinement klaar, orchestrator laat de story los. Development start wanneer de mens een **subtask** `ai-development` tagt |

De story-phase eindigt bij `planning-approved`; **geen `developing`/`done`/`summarizing`
story-phase**. Het enige echte agent-werk op story-niveau is **refine** en **plan**
(twee aparte stappen, elk met een goedkeuringsstap).

De **goedkeuring** is doel #1: de story wordt los gerefined en gepland, je keurt eerst de
refinement goed (of stuurt 'm terug), inspecteert daarna het plan + de aangemaakte
subtaken, keurt het plan goed (of stuurt 'm terug), en start development pas
wanneer jij zelf de tag `ai-development` zet (Optie B — approval en
development-start zijn losgekoppeld).

### Subtask-niveau (`Subtask Phase`-veld) — ketting van AI-stappen + goedkeuringen

Een subtask is een **ketting van AI-stappen**. Elke AI-stap (rol R, naam N) volgt
hetzelfde patroon als de story-refiner/-planner — een **vragen-loop** plus een
**goedkeuringsstap** (mens, of later auto-approve):

```
N-ing → (N-ed-with-questions ⇄ N-questions-answered) → N-ed → [goedkeuring] N-approved | N-rejected
```

- De overgang uit `N-ing` zet de **completion-handler**; `N-approved`/`N-rejected`
  zet de **mens** (of auto-approve). De **reviewer/tester mag `*-rejected` ook
  zélf zetten** (findings → direct terug naar de developer). `*-rejected` start een
  developer-fix; `*-approved` van de laatste stap = subtask klaar.
- **Voorwaarde:** de subtask heeft de tag `ai-development` (poll-filter).

**development** = developer-stap → reviewer-stap (volledige tabel):

| Subtask Phase | Wat de orchestrator doet | Wie zet de volgende |
|---|---|---|
| _(net getagd)_ | start developer → `developing` | O |
| `developing` | developer draait → `developed-with-questions` of `developed` | completion |
| `developed-with-questions` | wacht | mens → `development-questions-answered` |
| `development-questions-answered` | start developer (antw) → `developing` | O |
| `developed` | wacht op goedkeuring | mens → `development-approved`/`-rejected` |
| `development-rejected` | start developer (feedback) → `developing` | O |
| `development-approved` | start reviewer → `reviewing` | O |
| `reviewing` | reviewer draait → `reviewed-with-questions`, `reviewed`, of `review-rejected` | completion |
| `reviewed-with-questions` | wacht | mens → `review-questions-answered` |
| `review-questions-answered` | start reviewer (antw) → `reviewing` | O |
| `reviewed` | wacht op goedkeuring | mens → `review-approved`/`-rejected` |
| `review-rejected` | start developer (verwerk findings) → `developing` | reviewer of mens |
| `review-approved` | **subtask klaar** → fase 4 tagt de volgende | mens |

**review** (story-breed) = reviewer-stap; bij `review-rejected` een **simpele**
fix-developer (géén eigen goedkeuring) → terug naar re-review:

| Subtask Phase | Wat de orchestrator doet | Wie zet de volgende |
|---|---|---|
| _(net getagd)_ | start reviewer → `reviewing` | O |
| `reviewing` | reviewer draait → `reviewed-with-questions`, `reviewed`, of `review-rejected` | completion |
| `reviewed-with-questions` | wacht | mens → `review-questions-answered` |
| `review-questions-answered` | start reviewer (antw) → `reviewing` | O |
| `reviewed` | wacht op goedkeuring | mens → `review-approved`/`-rejected` |
| `review-rejected` | start developer (fix) → `developing` | reviewer of mens |
| `developing` | developer draait → `developed-with-questions` of `developed` | completion |
| `developed-with-questions` | wacht | mens → `development-questions-answered` |
| `development-questions-answered` | start developer (antw) → `developing` | O |
| `developed` | **geen aparte goedkeuring** → start reviewer (re-review) → `reviewing` | O |
| `review-approved` | **subtask klaar** | mens |

**test** (story-breed) = tester-stap; idem met simpele fix-developer:

| Subtask Phase | Wat de orchestrator doet | Wie zet de volgende |
|---|---|---|
| _(net getagd)_ | start tester → `testing` | O |
| `testing` | tester draait → `tested-with-questions`, `tested`, of `test-rejected` | completion |
| `tested-with-questions` | wacht | mens → `test-questions-answered` |
| `test-questions-answered` | start tester (antw) → `testing` | O |
| `tested` | wacht op goedkeuring | mens → `test-approved`/`-rejected` |
| `test-rejected` | start developer (fix) → `developing` | tester of mens |
| `developing` | developer draait → `developed-with-questions` of `developed` | completion |
| `developed-with-questions` | wacht | mens → `development-questions-answered` |
| `development-questions-answered` | start developer (antw) → `developing` | O |
| `developed` | **geen aparte goedkeuring** → start tester (re-test) → `testing` | O |
| `test-approved` | **subtask klaar** | mens |

**manual** (geen agent):

| Subtask Phase | Wat de orchestrator doet | Wie zet de volgende |
|---|---|---|
| _(net getagd)_ | zet `awaiting-human` | O |
| `awaiting-human` | niets; mens doet het werk en zet `manual-action-done` | mens → `manual-action-done` |
| `manual-action-done` | **subtask klaar** → fase 4 tagt de volgende | mens |

**summary** (summarizer-stap mét goedkeuring):

| Subtask Phase | Wat de orchestrator doet | Wie zet de volgende |
|---|---|---|
| _(net getagd)_ | start summarizer → `summarizing` | O |
| `summarizing` | summarizer draait → `summary-with-questions` of `summarized` | completion |
| `summary-with-questions` | wacht | mens → `summary-questions-answered` |
| `summary-questions-answered` | start summarizer (antw) → `summarizing` | O |
| `summarized` | wacht op goedkeuring | mens → `summary-approved`/`-rejected` |
| `summary-rejected` | start summarizer (feedback) → `summarizing` | O |
| `summary-approved` | **subtask klaar** | mens |

Cross-cutting: cap (`AI Max Developer Loopbacks`) op de `*-rejected → developing`-loop;
bij `*-approved` van de laatste stap tagt fase 4 de volgende subtask; een
**auto-approve setting per stap** (toekomst) laat delen autonoom lopen. Alle
status→actie-tabellen gelden alleen als het issue **niet gepauzeerd**, **binnen
budget** en **zonder `Error`** is — zie §7.

### Twee review-niveaus (belangrijk)

1. **Per-subtask review** — ingebouwd in elke `development`-subtask (de
   `reviewing`-stap hierboven). Reviewt alleen díe subtask.
2. **Story-brede review/test** — losse `review`- en `test`-subtaken die de
   planner aan het eind van de lijst plant en die de hele story dekken.

## 4. Belangrijke ontwerpbeslissingen (vastgelegd)

| # | Beslissing | Reden |
|---|-----------|-------|
| 1 | **Twee phase-velden**: `Story Phase` op stories, `Subtask Phase` op subtaken (geen gedeeld `AI Phase`-veld). | Bij handmatig aanmaken/bewerken toont de dropdown alleen relevante waarden; geen naam-collisions (beide kennen `developing`); menselijk leesbaar. De router takt tóch al af op IssueType. |
| 2 | **IssueType via het standaard `Type`-veld**: `User Story` → STORY, `Task` → SUBTASK. Het `Subtask Type`-veld (development/review/test/manual/summary) bepaalt alléén de *rol* van een subtask, niet STORY vs SUBTASK. | YouTrack-standaard, en het is ook waar de board-swimlanes op draaien. Vereist de conventie "stories = User Story, subtaken = Task" in elk gemanaged project (een nieuw, geschikt project; SF voldoet niet en wordt niet gebruikt). De Subtask-link dient alleen om de parent terug te vinden. |
| 3 | **Twee review-niveaus** (zie §3): ingebouwde review per dev-subtask + losse story-brede review/test. Dev-subtask is dus *niet* puur ontwikkelen. | Elke subtask wordt direct gevalideerd, én er is een eindcontrole over de hele story. Vervangt de oude "review = altijd losse subtask"-formulering. |
| 4 | **Alle subtaken werken op één gedeelde story-branch/PR.** | Subtaken zijn stappen binnen één feature. Dwingt sequentiële verwerking af (twee agents op één branch kan niet) — `isAnyAgentRunningForStory` (op parent-key) dekt dat. |
| 5 | **De planner maakt subtaken NIET zelf in YouTrack aan.** Hij *declareert* ze; de orchestrator *materialiseert* ze. | Agents zijn afgeschermd van YouTrack: ze schrijven alleen `agent-result.json`, de orchestrator schrijft naar YouTrack. Houdt de Docker-veiligheidsgrens intact (geen creds in de container) en idempotentie aan orchestrator-kant. |
| 6 | **Findings-loopback is INTERN aan de subtask en goedkeuring-gestuurd.** Een `*-rejected` (gezet door de AI-reviewer/tester óf de mens) start een developer-fix op de gedeelde branch en loopt terug naar de primaire rol, tot `*-approved`. | Self-contained subtask, schoon board, geen proliferatie van fix-subtaken. (Vervangt "nieuwe dev-subtask per finding" én de eerdere automatische loop.) Cross-subtask re-review is bewust geparkeerd (§11). |
| 7 | **Elke AI-stap (story én subtask) heeft een goedkeuringsstap** (`*-approved`/`*-rejected`), naast een vragen-loop. Standaard zet de mens 'm; een geplande **auto-approve setting per stap** kan delen (of alles) autonoom maken. | Bewust maximale controle: niets gaat door zonder akkoord. Flexibel: van volledig hands-on tot volledig autonoom door auto-approve aan te zetten. |
| 8 | **AI Level vervalt; vervangen door `AI Model` + `AI Reasoning Effort`.** | Level was een lekke cross-supplier abstractie. De dispatch droeg model+effort al expliciet mee; de subtask-split maakt per-rol-tiering overbodig (elke subtask ís één rol). Planner zet model/effort per subtask. |
| 9 | **Expliciete goedkeuringsstappen** in de Story Phase ná refine én ná plan; development-start losgekoppeld (mens zet zelf de `ai-development`-tag). | Mens-controle vóór er tokens aan plannen/ontwikkelen gaan; "klaar maar afgekeurd" is een echte status i.p.v. een vage checkpoint. Story-phase modelt puur de refinement-lifecycle; development is tag-gedreven. |

## 5. Hoe subtaken worden aangemaakt (datastroom)

```
PLANNER-agent (in Docker)
   └─ schrijft agent-result.json met:
        subtasks: [{ type, title, description, model?, effort? }, ...]
        (incl. story-brede review/test, en als állerlaatste een 'summary'-subtask)
        │
        ▼
AgentRunCompletionService (orchestrator-kant)
   └─ per spec: youTrackApi.createSubtask(parentKey, spec)
        - POST /api/issues (project, summary=title, description)
        - customFields: Subtask Type, Subtask Phase (begin), AI Model, Reasoning Effort
        - YouTrack Type = Task   (zodat het een kaart wordt, geen swimlane)
        - GEEN tag bij creatie: de subtask is inert tot 'ie 'ai-development' krijgt
        - link: command "parent for <subtaskKey>" op de parent
        - idempotent: sla aangemaakte subtask-keys op; rerun maakt geen duplicaten
        │
        ▼
YouTrack: sub-issues met Subtask Type + parent-link (nog ZONDER ai-development-tag)
        │
        ▼
mens tagt de 1e subtask 'ai-development' → poller pikt 'm op → SubtaskExecutionCoordinator
   → bij DONE tagt de completion-handler de volgende sibling (keten, Optie A)
```

Het `createSubtask`-pad is tegen de echte YouTrack-instance gevalideerd
(POST-issue + customFields + commands-link).

## 6. Triggering, tags & goedkeuring

Werk wordt door **tags** getriggerd; het fijnmazige verloop staat in de
phase-velden. Twee lifecycle-tags, op verschillende niveaus:

- **`ai-refinement`** — op de **story** (Type `User Story`). De mens zet 'm om een
  story bij de factory in te dienen. De `StoryRefinementCoordinator` drijft de
  Story Phase tot `planning-approved`; daarna laat de orchestrator de story los.
- **`ai-development`** — op een **subtask** (Type `Task`), nooit op de story. De
  mens zet 'm na `planning-approved` op de **eerste** subtask om development te
  starten (Optie B). Daarna **ketent** de `OrchestratorPoller`: zodra een subtask
  z'n terminale `*-approved`/`done` bereikt (door de mens of auto-approve gezet),
  tagt de poller de volgende sibling (via de parent-link, in aanmaakvolgorde) en
  haalt de tag van de afgeronde. De `SubtaskExecutionCoordinator` draait telkens de
  getagde subtask; de gedeelde-branch-guard (op parent-key) borgt dat er maar één
  tegelijk loopt.

De **goedkeuring** loopt via de approve/reject-statussen in de Story Phase (niet via
een tag): `refined → refined-approved/-rejected` en
`planned → planning-approved/-rejected`.

Een issue onderscheidt zich als STORY of SUBTASK via het **`Type`-veld**
(beslissing 2). `findWorkIssues()` levert issues met tag `ai-refinement` (stories)
of `ai-development` (subtaken); de router splitst op `Type`.

## 7. Gedeelde branch / workspace / budget + cross-cutting (pauze, fouten, recovery)

`StoryWorkspaceService.prepare()` cloont alleen als er nog geen `.git` is. Een
subtask-dispatch keyt z'n `StoryRun` op de **parent-story**, zodat workspace,
branch en PR vanzelf worden hergebruikt.

- **Tokens Used**: per issue (story én elke subtask een eigen waarde); het
  story-totaal = som van story + alle subtaken.
- **Budget** (cap): alleen op story-niveau.
- **AI-supplier**: story-niveau als default, per subtask overschrijfbaar.
- **AI Model / Reasoning Effort**: per issue; planner zet per subtask.

Deze concerns bestaan al in de huidige factory en blijven gelden; ze zitten **vóór**
elke status→actie uit §3. Een issue wordt alleen verwerkt als het **niet
gepauzeerd**, **binnen budget**, **niet credits-paused** en **zonder `Error`** is.
Details + scoping in [fase 6](./fase-6-shared-machinery.md).

- **Paused-veld:** is `Paused = true` → issue wordt overgeslagen (geen dispatch).
  Gezet door de mens (`@factory:command:pause`/`kill`) of door het systeem bij
  budget 100%. Opgeheven via `resume`/`BUDGET=N`/`CONTINUE`. **Scoping:** budget en
  dus pauze zijn **story-niveau** — een gepauzeerde story zet ook al z'n subtaken
  stil (de subtask-dispatch checkt de parent).
- **Budget:** de `CostMonitorPoller` en de dispatch vergelijken `Tokens Used`
  (som over story + subtaken, via `StoryRun`) met `AI Token Budget` (story). **Geen
  tussentijdse 75/90%-comments**; alleen bij **100%** → `Paused = true` (+ comment
  waarom). `CONTINUE` verhoogt het budget en hervat.
- **Credits-exhausted:** een agent-outcome `credits-exhausted` zet een
  **systeembrede** pauze tot een tijdstip; zolang die loopt worden álle issues
  overgeslagen.
- **`Error`-veld (systeemfout):** gezet bij permanente fouten (config ontbreekt,
  hard timeout, retry-cap, agent-fout, git-sync-fout). Een issue met `Error` wordt
  overgeslagen tot de mens `clear-error` doet (of een gericht transient-herstel).
  **Scoping per issue:** een `Error` op een subtask stalt de keten (§3/fase 4) tot
  het is opgelost.
- **Timeout & recovery:** `AgentStartedAt` (per issue) + `hardTimeout` → permanente
  `Error`. **Transient** fouten (rate-limit, http 429/500, container zonder
  result-file, timeout-tokens) → reset naar de vorige status + `Error` legen +
  opnieuw, tot `maxTransientRetries` (default 2); daarna permanente `Error`. In v2
  werkt dit **per subtask-run** (zie fase 6).

## 8. Velden-overzicht (story vs subtask)

| Veld | Story | Subtask |
|------|-------|---------|
| Story Phase / Subtask Phase | `Story Phase` | `Subtask Phase` |
| Subtask Type | leeg | development / review / test / manual / summary |
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
| 2 | `fase-2-refinement-loskoppelen.md` | `PLANNER`-rol + goedkeuringsstappen (refine & plan) | **Doel #1** |
| 3 | `fase-3-subtask-creatie.md` | planner declareert (incl. story-brede review/test) → orchestrator materialiseert, idempotent | Subtaken bestaan |
| 4 | `fase-4-story-development-coordinator.md` | subtaken sequencen via keten op completion (geen story-polling) | Subtask-sequencing |
| 5 | `fase-5-subtask-execution-coordinator.md` | per-type ketting van AI-stappen + goedkeuringsstappen + goedkeuring-gestuurde loopback | Subtask-uitvoering |
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
- **PR-comment feedback-route** (fase 7): nu zet een late `@factory`-PR-comment de
  story-phase terug naar de developer (`monitorPullRequest`). In v2 → maak in plaats
  daarvan een nieuwe `development`-subtask op de story aan (loopt via de keten).
  Ook de "PR gemerged → klaar"-afhandeling moet mee (geen story-Done-phase meer).
