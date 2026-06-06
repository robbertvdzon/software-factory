# Fase 2 — Refinement loskoppelen (REFINER + PLANNER + gate)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.
>
> **Dit levert doel #1: de story los kunnen refinen + plannen.**

## Doel

De story kan los worden gerefined en gepland, en **stopt** daarna voor inspectie.
Development start pas op een handmatige trigger.

## Wijzigingen

- **`AgentRole.PLANNER`** toevoegen (`youtrack/TrackerModels.kt`) + routing in
  `AiRouting` (`orchestrator/services/AiRouting.kt`).
- **`StoryRefinementCoordinator`** introduceren:
  - `REFINING` → (bestaande user-vragen-loop) → `REFINED`
  - → dispatch `PLANNER` → `PLANNING` → `PLANNED`
  - **stopt op `PLANNED`** (geen automatische doorgang naar development).
- **Gate** tussen `PLANNED` en `DEVELOPING`: development begint pas als de
  gebruiker de story op `DEVELOPING` zet — via een veld of een nieuw
  `@factory:command:develop` (`FactoryCommand` in `TrackerModels.kt`).

## Aandachtspunten

- De planner produceert in deze fase nog geen echte subtaken (dat is fase 3);
  hier gaat het om de rol, de fasen en de gate.
- De refiner-rol en de user-vragen-loop blijven functioneel zoals nu.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/youtrack/TrackerModels.kt`
- `.../orchestrator/services/AiRouting.kt`
- `.../orchestrator/services/OrchestratorService.kt` (+ nieuwe `StoryRefinementCoordinator`)

## Test

- Story doorloopt `refine → plan → stop op PLANNED`.
- Zonder gate-trigger start development niet.
- Gate-trigger (`develop`-commando/veld) zet de story op `DEVELOPING`.

## Klaar wanneer

Een story kan volledig worden gerefined en gepland en blijft daarna staan tot de
gebruiker development expliciet start.
