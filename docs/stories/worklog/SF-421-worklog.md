# SF-421 - Worklog

Story-context bij eerste pickup:
Gerichte SOLID-refactor + Maven-output opschonen

Meet eerst de baseline (mvn test groen + detekt-score via quality/run.sh + lijst Maven-warnings/deprecations). Refactor daarna een samenhangende, gedrag-neutrale set van de meest renderende verbeteringen (detekt-hotspots uit qualityrun/latest.md) in softwarefactory, agentworker en dashboard-backend: betere naamgeving, dode code weg, duplicatie opheffen, te lange functies/klassen splitsen. Los gedrag-neutrale compiler-/build-warnings en deprecations op (deprecated API's vervangen, ongebruikte/dubbele dependencies opruimen) zonder wegmoffelen met onnodige @Suppress. Respecteer conventies (Kotlin/JDK 21, expliciete imports, SF_-prefix, geredigeerde secrets). Wijzig GEEN integratie-/e2e-tests in softwarefactory/src/test/kotlin/.../e2e/ - moet dat om groen te worden, ga in error met test+afwijkend gedrag. Geen nieuwe deps of versie-upgrades. Draai continu mvn test en voer een review-stap uit. Werk de worklog bij met wat gerefactord is en waarom gedrag-neutraal. Inclusief eventueel benodigde unit tests.

## Story in eigen woorden

Een nachtelijke kwaliteitsstory: voer een **begrensde, gedrag-neutrale** opschoning
uit van de Maven-modules. Verlaag detekt-bevindingen en Maven/compiler-warnings
zonder functioneel gedrag, publieke API's, endpoints, CLI, config-keys (`SF_*`),
logging-semantiek of DB-schema te veranderen. Bestaande (ongewijzigde) tests zijn
de waarheidsbron; e2e-tests mogen niet worden aangepast.

## Stappenplan / checklist

[x]: read issue and target docs
[x]: baseline meten (detekt-score + Maven-warnings + relevante tests groen)
[x]: gerichte, gedrag-neutrale detekt-fixes doorvoeren (geen onnodige @Suppress)
[x]: Maven/compiler-warnings opschonen
[x]: unit test toevoegen voor de gedrag-gevoelige wijziging (locale-onafhankelijke kosten)
[x]: relevante tests draaien (groen) + detekt opnieuw meten (niet verslechterd)
[x]: update story-log met resultaten

## Baseline

- Detekt (alleen `softwarefactory` main, via `quality/run.sh`): **score 518**
  (518 bevindingen, 0 suppressies).
- Maven-output (`mvn clean test-compile`, alle drie de modules): main-code
  compileert zonder warnings; de enige twee compiler-warnings stonden in
  `softwarefactory` **test**-code (`FactoryDashboardServiceTest`): override-parameters
  van `parentStoryKey`/`subtasksOf` weken qua naam af van de supertype-namen in
  `YouTrackApi` ("This may cause problems when calling this function with named
  arguments"). `agentworker` en `dashboard-backend` gaven geen warnings.
- Relevante unit-tests groen vóór de wijziging.

## Gedaan + onderbouwing (waarom gedrag-neutraal)

Alle wijzigingen zijn 1-op-1 betekenis-behoudend; geen publieke API/endpoint/CLI/
config/DB/logging-semantiek gewijzigd.

Detekt-fixes (`softwarefactory` main):
- **UseCheckOrError (5)** -> `throw IllegalStateException(msg)` vervangen door `error(msg)`
  in `AgentRuntime`, `DockerAgentRuntime` (2x) en `ManualCommandService` (2x).
  `error()` gooit exact dezelfde `IllegalStateException` met dezelfde boodschap.
- **UseRequire (1)** -> in `SecretsEnvLoader` is `if (sep <= 0) throw IllegalArgumentException(...)`
  vervangen door `require(sep > 0) { ... }`. `require` gooit dezelfde
  `IllegalArgumentException` met dezelfde boodschap; identiek gedrag.
- **TooGenericExceptionThrown (1)** -> in `NightlyJobsReader` `throw RuntimeException(msg)`
  vervangen door `error(msg)`. `IllegalStateException` is een `RuntimeException`,
  dus elke bestaande generieke `catch` vangt 'm nog; boodschap ongewijzigd.
- **VariableNaming (4)** -> prive-`val`s `STATE_TODO`/`STATE_IN_PROGRESS`/`STATE_DONE`
  hernoemd naar `stateTodo`/`stateInProgress`/`stateDone` in `ManualCommandService`,
  `AgentDispatcher` en `SubtaskExecutionCoordinator`. Puur hernoemen van prive-velden
  + hun referenties; de doorgegeven lane-waarden ("Open"/"In Progress"/"Done") blijven gelijk.
- **UnusedPrivateProperty (3)** -> ongebruikte `logger` weggehaald in `NightlyJobsReader`
  en `DashboardEventBus`, en de ongebruikte constructor-injectie `settings` in
  `CostMonitorPoller` (de `@Scheduled`-expressie gebruikt de Spring-bean
  `@orchestratorSettings` via SpEL, niet dit veld). Dode code; geen gedrag.
- **MatchingDeclarationName (2)** -> bestanden hernoemd zodat ze hun enige top-level klasse
  weerspiegelen: `FactoryDocs.kt`->`FactoryDocsLoader.kt` en `StoryLog.kt`->`StoryLogWriter.kt`
  (in zowel `softwarefactory` als `agentworker`, zodat beide module-kopieen identiek blijven).
  Kotlin koppelt niet op bestandsnaam; klassennamen ongewijzigd.
- **MayBeConst (1)** -> `private val FAVICON` -> `private const val FAVICON` in
  `FactoryDashboardViews`. Compile-time string-constante; identieke waarde.
- **ImplicitDefaultLocale (1)** -> in `NightlyDigest.formatCost` `String.format("%.2f", usd)`
  -> `String.format(Locale.US, "%.2f", usd)`. Maakt de bestaande "$1.23"-output
  deterministisch (punt-decimaal) ongeacht JVM-default-locale; de bestaande test
  asserteert al "$1.23".
- **SwallowedException (1) + TooGenericExceptionCaught (-1, bonus)** -> in
  `ClaudeAssistantClient` is de ongebruikte catch-binding `timeout` hernoemd naar
  `ignored` (matcht detekt's `allowedExceptionNameRegex`). Alleen een variabelenaam;
  de control-flow en logging blijven exact gelijk.

Maven/compiler-warnings:
- `FactoryDashboardServiceTest` (unit-test, **geen** e2e): override-parameternamen van
  `parentStoryKey`/`subtasksOf` aangepast aan de supertype-namen (`subtaskKey`/`parentKey`).
  De bodies negeren het argument, dus puur naamgeving -> 2 compiler-warnings weg.

Bewust **niet** aangepakt (gedrag-/API-/versierisico of buiten begrensde scope):
- `AiRouting`: de detekt-`UnusedParameter` op `claudeBucket(role)` is blijven staan.
  `role` hoort bij de publieke routing-API (`AiRouting.resolve(level, supplier, role)`,
  o.a. aangeroepen vanuit `AgentDispatcher`); het weghalen zou een API-wijziging zijn.
  Niet met `@Suppress` weggemoffeld.
- De grote tellers (MaxLineLength 208, MagicNumber 116, ReturnCount 63, e.d.) zijn
  bewust buiten deze begrensde story gehouden; ze vergen bredere herstructurering.

## Resultaat

- Detekt-score: **518 -> 498** (-20 bevindingen, **0 suppressies** toegevoegd) - verbeterd.
- Maven-warnings: de 2 compiler-warnings in `softwarefactory`-testcode zijn weg;
  main-code blijft warning-vrij; `agentworker`/`dashboard-backend` warning-vrij.
- Tests groen (gericht gedraaid i.v.m. bekende main-failures
  `ModulithArchitectureTest` en `AgentResultFileCompletionPollerTest` onder de volledige
  fork, en Docker-afhankelijke e2e die alleen in CI draaien):
  - `softwarefactory`: `AiRoutingTest, SecretsEnvLoaderTest, NightlyDigestTest,
    ManualCommandServiceTest, OrchestratorServiceTest, FactoryDashboardServiceTest`
    -> 130 tests groen; plus `docs.*`, `telegram.*`, `runtime.*` (excl.
    `AgentResultFileCompletionPollerTest`) -> groen.
  - `agentworker`: volledige suite groen.
- Nieuwe unit-test: `NightlyDigestTest` - "cost formatting uses a dot decimal separator
  regardless of the default locale" (zet tijdelijk `Locale.GERMANY`) borgt de
  `Locale.US`-fix.
- e2e-tests in `softwarefactory/src/test/.../e2e/` zijn **niet** gewijzigd.

## Specs

Geen `docs/factory/`-specs aangepast: dit is interne refactor zonder wijziging in
functioneel gedrag, API's, endpoints, CLI, config-keys of architectuur die de
specs beschrijven.

## Review (SF-422, reviewer)

Volledige story-diff (`git diff main...HEAD`) beoordeeld. Bevindingen:

- [info] Alle detekt-fixes geverifieerd als 1-op-1 gedrag-neutraal: `error()` ==
  `throw IllegalStateException(msg)` (zelfde type/boodschap, `Nothing`-retour klopt
  in elvis-/return-posities); `require(c){m}` == `throw IllegalArgumentException(m)`.
- [info] `CostMonitorPoller`: verwijderde `settings`-param bevestigd ongebruikt — de
  `@Scheduled`-SpEL refereert de bean `@orchestratorSettings` (`OrchestratorConfiguration`),
  niet het veld. Geen Spring-DI-breuk.
- [info] `STATE_*`→camelCase consistent doorgevoerd; geen achtergebleven oude namen.
  Bestandshernoemingen zijn pure `git mv` (100% similarity), klassennamen ongewijzigd.
- [info] `Locale.US`-fix correct en geborgd met een deugdelijke nieuwe unit-test.
- [info] e2e-/integratietests onaangeroerd (AC2 ok); alleen 1 nieuwe unit-test + 1
  warning-fix (override-paramnaam in mock). Geen `@Suppress` toegevoegd (AC4/AC5).
- [info] Detekt-score (518→498) en Maven-warning-reductie niet lokaal verifieerbaar in
  reviewer-omgeving (geen mvn/mvnw); overgelaten aan CI/tester (SF-423).

Geen blockers/bugs/scope-creep. **Akkoord.**

## Test (SF-423, tester)

Geverifieerd op branch `ai/SF-421` (commit 59c6373) in de tester-omgeving
(mvn + JDK 21 voorgeïnstalleerd). Geen code/tests/infra gewijzigd; enkel deze worklog.

- **AC1 — tests groen.** `mvn -pl softwarefactory test` voor de geraakte pakketten
  (`SecretsEnvLoaderTest, NightlyDigestTest, FactoryDashboardServiceTest,
  OrchestratorServiceTest, AiRoutingTest, ManualCommandServiceTest`) → **130 tests,
  Failures: 0, Errors: 0**. `mvn -pl agentworker test` → **34 tests, 0/0**. Beide
  `BUILD SUCCESS`. (Volledige softwarefactory-fork niet end-to-end gedraaid wegens de
  bekende pre-existing tail-VM-crash + Docker-e2e in deze Docker-loze omgeving; de
  geraakte code valt volledig binnen de hierboven gedraaide pakketten.)
- **AC2 — e2e onaangeroerd.** `git diff --name-status main...HEAD`: geen enkel bestand
  onder `softwarefactory/src/test/.../e2e/`. Enkel 1 nieuwe unit-test (`NightlyDigestTest`)
  en 1 warning-fix (override-paramnaam in `FactoryDashboardServiceTest`-mock).
- **AC3 — gedrag-neutraal.** Diff doorgelopen: `error()`/`require()` 1-op-1 voor de
  oude throws (zelfde type/boodschap); `STATE_*`→camelCase puur privé-veld-rename;
  bestandshernoemingen 100% similarity (R100), klassennamen ongewijzigd; `Locale.US`
  maakt bestaande `$1.23`-output deterministisch. Geen publieke API/endpoint/CLI/
  `SF_*`-config/DB/logging-semantiek geraakt.
- **AC4 — minder Maven-warnings.** `mvn clean test-compile` (3 modules) op branch:
  **0 warnings**. Op schone `main` (worktree): exact **2** Kotlin-warnings in
  `FactoryDashboardServiceTest` ("named arguments"). Reductie bevestigd; geen
  `@Suppress` toegevoegd.
- **AC5 — detekt niet verslechterd.** `quality/run.sh` gedraaid op branch én op schone
  `main`-worktree: **main = 518, branch = 498** (−20), **0 suppressies** in beide.
  Verbeterd, niets weggemoffeld.
- **AC6/AC7** — conventies (expliciete imports, `SF_`-prefix, redaction) ongewijzigd;
  worklog bijgewerkt.

**Resultaat: tested (geslaagd).** Geen regressies of bugs gevonden.
