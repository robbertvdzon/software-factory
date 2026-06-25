# SF-213 - Worklog

Story-context bij eerste pickup:
Subtaaktype 'documentation' + DOCUMENTER-agent implementeren

Voeg een nieuw vast subtaaktype 'documentation' (titel 'Werk documentatie bij') en AI-rol DOCUMENTER toe, gemodelleerd naar summary/test. Core: TrackerModels.kt (SubtaskType.DOCUMENTATION, AgentRole.DOCUMENTER), SubtaskPhase.kt (fasen documenting/documented/documentation-with-questions/documentation-questions-answered/documentation-approved; laatste terminaal in isTerminal; geen reject-tak), AiPhase.kt (DOCUMENTING + afgerond, activeFor()/result-mapping mirror TESTER/SUMMARIZER). Pipeline: SubtaskExecutionCoordinator.processSubtask() tak DOCUMENTATION -> documentationSubtask(); handler naar voorbeeld summarySubtask() (dispatch DOCUMENTER bij documenting, recover, autoAdvanceSubtask -> documentation-approved, advanceSubtaskChain bij approved). Materialisatie: AgentRunCompletionService.materializeSubtasksIfPlanned() documentationSpecs (altijd aan, AI-taak) als (plannedSpecs + documentationSpecs + manualApproveSpecs + chainClosingSpecs); stray planner 'documentation'-spec filteren via SubtaskType.…->null-patroon (zoals MERGE/DEPLOY); constante DOCUMENTATION_SUBTASK_TITLE='Werk documentatie bij'. YouTrack-schema: youtrack/clients/YouTrackClient.kt 'documentation' in subtaskTypeValues en vijf nieuwe fasen in subtaskPhaseValues. Agentworker: ClaudeCodeAiClient.kt (ClaudePromptBuilder) DOCUMENTER in rolePrompt()/userPrompt()/questionsPhaseFor() (documentation-with-questions) en JSON-contract/retryExample ({"phase":"documented"} | {"phase":"documentation-with-questions","questions":[…]}), parser accepteert documenter-fasen; DummyAiClient.kt dummy DOCUMENTER-tak; controleer copilot/codex falen niet op nieuwe rol. Werk OrchestratorServiceTest en overige ketens/fixtures + unit-tests bij voor de nieuwe subtaak-volgorde, fasen en stray-spec-dedup. Sluit af met de ingebouwde review-stap.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results
[x]: update docs/factory specs (functional-spec, technical-spec, agents/documenter.md)

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-214 — Subtaaktype 'documentation' + DOCUMENTER-agent

In eigen woorden: een nieuw, factory-afgedwongen subtaaktype `documentation` ("Werk documentatie
bij") toegevoegd, uitgevoerd door de nieuwe AI-rol DOCUMENTER. De subtaak wordt automatisch aan
elke story toegevoegd, ná de planner-subtaken (dus ná `summary`) en vóór de manual-approve-poort.
Nieuwe ketenvolgorde: `development → review → test → summary → documentation → manual-approve →
merge → deploy`. De levenscyclus spiegelt `summary`/`test`; er is bewust géén reject-tak.

### Gedaan

Core (`softwarefactory`):
- `core/TrackerModels.kt`: `AgentRole.DOCUMENTER("[DOCUMENTER]")` en `SubtaskType.DOCUMENTATION("documentation")`.
- `core/SubtaskPhase.kt`: fasen `documenting` (activeRole DOCUMENTER), `documented`,
  `documentation-with-questions`, `documentation-questions-answered`, `documentation-approved`
  (terminaal in `isTerminal`). Geen `documentation-rejected`.
- `core/AiPhase.kt`: `DOCUMENTING` (activeRole DOCUMENTER) + `DOCUMENTATION_FINISHED`;
  `completedAfterSuccessful`/`previousCompletedBeforeRetry` aangevuld (mirror SUMMARIZER).
- Exhaustieve `when (role)`-blokken bijgewerkt voor de nieuwe rol: `OrchestratorSettings.maxParallelFor`,
  `AgentCommentContext` (documenter leest alle agent-output incl. summarizer), `AgentKnowledgeService`.
- `pipeline/service/SubtaskExecutionCoordinator.kt`: `processSubtask`-tak
  `DOCUMENTATION -> documentationSubtask()` + nieuwe handler naar voorbeeld van `summarySubtask()`
  (dispatch DOCUMENTER, recover `documenting`, `autoAdvanceSubtask` → `documentation-approved`,
  `advanceSubtaskChain` bij approved).
- `runtime/services/AgentRunCompletionService.kt`: `documentationSpecs` (altijd aan, AI-taak)
  ingevoegd als `(plannedSpecs + documentationSpecs + manualApproveSpecs + chainClosingSpecs)`;
  stray planner-`documentation`-spec gefilterd via het `SubtaskType.… -> null`-patroon (zoals
  MERGE/DEPLOY); constante `DOCUMENTATION_SUBTASK_TITLE = "Werk documentatie bij"`.
- `youtrack/clients/YouTrackClient.kt`: `documentation` in `subtaskTypeValues`, de vijf nieuwe
  fasen in `subtaskPhaseValues` (schema-bootstrap).

Agentworker:
- `youtrack/TrackerModels.kt`: `AgentRole.DOCUMENTER` toegevoegd.
- `agent/ai/claude/ClaudeCodeAiClient.kt` (`ClaudePromptBuilder` + `ClaudeOutcomeParser`):
  DOCUMENTER-rol in `rolePrompt()`, `userPrompt()`, `questionsPhaseFor()`
  (`documentation-with-questions`), `retryExample()` en het JSON-contract; parser accepteert de
  documenter-fasen. Copilot/codex erven dit (supplier-agnostisch) en falen niet op de nieuwe rol.
- `agent/ai/dummy/DummyAiClient.kt`: dummy DOCUMENTER-tak (`documented` / `documentation-with-questions`).

Tests (zelf geschreven/bijgewerkt):
- `OrchestratorServiceTest`: 4 nieuwe coördinator-tests (start→dispatch, documented→waiting,
  auto-approve→documentation-approved, documentation-approved→chain).
- `AgentRunCompletionServiceTest`: subtaak-volgorde/-types-asserts bijgewerkt met "Werk documentatie bij".
- `e2e/FakeYouTrackState.kt`: schema-seed bijgewerkt (type + fasen) zodat `FakeYouTrackServerTest`
  groen blijft.
- agentworker `ClaudeCodeAiClientTest` + `DummyAiClientTest`: DOCUMENTER-fasen toegevoegd.

Docs: `docs/factory/functional-spec.md`, `docs/factory/technical-spec.md` en nieuwe
`docs/factory/agents/documenter.md` bijgewerkt met de documentatie-stap en ketenvolgorde.

### Niet gedaan / bewust overgeslagen
- Geen `documentation-rejected`/loopback-mechaniek (conform story-aanname).
- `dashboard-backend`-`YouTrackClient.kt` ongemoeid (buiten scope; full build compileert).
- De Docker-afhankelijke e2e-tests (`PipelineFlowsE2eTest`, `FullRefineToDevelopE2eTest`) konden
  lokaal niet draaien (geen Docker; falen identiek op schone `main` bij context-load). Hun
  child-count/`.single()`-asserts zijn niet aangepast: de documentation-subtaak wordt via exact
  dezelfde materialisatie-`forEach` aangemaakt als de bestaande merge/deploy-subtaken, die deze
  asserts ook niet beïnvloeden (commit `aba4e16` voegde merge/deploy toe zonder die e2e-tests te
  wijzigen). Verifieer in de pipeline met Docker.

### Tests gedraaid (lokaal, mvn 3.9.10 + JDK 21)
- `softwarefactory`: `OrchestratorServiceTest` (55), `AgentRunCompletionServiceTest` (13),
  `FakeYouTrackServerTest` (5) — alle groen.
- `agentworker`: volledige suite (34) — groen.
- Volledige `mvn test-compile` over alle modules: BUILD SUCCESS.
