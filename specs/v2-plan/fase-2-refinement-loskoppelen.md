# Fase 2 — Refinement loskoppelen (REFINER + PLANNER + goedkeuringsstappen)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.
>
> **Dit levert doel #1: de story los kunnen refinen + plannen, met mens-controle.**

## Doel

De story kan los worden gerefined en gepland, met **twee expliciete
goedkeuringsstappen** (na refine en na plan). Development is losgekoppeld: de
mens zet zelf de `ai-development`-tag wanneer hij wil starten (Optie B).

## Implementatie-opdeling (2a / 2b / 2c)

Fase 2 is groot en cross-module, dus opgeknipt in drie los reviewbare/bouwbare
stories. Deze doc blijft de **overview** (de status→actie-tabel + beslissingen);
de sub-stories bevatten de implementatie-scope.

| Deel | Bestand | Kern | Test-groen met |
|------|---------|------|----------------|
| 2a | [fase-2a-refine-orchestrator.md](./fase-2a-refine-orchestrator.md) | dispatch/completion/recovery rewiren naar `Story Phase` + refiner-helft van `StoryRefinementCoordinator` (refine-stap + goedkeuring) | fakes |
| 2b | [fase-2b-plan-orchestrator.md](./fase-2b-plan-orchestrator.md) | `AgentRole.PLANNER` + planner-helft + terminale `planning-approved` | fakes |
| 2c | [fase-2c-agentworker.md](./fase-2c-agentworker.md) | agentworker: refiner/planner emitten `Story Phase` + `--type=planner` | end-to-end |

2a legt het fundament (de phase-veld-rewire); 2b bouwt de plan-stap erop; 2c maakt
het écht draaiend met de agents. De onderstaande tabel is de gezamenlijke
einddoel-spec van 2a+2b.

## Twee soorten mens-interactie (uit elkaar gehouden)

- **Vragen-loop** = *AI vraagt*, mens antwoordt → AI draait opnieuw.
- **Goedkeuringsstap** = *AI is klaar*, mens beoordeelt → goedgekeurd of
  teruggestuurd met opmerkingen.

## Wijzigingen

- **`AgentRole.PLANNER`** toevoegen (`youtrack/TrackerModels.kt`) + routing in
  `AiRouting`. De planner is een **echt aparte stap** (eigen fasen + goedkeuringsstap).
- **`StoryRefinementCoordinator`** introduceren, drijvend op de Story Phase. Per
  status, wat de orchestrator doet:

  | Status | Wat de orchestrator doet |
  |---|---|
  | _(geen status)_ | start de refine-agent, zet status op `refining` |
  | `refining` | niets; refine-agent draait (liveness/timeout bewaken). Bij klaar zet de completion-handler `refined-with-questions` (mét vragen) of `refined` (zónder) |
  | `refined-with-questions` | niets; wacht tot de mens antwoordt (comment) en zelf `questions-answered` zet |
  | `questions-answered` | start de refine-agent (met de antwoorden), zet status op `refining` |
  | `refined` | niets; wacht tot de mens `refined-rejected` of `refined-approved` zet |
  | `refined-rejected` | start de refine-agent (met mens-feedback uit comments/description), zet status op `refining` |
  | `refined-approved` | start de planning-agent, zet status op `planning` |
  | `planning` | niets; planning-agent draait. Bij klaar: `planned-with-questions` (mét vragen), óf completion-handler **materialiseert de subtaken** (createSubtask, fase 3) en zet `planned` (zónder) |
  | `planned-with-questions` | niets; wacht tot de mens antwoordt en `planning-questions-answered` zet |
  | `planning-questions-answered` | start de planning-agent (met de antwoorden), zet status op `planning` |
  | `planned` | niets; wacht tot de mens `planning-rejected` of `planning-approved` zet |
  | `planning-rejected` | start de planning-agent (met mens-feedback), zet status op `planning`; planner **reconcilieert** bestaande subtaken (fase 3) |
  | `planning-approved` | niets meer; refinement klaar, orchestrator laat de story los |

- **Router omschakelen (vervangt fase 1):** de STORY-tak van de router gaat nu naar
  de `StoryRefinementCoordinator`, die op het **`Story Phase`-veld** draait (niet
  meer op `AI Phase`). De fase-1 `processStory`/`AiPhase`-flow voor stories vervalt
  hiermee; resten van `AiPhase` worden in fase 7 opgeruimd.
- **Completion-handler (`AgentRunCompletionService`):** zet voor een story het
  **`Story Phase`-veld** op de juiste vervolgstatus i.p.v. het oude `AI Phase`:
  refiner klaar → `refined-with-questions` (mét vragen) of `refined` (zónder);
  planner klaar → `planned-with-questions` of `planned`. De agent geeft in
  `agent-result.json` aan of 'ie vragen heeft.
- **Subtaken nog niet in deze fase:** `planning → planned` zet in fase 2 nog
  **geen** subtaken aan; dat komt in fase 3 (de tabel toont de eindsituatie met
  `createSubtask`).
- **Start van development (Optie B):** na `PLANNING_APPROVED` gebeurt er niets
  automatisch. Development start pas als de **mens zelf de tag `ai-development`**
  op de **eerste subtask** zet (subtaken bestaan vanaf fase 3; sequencing in fase 4).

## Aandachtspunten

- De planner produceert in deze fase nog geen echte subtaken (dat is fase 3);
  hier gaat het om de rol, de fasen, de vragen-loops en de goedkeuringsstappen.
- Rol-onderscheid: de **refiner** verbetert story-tekst + stelt vragen
  (consistentie met specs, acceptatiecriteria, risico-analyse, geraakte modules);
  de **planner** maakt het implementatieplan in de story-body. Scherpere prompts
  per rol.
- Bij `*_REJECTED` leest de opnieuw-draaiende agent de mens-feedback uit de
  story-comments en/of de aangepaste description.
- **`AgentRole.PLANNER` toevoegen cascadeert** naar exhaustive `when (role)`-plekken
  (o.a. `AiPhase.completedAfterSuccessful` en `AiRouting`): voeg daar een
  PLANNER-tak toe, anders compileert het niet.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/youtrack/TrackerModels.kt` (`AgentRole.PLANNER`)
- `.../orchestrator/services/AiRouting.kt` (routing + PLANNER-tier)
- `.../orchestrator/services/OrchestratorService.kt` (STORY-tak → nieuwe `StoryRefinementCoordinator` op `Story Phase`)
- `.../runtime/services/AgentRunCompletionService.kt` (vervolgstatus naar `Story Phase` zetten, op vragen/geen-vragen takken)
- `.../youtrack/clients/YouTrackClient.kt` (status lezen/schrijven op `Story Phase`)

## Test

- `refine → REFINED`; mens `REFINED_REJECTED` → refiner draait opnieuw.
- `REFINED_APPROVED` → planner draait → `PLANNED`.
- `PLANNED` mens `PLANNING_REJECTED` → planner opnieuw; `PLANNING_APPROVED` →
  orchestrator laat los (doet niets meer).
- Zonder de tag `ai-development` start development niet.

## Klaar wanneer

Een story doorloopt `refine → (approve/reject) → plan → (approve/reject)` met
expliciete mens-gates, blijft op `PLANNING_APPROVED` staan, en development start
pas als de mens zelf de `ai-development`-tag zet.
