# SF-213 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

## Eindsamenvatting — SF-213: Nieuw subtaaktype 'documentation' met DOCUMENTER-agent

### Wat is gebouwd
Een nieuw, factory-afgedwongen subtaaktype **`documentation`** ("Werk documentatie bij"), uitgevoerd door een nieuwe AI-rol **DOCUMENTER**. De subtaak wordt automatisch aan elke story toegevoegd, ná de planner-subtaken (dus ná `summary`) en vóór de manual-approve-poort. De nieuwe ketenvolgorde is:

`development → review → test → summary → documentation → manual-approve → merge → deploy`

De documenter werkt bij oppakken de relevante documentatie bij (README's, `docs/`, specs e.d.) zodat die klopt met wat in de story is gedaan, en rapporteert via het JSON-contract (`{"phase":"documented"}` of `{"phase":"documentation-with-questions","questions":[…]}`). De levenscyclus spiegelt die van `summary`/`test`, met `documentation-approved` als terminale fase.

### Belangrijkste keuzes
- **Plaatsing ná summary, vóór manual-approve** — zoals de issue-auteur expliciet koos; niet "strikt direct ná test", omdat dat het herordenen van planner-specs vergt en fragieler is (bewust buiten scope).
- **Altijd aan** voor elke story (niet per project uitschakelbaar), in tegenstelling tot de manual-approve-poort.
- **Geen reject-/loopback-tak** (`documentation-rejected`): de documenter doet zijn werk en rapporteert klaar.
- De rol/fasen zijn consistent **gemodelleerd naar het bestaande `summary`/`test`-patroon** over alle lagen: core-modellen (TrackerModels, SubtaskPhase incl. `isTerminal`, AiPhase), pipeline-coördinator, materialisatie, YouTrack-schema, prompt-builder/parser en dummy-supplier.
- Een per ongeluk door de planner meegestuurde `documentation`-spec wordt **gefilterd** via hetzelfde `-> null`-patroon als MERGE/DEPLOY, zodat er geen dubbele subtaak ontstaat.

### Wat is getest
- **softwarefactory**: `OrchestratorServiceTest` (incl. 4 nieuwe coördinator-tests: start→dispatch, documented→waiting, auto-approve→approved, approved→chain), `AgentRunCompletionServiceTest` (volgorde/type-asserts + stray-spec-dedup), `FakeYouTrackServerTest` — alle groen (73 tests).
- **agentworker**: volledige suite incl. `ClaudeCodeAiClientTest` en `DummyAiClientTest` met de nieuwe DOCUMENTER-fasen, en de copilot/codex-suppliers — groen (34 tests).
- `mvn test-compile` over alle modules: BUILD SUCCESS (geen compile-gaten in de exhaustieve `when(role)`-blokken).
- Geverifieerd tegen alle acceptatiecriteria: ketenvolgorde, titel/type/rol, terminale fase, auto-approve-doorloop, JSON-contract en stray-spec-dedup.

### Bewust niet gedaan / aandachtspunten
- **Geen `documentation-rejected`/loopback-mechaniek** (conform story-aanname).
- De losse **`dashboard-backend`-`YouTrackClient.kt`** is ongemoeid gelaten (buiten scope; de full build compileert).
- **Docker-afhankelijke e2e-tests** (`PipelineFlowsE2eTest`, `FullRefineToDevelopE2eTest`) konden lokaal niet draaien (geen Docker; falen identiek op schone `main`). Hun child-count/`.single()`-asserts zijn niet aangepast — de documentation-subtaak ontstaat via exact dezelfde materialisatie-`forEach` als de bestaande merge/deploy-subtaken (die deze asserts ook niet raakten). **Te verifiëren in de CI-pipeline met Docker.**
- Reviewer-suggestie (non-blocking): `e2e/AgentScript.kt#resultFor` heeft nog geen expliciete DOCUMENTER-tak; nu latent, toe te voegen voor e2e-pariteit zodra een e2e de volledige keten doorloopt.

### Wijzigingen
21 bestanden gewijzigd (+374/−12): core-modellen, pipeline-coördinator, materialisatie-service, YouTrack-schema, claude/dummy AI-clients, tests, en factory-docs (`functional-spec.md`, `technical-spec.md`, nieuwe `agents/documenter.md`).
