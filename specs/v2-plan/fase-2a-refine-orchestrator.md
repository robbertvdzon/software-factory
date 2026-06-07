# Fase 2a — Refine-stap op `Story Phase` (orchestrator-fundament)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md)
> en de fase-2 overview: [fase-2-refinement-loskoppelen.md](./fase-2-refinement-loskoppelen.md).

## Doel

De STORY-tak van de router omzetten van de fase-1 `AiPhase`-flow naar een
`StoryRefinementCoordinator` die de **refine-stap** op het **`Story Phase`-veld**
draait — met de vragen-loop en de goedkeuringsstap. Dit legt het fundament: de
dispatch/completion/recovery-machinerie wordt **phase-veld-bewust** (schrijft
`Story Phase` voor story-rollen i.p.v. `AI Phase`). De planner volgt in 2b.

Test-groen met de bestaande test-fakes (de agentworker komt in 2c).

## Scope: statussen (subset van de fase-2 tabel)

| Status | Wat de orchestrator doet |
|---|---|
| _(geen status)_ | dispatch refiner, zet `Story Phase = refining` |
| `refining` | niets; refiner draait (recovery/timeout). Completion-handler zet `refined-with-questions` of `refined` |
| `refined-with-questions` | niets; wacht tot mens `questions-answered` zet |
| `questions-answered` | dispatch refiner (met antwoorden), zet `refining` |
| `refined` | niets; wacht tot mens `refined-rejected` of `refined-approved` zet |
| `refined-rejected` | dispatch refiner (met feedback), zet `refining` |
| `refined-approved` | **voorlopig stop** (Skipped); de planner-dispatch komt in 2b |

## Wijzigingen

- **Dispatch phase-veld-bewust maken.** `AgentDispatchRequest.phase: AiPhase` is te
  beperkt (geen story-phases). Ontkoppel het: laat dispatch de actieve status als
  **trackerValue + doelveld** schrijven. Voor story-rollen (refiner) →
  `Story Phase`. Concreet: een variant/parameter zodat `dispatchIfAllowed` voor de
  refiner `TrackerField.STORY_PHASE = "refining"` zet (i.p.v. `AI_PHASE`).
- **`StoryRefinementCoordinator`** (refiner-helft) introduceren; de STORY-tak in
  `processIssue` roept deze aan (vervangt `processStory` voor de refine-statussen).
  Hij leest `StoryPhase.fromTracker(issue.fields.storyPhase)`.
- **Completion-handler (`AgentRunCompletionService`)**: voor een geslaagde
  **REFINER**-run het **`Story Phase`-veld** zetten (`refined` of
  `refined-with-questions`) i.p.v. `AI Phase`. De waarde komt uit de agent-result
  (`request.phase`); in 2a leveren de test-fakes die waarde, de echte agent in 2c.
- **Recovery/transient-retry** voor de actieve status `refining` op `Story Phase`
  (analoog aan `recoverActivePhase`, maar story-phase-bewust).

## Aandachtspunten

- `refined-approved` eindigt in 2a bewust op `Skipped` (geen planner). In 2b wordt
  dit de planner-dispatch.
- De recovery/budget/concurrency-guards blijven hergebruikt; alleen het
  phase-veld dat geschreven/gelezen wordt verandert voor story-rollen.
- Houd de oude `AiPhase`-flow (developer/reviewer/tester/summarizer) voorlopig
  intact voor zover die niet door story-rollen wordt geraakt; die wordt in
  fase 5/7 opgeruimd.

## Betrokken bestanden

- `.../orchestrator/AgentRuntime.kt` (`AgentDispatchRequest.phase` ontkoppelen)
- `.../orchestrator/services/OrchestratorService.kt` (`StoryRefinementCoordinator`,
  dispatch schrijft `Story Phase`, recovery story-phase-bewust)
- `.../runtime/services/AgentRunCompletionService.kt` (REFINER → `Story Phase`)
- `.../orchestrator/StoryPhase.kt` (evt. helpers: actieve status, vorige-bij-retry)

## Test

- `(geen) → refining`; completion → `refined`; mens `refined-rejected` → `refining`.
- Vragen-loop: completion → `refined-with-questions`; `questions-answered` →
  `refining`.
- `refined-approved` → Skipped (nog geen planner).
- Dispatch schrijft `Story Phase`, niet `AI Phase`, voor de refiner.
- (Herschrijf de refiner-gerelateerde `OrchestratorServiceTest`-cases hiernaar.)

## Klaar wanneer

De refine-stap draait volledig op het `Story Phase`-veld met vragen-loop +
goedkeuring, de dispatch/completion/recovery zijn phase-veld-bewust, en de tests
zijn groen. `refined-approved` stopt (planner = 2b).
