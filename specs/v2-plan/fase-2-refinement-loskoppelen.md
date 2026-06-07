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
- **`StoryRefinementCoordinator`** introduceren, drijvend op de Story Phase:

  **Refine-stap:**
  - `REFINING` → (vragen-loop: `REFINED_WITH_QUESTIONS` ⇄ `QUESTIONS_ANSWERED`)
    → `REFINED` (AI klaar, wacht op mens).
  - **Approve/reject-gate:** de mens zet zelf de status.
    - `REFINED_REJECTED` → refiner draait opnieuw (leest de comments / aangepaste
      description van de mens) → terug naar `REFINING`.
    - `REFINED_APPROVED` → de coördinator dispatcht de **planner**.

  **Plan-stap:**
  - `PLANNING` → (vragen-loop: `PLANNED_WITH_QUESTIONS` ⇄
    `PLANNING_QUESTIONS_ANSWERED`) → `PLANNED` (AI klaar, wacht op mens).
  - **Approve/reject-gate:**
    - `PLANNING_REJECTED` → planner draait opnieuw → terug naar `PLANNING`.
    - `PLANNING_APPROVED` → **terminal** voor de refinement-coördinator; de
      orchestrator laat de story los.

- **Gate naar development (Optie B):** na `PLANNING_APPROVED` gebeurt er niets
  automatisch. Development start pas als de **mens zelf de tag `ai-development`**
  op de story zet (opgepakt in fase 4).

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
