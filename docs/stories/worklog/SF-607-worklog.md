# SF-607 - Worklog

Story-context bij eerste pickup:
Documentatie-audit en correcties

Stel via de broncode (softwarefactory/ + build/config) de actuele functionaliteit vast (agent-rollen, factory-flow, build/test-commando's, configuratie-/secret-sleutels, CLI/entrypoints). Vergelijk dit met de documentatie onder docs/factory/ en corrigeer uitsluitend feitelijke discrepanties in de docs. Code is leidend.

## Stappenplan

[x]: read issue and target docs
[x]: vergelijk docs/factory met de broncode (config, agent-rollen, factory-flow, CLI, suppliers, poorten)
[x]: corrigeer geconstateerde feitelijke discrepanties in de docs
[x]: verifieer dat de diff uitsluitend documentatie + worklog raakt
[x]: update story-log met results

## Gecontroleerde documenten

- `docs/factory/README.md`
- `docs/factory/development.md`
- `docs/factory/deployment.md`
- `docs/factory/functional-spec.md`
- `docs/factory/technical-spec.md`
- `docs/factory/secrets-local.md`
- `docs/factory/agents/{developer,refiner,reviewer,tester,summarizer,documenter}.md`

## Geverifieerd tegen de code (geen wijziging nodig — al in sync)

- **Modules / poorten** (`technical-spec.md`, `development.md`, `README.md`): root `pom.xml` heeft
  exact 3 Maven-modules (`softwarefactory`, `agentworker`, `dashboard-backend`) + losse Flutter
  `dashboard-frontend`. `docker/docker-compose.yml` mapt dashboard-backend op host-poort `9090` en
  frontend op `9080`; softwarefactory HTML-dashboard op `8080`. Klopt met de docs.
- **Config-defaults** (`technical-spec.md`, `secrets-local.md`): `OrchestratorSettings.fromEnvironment`
  bevestigt alle gedocumenteerde defaults, incl. `SF_POLL_INTERVAL_MS=1000`,
  `SF_POLL_INTERVAL_IDLE_MS=1000`, parallel-caps, `SF_MAX_DEVELOPER_LOOPBACKS=5`,
  `SF_MAX_TEST_CHAIN_RESETS=3`, `SF_AGENT_HARD_TIMEOUT_MINUTES=60`,
  `SF_ACTIVE_PHASE_RECOVERY_DELAY_MS=60000`, `SF_COST_MONITOR_INTERVAL_MS=300000`,
  `SF_CREDITS_PAUSE_DEFAULT_MINUTES=30`. Workspace-cleanup-vlaggen kloppen.
- **Agent-rollen**: `core.AgentRole` bevat REFINER, PLANNER, DEVELOPER, REVIEWER, TESTER, SUMMARIZER,
  DOCUMENTER, ASSISTANT, COST_MONITOR, ORCHESTRATOR. De ketenvolgorde
  `development → review → test → summary → documentation → manual-approve → merge → deploy` in
  functional-spec/technical-spec klopt.
- **CLI** (`development.md`, `deployment.md`): `./factory local-db` en `mvn -f softwarefactory/pom.xml
  spring-boot:run` bestaan in het `factory`-script. `specs/specs.md` bestaat (referentie in README/
  development klopt).
- **Verplichte secrets** (`secrets-local.md`, `technical-spec.md`): 5 REQUIRED_KEYS, `SF_YOUTRACK_PROJECTS`
  optioneel. Klopt.
- **SF-335/SF-213/SF-244/SF-200/SF-350/SF-352/SF-565 secties**: steekproefsgewijs consistent met de code.

## Gevonden discrepanties + aanpassingen (functional-spec.md)

1. **Poll-filter `Stage = Develop` is verouderd.** `YouTrackClient.findWorkIssues`
   (`softwarefactory/.../youtrack/clients/YouTrackClient.kt:75-95`) query't álle issues van de
   geconfigureerde projecten (`project: <key> sort by: updated desc`) en filtert alleen op
   `AI-supplier` niet leeg/niet `none`. Er is geen `Stage`-veldfilter meer; de fase-gate in de
   orchestrator (lege `AI Phase` = niet starten, `start` = oppakken) bepaalt de pickup (codecommentaar
   regel 78-79 en 492-493 + de schema-bootstrap die `Stage` niet meer vereist, bevestigen dit). De
   docs-zin is herschreven naar dit gedrag.

2. **AI-supplier-waarden incompleet.** functional-spec noemde alleen `mock` en `claude`. De code
   ondersteunt méér: `TrackerCommentParser.supplierPattern` en `FactoryDashboardViews.AI_SUPPLIER_OPTIONS`
   (`none`, `mock`, `claude`, `openai`, `copilot`, `microsoft`); `DockerAgentRuntime` mapt `openai`→Codex
   CLI en `copilot`/`microsoft`→GitHub Copilot CLI; `AiRouting` routeert `copilot`/`microsoft` naar de
   copilot-bucket. Toegevoegd: vermelding van `openai` (Codex), `copilot`/`microsoft` (GitHub Copilot),
   `none`, en de volledige dashboard-keuzelijst.

## Bewust niet gewijzigd (met reden)

- **Code-commentaar-inconsistentie** in `YouTrackClient.kt`: regel 78 zegt "Geen work-tags meer"
  terwijl regel 492-493 nog naar tags `ai-refinement`/`ai-development` verwijst. Dit is in-code
  commentaar (geen documentatie onder `docs/`) én broncode → buiten scope (geen code-wijzigingen).
  Hier gemeld als bevinding.
- **functional-spec genummerde flow (stap 1-6)** noemt de PLANNER-rol niet expliciet. Dit is een
  bewuste high-level samenvatting (planning hoort bij de refine-fase); de gedetailleerde
  ketenvolgorde verderop in het document is correct. Niet aangepast om geen stijl-/herschrijfoefening
  op correcte tekst te doen.
- **UX-wireframes (`ux/**`)**: niet aangeraakt — geen evidente onjuistheid geconstateerd; alleen aan
  te raken bij evidente fouten.

## Tests

Geen broncode of build/config gewijzigd (diff = uitsluitend `docs/factory/functional-spec.md` +
deze worklog), dus bestaande build/tests blijven ongewijzigd groen; geen testaanpassingen nodig of
mogelijk (docs-only). Zelf-review bevestigt via `git status` dat de diff alleen documentatie +
worklog raakt.

## Review (SF-609, reviewer)

[info] Diff = uitsluitend `docs/factory/functional-spec.md` + dit worklog; geen broncode/build/config.
Voldoet aan scope (acceptance: docs-only).
[info] Correctie 1 geverifieerd tegen `YouTrackClient.findWorkIssues` (regel 75-91): query't per
project `project: <key> sort by: updated desc` en filtert client-side op `aiSupplier !in {null,"","none"}`;
geen `Stage`-filter meer. `SF_YOUTRACK_PROJECTS` bestaat in `SecretsEnvLoader`; leeg = alle
niet-gearchiveerde projecten. Klopt.
[info] Correctie 2 geverifieerd: `FactoryDashboardViews.AI_SUPPLIER_OPTIONS` =
`none/mock/claude/openai/copilot/microsoft`; `DockerAgentRuntime` mapt `openai`→Codex en
`copilot`/`microsoft`→Copilot; `TrackerCommentParser.supplierPattern` dekt dezelfde set. Klopt.
[info] Bewust-niet-gewijzigd punten (code-commentaar-inconsistentie, PLANNER in high-level flow,
UX-wireframes) correct gemotiveerd en buiten scope.

Akkoord: review-bevindingen geen, wijziging coherent, in scope en spec-consistent.

## Test (SF-610, tester) — TEST-REJECTED

Geverifieerd tegen de code (geen code/docs gewijzigd; alleen deze worklog-notitie toegevoegd).

[ok] Scope: `git diff main...HEAD` raakt uitsluitend `docs/factory/functional-spec.md` + dit
worklog. Geen broncode/build/config. Voldoet aan AC-1.
[ok] Correctie 1 (poll-filter): `YouTrackClient.findWorkIssues` (regel 75-95) query't per project
`project: <key> sort by: updated desc` en filtert client-side op `aiSupplier !in {null,"","none"}`;
geen `Stage = Develop`-filter. `SF_YOUTRACK_PROJECTS` (leeg = alle projecten) bevestigd in
`SecretsEnvLoader`. Doc-tekst klopt.
[ok] Dashboard-keuzelijst `none/mock/claude/openai/copilot/microsoft`: bevestigd
(`FactoryDashboardViews.AI_SUPPLIER_OPTIONS`, `TrackerCommentParser.supplierPattern`,
`YouTrackClient` schema-bootstrap). Klopt.

[BUG] **Onjuiste bewering: `microsoft` is GEEN GitHub Copilot CLI.** De toegevoegde zin in
`functional-spec.md` (regel ~26) stelt: "`openai` (Codex CLI) en `copilot`/`microsoft`
(GitHub Copilot CLI) ondersteund". De feitelijke supplier→client-selectie zit echter in de
agentworker `AiClientFactory.create` (`agentworker/.../agent/AiClient.kt:93-108`):
  - `"copilot"`, `"github"` → `CopilotAiClient` (GitHub Copilot CLI) ✓
  - `"microsoft"` → `NotImplementedAiClient(supplier)` — expliciet NIET geïmplementeerd.
`AiRouting.bucket` routeert ook alleen `copilot`/`github` naar de copilot-bucket; `microsoft`
valt in de `else`-tak (model = null). De dashboard-comment
(`FactoryDashboardViews.kt:1699`) bevestigt dit: "model-override (microsoft/none) ... krijgen
alleen 'automatisch'". De documentatie beschrijft hiermee functionaliteit die in de code niet
bestaat (microsoft = Copilot CLI) en schendt AC: "De documentatie ... beschrijft de
functionaliteit die in de code aanwezig is". Dit is precies het type discrepantie dat deze
story moet wegnemen, niet introduceren.

Voorstel voor developer: beschrijf `microsoft` niet als (werkende) GitHub Copilot CLI. Opties:
óf `microsoft` weglaten uit de "ondersteund"-opsomming en alleen als keuzelijst-waarde noemen
met de kanttekening dat die naar een niet-geïmplementeerde supplier mapt, óf de groepering
splitsen zodat alleen `copilot`/`github` als GitHub Copilot CLI staan.

Resultaat: test-rejected — terug naar developer voor correctie van de microsoft-bewering.
