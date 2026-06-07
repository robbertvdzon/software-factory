# Fase 2 — Refinement loskoppelen (REFINER + PLANNER + approve/reject-gates)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.
>
> **Dit levert doel #1: de story los kunnen refinen + plannen, met mens-controle.**

## Doel

De story kan los worden gerefined en gepland, met **twee expliciete
approve/reject-gates** (na refine en na plan). Development is losgekoppeld: de
mens zet zelf de `ai-development`-tag wanneer hij wil starten (Optie B).

## Twee soorten mens-interactie (uit elkaar gehouden)

- **Vragen-loop** = *AI vraagt*, mens antwoordt → AI draait opnieuw.
- **Approve/reject-gate** = *AI is klaar*, mens beoordeelt → goedgekeurd of
  teruggestuurd met opmerkingen.

## Wijzigingen

- **`AgentRole.PLANNER`** toevoegen (`youtrack/TrackerModels.kt`) + routing in
  `AiRouting`. De planner is een **echt aparte stap** (eigen fasen + gate).
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

- **Gate naar development (Optie B):** na `PLANNING_APPROVED` gebeurt er niets
  automatisch. Development start pas als de **mens zelf de tag `ai-development`**
  op de **eerste subtask** zet (opgepakt in fase 4).

## Aandachtspunten

- De planner produceert in deze fase nog geen echte subtaken (dat is fase 3);
  hier gaat het om de rol, de fasen, de vragen-loops en de approve/reject-gates.
- Rol-onderscheid: de **refiner** verbetert story-tekst + stelt vragen
  (consistentie met specs, acceptatiecriteria, risico-analyse, geraakte modules);
  de **planner** maakt het implementatieplan in de story-body. Scherpere prompts
  per rol.
- Bij `*_REJECTED` leest de opnieuw-draaiende agent de mens-feedback uit de
  story-comments en/of de aangepaste description.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/youtrack/TrackerModels.kt`
- `.../orchestrator/services/AiRouting.kt`
- `.../orchestrator/services/OrchestratorService.kt` (+ nieuwe `StoryRefinementCoordinator`)
- `.../youtrack/clients/YouTrackClient.kt` (status/tag lezen)

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
