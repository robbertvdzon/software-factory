# Fase 2 — Refinement loskoppelen (REFINER + PLANNER + label-gate)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.
>
> **Dit levert doel #1: de story los kunnen refinen + plannen.**

## Doel

De story kan los worden gerefined en gepland, en **stopt** daarna op de gate voor
inspectie. Development start pas op een handmatige trigger (label).

## Wijzigingen

- **`AgentRole.PLANNER`** toevoegen (`youtrack/TrackerModels.kt`) + routing in
  `AiRouting` (`orchestrator/services/AiRouting.kt`).
- **`StoryRefinementCoordinator`** introduceren:
  - `REFINING` → (bestaande user-vragen-loop:
    `REFINED_WITH_QUESTIONS` ⇄ `QUESTIONS_ANSWERED`) → `REFINED`
  - **manual-adjust checkpoint** op `REFINED`: de mens kan de gerefinede
    story-tekst zelf bijstellen; daarna door naar plannen.
  - → dispatch `PLANNER` → `PLANNING` → `PLANNED`
  - op `PLANNED`: zet het label op **`ai-refined`** en **stop** (geen
    automatische doorgang naar development). Tweede manual-adjust moment: de mens
    inspecteert plan + (in fase 3) de aangemaakte subtaken en past desgewenst aan.

- **Label-gate** tussen `PLANNED` en `DEVELOPING`:
  - de refinement draait onder label `ai-refinement`;
  - aan het eind flipt de orchestrator naar `ai-refined` (idle/gate);
  - development begint pas als de **gebruiker het label op `ai-development`**
    zet. Dan zet de `StoryDevelopmentCoordinator` (fase 4) de story op
    `DEVELOPING`.

## Aandachtspunten

- De planner produceert in deze fase nog geen echte subtaken (dat is fase 3);
  hier gaat het om de rol, de fasen, de manual-adjust checkpoints en de gate.
- De refiner-rol en de user-vragen-loop blijven functioneel zoals nu.
- Het rol-onderscheid is bewust: de **refiner** verbetert story-tekst + stelt
  user-vragen (consistentie met specs, acceptatiecriteria, risico-analyse,
  geraakte modules); de **planner** maakt het implementatieplan in de
  story-body. Scherpere prompts per rol.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/youtrack/TrackerModels.kt`
- `.../orchestrator/services/AiRouting.kt`
- `.../orchestrator/services/OrchestratorService.kt` (+ nieuwe `StoryRefinementCoordinator`)
- `.../youtrack/clients/YouTrackClient.kt` (label zetten/lezen)

## Test

- Story doorloopt `refine → (manual-adjust) → plan → stop op PLANNED + label ai-refined`.
- Zonder label `ai-development` start development niet.
- Label `ai-development` zet de story op `DEVELOPING`.

## Klaar wanneer

Een story kan volledig worden gerefined en gepland, blijft op de gate
(`PLANNED` / `ai-refined`) staan, en development start pas als de gebruiker het
label op `ai-development` zet.
