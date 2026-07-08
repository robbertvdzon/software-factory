# Software Factory — Specs

> ⚠️ **Historisch document (2026-05).** Dit document beschrijft deels een verouderd model
> (o.a. work-tags als trigger; die bestaan niet meer — werk start via fase `start`, en de
> keten bevat inmiddels ook de afgedwongen documentation/manual-approve/merge/deploy-subtaken).
> Actueel: [docs/factory/functional-spec.md](../docs/factory/functional-spec.md).

Beschrijft hoe de Software Factory nu werkt: een poll-gestuurde pijplijn die
tracker-issues via AI-agents van refinement naar werkende code brengt. Werk wordt
op twee niveaus gemodelleerd: een **story** (wat moet er gebeuren) wordt eerst
gerefined en gepland, en valt daarna uiteen in **subtaken** (development, review,
test, manual, summary) die één voor één op een gedeelde branch worden uitgevoerd.

## 1. Architectuur

Eén poller leest werk-issues uit de tracker-database en stuurt ze door een **dunne router**
naar coördinatoren die de gedeelde services (dispatch, workspace, Docker-runtime,
cost/budget, recovery) hergebruiken.

```
OrchestratorPoller (findWorkIssues)
  -> OrchestratorService.processIssue  (router op het Type-veld)
       Type = User Story  -> StoryRefinementCoordinator   (refine + plan)
       Type = Task        -> SubtaskExecutionCoordinator   (per-type pipeline)
```

Modules:
- **softwarefactory** — orchestrator (routing, dispatch, recovery, cost), runtime
  (workspaces, Docker, completion-handler), tracker (issue-tracker-client), web
  (dashboard).
- **agentworker** — het standalone containerproces dat één agent-run uitvoert: repo
  klaarzetten, prompt bouwen, AI-client aanroepen, PR publiceren en het resultaat
  naar `agent-result.json` schrijven. Praat nooit rechtstreeks met de
  factory-server.

Agents draaien in Docker en zijn afgeschermd van de tracker-database: ze lezen hun
taak en schrijven alleen `agent-result.json`; de orchestrator schrijft naar de
tracker-database. De voltooiing wordt gedetecteerd door een poller die
`agent-result.json` leest.

## 2. Tracker-model

- **IssueType** komt uit het standaard `Type`-veld: `User Story` → STORY,
  `Task` → SUBTASK.
- **Work-tags** triggeren werk: `ai-refinement` op een story, `ai-development` op
  een subtask. Een issue zonder de juiste tag wordt nooit opgepakt.
- **Custom fields** (bij startup geprovisioneerd op elk gemanaged project):
  - `Story Phase` — de refinement-lifecycle van een story.
  - `Subtask Phase` — de status binnen een subtask.
  - `Subtask Type` — `development` / `review` / `test` / `manual` / `summary`.
  - `AI-supplier` — `mock` / `claude` / `openai` / `copilot` / `microsoft` / `none`.
  - `AI Model`, `AI Reasoning Effort` — per issue; de planner kiest deze per subtask.
  - `AI Token Budget`, `AI Tokens Used`, `Paused`, `Error`, `AgentStartedAt`,
    `AI Max Developer Loopbacks`.
- **Subtaken** worden gelegd via een `parent_key`-koppeling in de unified `issues`-tabel.
- **Verbinding/transport** — inmiddels is er geen externe issue-tracker meer: de
  `PostgresTrackerClient` praat rechtstreeks met de lokale Postgres (JDBC), geen
  HTTP-transport, geen certificaten of tunnels. Zie
  [docs/factory/technical-spec.md](../docs/factory/technical-spec.md) §Tracker-database
  voor het actuele model.

## 3. Story-flow (`Story Phase`)

Een story met de tag `ai-refinement` doorloopt twee AI-stappen, elk met een
**vragen-loop** (AI vraagt, mens antwoordt → AI draait opnieuw) en een
**goedkeuringsstap** (AI is klaar, mens keurt goed of af):

| Status | Orchestrator |
|---|---|
| _(leeg)_ | start de refiner → `refining` |
| `refining` | refiner draait; completion zet `refined` of `refined-with-questions` |
| `refined-with-questions` | wacht; mens antwoordt en zet `questions-answered` |
| `questions-answered` | start de refiner (met antwoorden) → `refining` |
| `refined` | wacht op `refined-approved` / `refined-rejected` (mens) |
| `refined-rejected` | start de refiner (met feedback) → `refining` |
| `refined-approved` | start de planner → `planning` |
| `planning` | planner draait; completion zet `planned` of `planned-with-questions` (en materialiseert de subtaken) |
| `planned-with-questions` | wacht; mens zet `planning-questions-answered` |
| `planning-questions-answered` | start de planner (met antwoorden) → `planning` |
| `planned` | wacht op `planning-approved` / `planning-rejected` (mens) |
| `planning-rejected` | start de planner (met feedback) → `planning` |
| `planning-approved` | terminaal; refinement klaar, de orchestrator laat de story los |

De refiner verbetert de story-tekst en stelt blokkerende vragen; de planner maakt
het implementatieplan in de story-body en declareert de subtaken. Er is geen
development/done/summarizing op story-niveau — development is tag-gedreven en draait
op subtaken.

## 4. Subtask-creatie

De planner **declareert** subtaken in `agent-result.json`; de orchestrator
**materialiseert** ze met `createSubtask` zodra de planner `planned` bereikt
(idempotent op bestaande child-titels, zodat een re-plan geen duplicaten maakt).
Elke subtask krijgt `Type = Task`, een `Subtask Type`, de AI-supplier van de story
(per subtask overschrijfbaar via model/effort) en **geen** tag — een subtask is
inert tot 'ie `ai-development` krijgt. De planner zet typisch een reeks
`development`-subtaken, gevolgd door een story-brede `review`, een `test` en als
laatste een `summary`.

## 5. Subtask-keten

Na `planning-approved` zet de mens de tag `ai-development` op de **eerste** subtask.
Zodra een subtask z'n eindstatus bereikt (`*-approved` of `manual-action-done`)
tagt de poller de **eerstvolgende niet-afgeronde** sibling (in aanmaakvolgorde) en
haalt de tag van de afgeronde subtask. Zo is er altijd hooguit één subtask getagd;
samen met de serialisatie op parent-key draait er nooit meer dan één agent op de
gedeelde branch.

## 6. Subtask-uitvoering (`Subtask Phase`)

Een subtask is een ketting van AI-stappen. Elke AI-stap (rol R, naam N) volgt:

```
N-ing → (N-ed-with-questions ⇄ N-questions-answered) → N-ed → [goedkeuring] N-approved | N-rejected
```

De completion-handler zet de overgang uit `N-ing`; de mens (of, later, een
auto-approve-instelling) zet `N-approved`/`N-rejected`. Een `*-rejected` start een
developer-fix op de gedeelde branch en loopt terug naar de primaire rol, begrensd
door `AI Max Developer Loopbacks`. De reviewer/tester mag z'n `*-rejected` zelf
zetten (findings → direct terug naar de developer).

Per type:
- **development** — developer-stap, gevolgd door een ingebouwde reviewer-stap;
  terminaal op `review-approved`.
- **review** / **test** — story-breed; reviewer/tester met een simpele
  fix-developer (na een fix volgt automatisch een re-review/-test, zonder aparte
  dev-goedkeuring); terminaal op `review-approved` resp. `test-approved`.
- **manual** — geen agent; `awaiting-human` tot de mens `manual-action-done` zet.
- **summary** — summarizer-stap; terminaal op `summary-approved`.

## 7. Gedeelde branch, budget en recovery

- **Gedeelde branch/workspace/PR**: alle subtaken van een story werken op één
  branch. Een subtask-dispatch keyt z'n `StoryRun`, concurrency-guard en
  Docker-label op de **parent-story**, zodat branch, workspace en PR worden
  hergebruikt en er nooit twee agents tegelijk draaien.
- **Budget** (`AI Token Budget`) is story-niveau: de som van `Tokens Used` over de
  story en haar subtaken wordt vergeleken met het budget; bij 100% wordt de story
  gepauzeerd (`Paused = true`, met comment). `CONTINUE`/`BUDGET=N` hervat.
- **Pauze**: een gepauzeerde story zet ook de subtask-dispatch stil (de subtask
  checkt de `Paused` van de parent).
- **Credits-exhausted** zet een systeembrede pauze tot een tijdstip; zolang die
  loopt worden alle issues overgeslagen.
- **Error** is per issue: een issue met `Error` wordt overgeslagen tot
  `clear-error`; een subtask met `Error` stalt de keten.
- **Recovery/timeout**: `AgentStartedAt` + hard timeout → permanente `Error`;
  transiente fouten legen `Error` en laten de recovery-poll de actieve stap
  herstarten, tot `maxTransientRetries`.

## 8. Agents en suppliers

Rollen: `refiner`, `planner`, `developer`, `reviewer`, `tester`, `summarizer`. De
agentworker draait de juiste flow per rol en emit de bijbehorende fase-waarde
(`Story Phase` voor refiner/planner, `Subtask Phase` voor de rest) plus eventuele
subtaak-declaraties in `agent-result.json`. Suppliers: `mock` (DummyAiClient, voor
end-to-end testen zonder echte AI), `claude`, `openai`/`codex`, `copilot`. Model en
reasoning effort komen uit de issue-velden.

Late `@factory`-comments op een PR worden opgepakt als een nieuwe
`development`-subtask op de story (in plaats van een story-reset).

## 9. Dashboard

Server-rendered dashboard met een stories-overzicht en detailschermen:
- De lijst en het detail tonen een **Story/Subtask-badge** en de juiste fase.
- **Mens-acties** vanuit de UI: vragen beantwoorden en stappen approven/rejecten met
  reden — op zowel `Story Phase` als `Subtask Phase` (en `mark-done` voor manual).
- Het story-detail toont een **Subtaken-paneel**; een subtask opent een eigen
  detailscherm met status, mens-acties, commands en een per-subtask briefing
  (agent-runs zijn per subtask traceerbaar).
- Commands: pause/resume/kill, clear-error, retry-current-step, sync, merge, delete,
  re-implement — werkend voor zowel story als subtask.
