# Fase 2b — Plan-stap + gate (orchestrator)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md)
> en de fase-2 overview: [fase-2-refinement-loskoppelen.md](./fase-2-refinement-loskoppelen.md).
> Bouwt voort op [fase-2a](./fase-2a-refine-orchestrator.md).

## Doel

De **plan-stap** toevoegen aan de `StoryRefinementCoordinator`: na
`refined-approved` draait de planner, met eigen vragen-loop en goedkeuringsstap,
eindigend op de terminale `planning-approved`. De planner is een **echt aparte
stap** (eigen rol + fasen + goedkeuring). Nog **geen** subtaken (dat is fase 3).

Test-groen met de bestaande test-fakes (de echte planner-agent komt in 2c).

## Scope: statussen (vervolg op 2a)

| Status | Wat de orchestrator doet |
|---|---|
| `refined-approved` | dispatch **planner**, zet `Story Phase = planning` |
| `planning` | niets; planner draait (recovery/timeout). Completion-handler zet `planned-with-questions` of `planned` |
| `planned-with-questions` | niets; wacht tot mens `planning-questions-answered` zet |
| `planning-questions-answered` | dispatch planner (met antwoorden), zet `planning` |
| `planned` | niets; wacht tot mens `planning-rejected` of `planning-approved` zet |
| `planning-rejected` | dispatch planner (met feedback), zet `planning` |
| `planning-approved` | **terminaal** — orchestrator laat de story los |

## Wijzigingen

- **`AgentRole.PLANNER`** toevoegen (`youtrack/TrackerModels.kt`). Dit cascadeert
  naar exhaustive `when (role)`-plekken:
  - `AiPhase.completedAfterSuccessful` / `previousCompletedBeforeRetry` (PLANNER-tak),
  - `AiRouting` (PLANNER-tier/-routing).
- **Coordinator uitbreiden** met de plan-statussen (tabel hierboven). `refined-approved`
  (in 2a nog `Skipped`) dispatcht nu de planner.
- **Completion-handler**: voor een geslaagde **PLANNER**-run het `Story Phase`-veld
  zetten (`planned` of `planned-with-questions`).
- **Recovery/transient-retry** voor de actieve status `planning` (story-phase-bewust).
- `planning-approved` is terminaal: de orchestrator doet niets meer met de story
  (development is tag-gedreven, fase 4).

## Aandachtspunten

- **Geen subtaken in 2b.** `planning → planned` zet nog geen subtaken aan; dat is
  fase 3 (de overview-tabel toont de eindsituatie met `createSubtask`).
- Rol-onderscheid: refiner verbetert story-tekst + stelt vragen; planner maakt het
  implementatieplan in de story-body. Scherpere prompts per rol (prompts zelf =
  agentworker, fase 2c).

## Betrokken bestanden

- `.../youtrack/TrackerModels.kt` (`AgentRole.PLANNER`)
- `.../orchestrator/AiPhase.kt` (PLANNER-takken in exhaustive `when`)
- `.../orchestrator/services/AiRouting.kt` (PLANNER-tier)
- `.../orchestrator/services/OrchestratorService.kt` (plan-statussen in de coordinator)
- `.../runtime/services/AgentRunCompletionService.kt` (PLANNER → `Story Phase`)

## Test

- `refined-approved → planning`; completion → `planned`; mens `planning-rejected`
  → `planning`; `planning-approved` → terminaal (Skipped/niets).
- Vragen-loop planner: completion → `planned-with-questions`;
  `planning-questions-answered` → `planning`.
- Volledige refine→plan-flow op `Story Phase` test-groen.

## Klaar wanneer

De volledige refine→plan-flow draait op `Story Phase` met twee goedkeuringsstappen,
`planning-approved` is terminaal, en de tests zijn groen. Subtaken volgen in fase 3.
