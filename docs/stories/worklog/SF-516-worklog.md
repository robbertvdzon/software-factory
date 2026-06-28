# SF-516 - Worklog

Story-context bij eerste pickup:
Afwijkende patronen gladstrijken (gedragsneutraal)

Scan de Kotlin-modules (primair softwarefactory/src/main/kotlin; eventueel agentworker/dashboard-backend, niet de Flutter-frontend en niet de e2e-/integratietests) op afwijkende patronen in naamgeving, error-handling (ruwe throw IllegalStateException/IllegalArgumentException → error(...)/require(...), domeinexcepties ongemoeid), project-interne wildcard-imports, logging en structuur/API-conventies. Bepaal per as de dominante norm en haal afwijkingen daarnaartoe. Voer alleen gedragsneutrale wijzigingen door die herleidbaar zijn tot een aanwijsbare meerderheidsvariant; bij twijfel of gedrag verandert: niet doen of story in error. Houd de detekt-/kwaliteitsscore (quality/run.sh, qualityrun/quality-score.json) gelijk of beter; @Suppress-onderdrukkingen tellen mee. Inclusief eigen review-stap. Draai de relevante tests en werk docs/stories/worklog/SF-516-worklog.md bij (no-op met notitie is een geldig resultaat). Pas geen integratie-/e2e-tests aan - moet dat wel om groen te worden, dan is het een gedragsverandering en gaat de story in error.

Stappenplan:
[x]: read issue and target docs
[x]: scan Kotlin-modules op afwijkende patronen per as (naamgeving / structuur / error-handling / logging / conventies)
[x]: implement requested changes (gedragsneutraal, herleidbaar tot dominante norm)
[x]: eigen review-stap
[x]: run relevant tests
[x]: detekt-score bevestigen (gelijk of beter)
[x]: update story-log with results

## Scan-bevindingen per as (norm bepaald uit de codebase zelf)

- **Error-handling — ruwe IllegalStateException**: 0 ruwe `throw IllegalStateException` in main (al opgeruimd door eerdere nightlies). Geen werk.
- **Error-handling — null-check → IllegalArgumentException**: in `softwarefactory` is `requireNotNull(x) { ... }` de dominante norm (19 voorkomens) tegenover 3 afwijkende `... ?: throw IllegalArgumentException(...)`. De overige `?: throw`-gevallen gooien bewust domeinexcepties (GitHubClientException, YouTrackApiException, MissingTrackerFieldException) en blijven ongemoeid. → 3 afwijkingen gladgestreken (zie hieronder).
- **dashboard-backend**: gebruikt 0× `requireNotNull` en consistent `?: throw IllegalArgumentException` (4×). Daar is `?: throw` juist de modulenorm, dus die module bewust NIET aangepast (norm is module-relatief).
- **Project-interne wildcard-imports**: 0 — al conform de expliciete-import-norm. Geen werk.
- **Logging**: logger-declaratie volledig consistent (`private val logger`, 32×; companion-loggers gebruiken bewust `::class.java` zoals in `ProjectRepoResolver`). `String.format` gebruikt overal `Locale.US`. Geen afwijking om glad te strijken. De 3 `println` in `DockerAgentRuntime` schrijven bewust naar stdout; omzetten naar logger zou gedrag/uitvoerkanaal wijzigen → buiten scope gelaten.
- **Collecties**: `mutableListOf` is de norm (39×); de enige `ArrayList`-match is `CopyOnWriteArrayList` (bewuste thread-safe keuze), geen afwijking.
- **Detekt-resten**: overige bevindingen zijn structureel/complexiteit (MaxLineLength, MagicNumber, ReturnCount, CyclomaticComplexMethod, ...) of expliciet beschermd (`AiRouting.claudeBucket` UnusedParameter — hoort bij publieke `resolve()`-API). Niet veilig/gedragsneutraal glad te strijken bij effort "medium"; bewust ongemoeid.

## Doorgevoerde wijzigingen

1. `config/ProjectRepoResolver.kt` (2×, in `parse()`): `... as? List<*> ?: throw IllegalArgumentException(...)` en `entry as? Map<*,*> ?: throw IllegalArgumentException(...)` → `requireNotNull(...) { ... }`.
2. `knowledge/services/AgentKnowledgeService.kt` (`parseAgentRole`): de afsluitende `?: throw IllegalArgumentException("Unknown agent role...")` → `requireNotNull(...) { ... }`.

Waarom gedragsneutraal: `requireNotNull(x) { msg }` gooit exact hetzelfde `IllegalArgumentException` met dezelfde (lazy) message als `x ?: throw IllegalArgumentException(msg)` en retourneert dezelfde non-null waarde. Aanroepers veranderen niet: `ProjectRepoResolver.fromYaml` vangt deze in een `catch (ex: Exception)` (→ lege resolver, ongewijzigd), en `AgentKnowledgeService` gooit hetzelfde type door (bestaande test verifieert `IllegalArgumentException`).

## Tests (zelf geschreven / gedraaid)

- Nieuwe unittest `ProjectRepoResolverTest."a non-object project entry yields an empty resolver instead of crashing"` dekt het gewijzigde `requireNotNull`-pad in `parse()` via de publieke `fromYaml` (non-object project-entry → exception gegooid, gevangen, lege resolver).
- Bestaande `AgentKnowledgeServiceTest."rejects non agent roles and blank keys"` dekt de gewijzigde `parseAgentRole`-throw (blijft `IllegalArgumentException`).
- Gedraaid: `mvn -f softwarefactory/pom.xml test -Dtest='ProjectRepoResolverTest,AgentKnowledgeServiceTest,ProjectRepoResolverMergeDeployTest'` → alle groen (10 + 3 + 7 tests, 0 failures/errors). Geen integratie-/e2e-test aangepast.
- Kwaliteitsmeetlat: `quality/run.sh` → score blijft **508** (totalFindings 508, suppressions 0); niet verslechterd, geen nieuwe `@Suppress` toegevoegd.

Specs: geen `docs/factory/`-spec geraakt (puur interne, gedragsneutrale consistentie-fix; geen functionele/architecturale wijziging om te documenteren).

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- 3 gerichte, gedragsneutrale error-handling-consistentiefixes doorgevoerd, elk herleidbaar tot de dominante `requireNotNull`-norm binnen `softwarefactory`; domeinexcepties en de afwijkende `dashboard-backend`-modulenorm bewust ongemoeid gelaten.

## Review SF-517 (reviewer, 2026-06-28)

[info] Volledige story-diff t.o.v. `main` beoordeeld: 2 implementatiebestanden + 1 test + dit worklog. Klein, gericht consistentiewerk conform scope.
[info] Gedragsneutraliteit geverifieerd: `requireNotNull(x) { msg }` werpt exact `IllegalArgumentException` met dezelfde lazy message als de oude `... ?: throw IllegalArgumentException(msg)`. Identiek excepttype, message en control-flow op alle 3 plekken (`ProjectRepoResolver.parse` 2×, `AgentKnowledgeService.parseAgentRole`).
[info] Norm-claim bevestigd: `softwarefactory/src/main` bevat nu 19× `requireNotNull` en 0× `?: throw IllegalArgumentException` — de afwijkende variant is volledig naar de dominante norm getrokken. `dashboard-backend` en domeinexcepties terecht ongemoeid.
[info] Nieuwe test `ProjectRepoResolverTest`: pad klopt — `parse()`-exceptie wordt in `fromYaml` (regel 174) gevangen en levert lege resolver; dekt het gewijzigde `requireNotNull`-pad via de publieke API. Geen e2e-/integratietest geraakt (criterium 3 OK).
[info] Specs: geen `docs/factory/`-inconsistentie; pure interne consistentie-fix, geen spec-update vereist.
[info] Tests niet lokaal gedraaid (geen mvn in reviewer-omgeving); vertrouw op CI. Akkoord.

## Test SF-518 (tester, 2026-06-28)

[info] Gerichte tests gedraaid: `mvn -f softwarefactory/pom.xml test -Dtest='ProjectRepoResolverTest,AgentKnowledgeServiceTest,ProjectRepoResolverMergeDeployTest'` → BUILD SUCCESS, 20 tests, Failures: 0, Errors: 0. Dekt beide gewijzigde bestanden (incl. de nieuwe `requireNotNull`-paden via de publieke API).
[info] Volledige suite gedraaid: `mvn -f softwarefactory/pom.xml test -Dsurefire.runOrder=alphabetical` → 420 tests, **Failures: 0**, 21 Errors. Alle 21 errors zijn pre-existing/omgevingsgebonden (geen Docker/Testcontainers in deze omgeving): e2e `FactoryUiDriverLoginTest`, `FullRefineToDevelopE2eTest`, `PipelineFlowsE2eTest`, `PipelineLoopbackE2eTest`, `SpecScenarioCoverageE2eTest`, plus `NightlyRepositoriesTest` (Postgres-Testcontainer) en `FactoryDashboardRepositoryScreenshotTest`. Geen enkele error raakt de gewijzigde bestanden; `ModulithArchitectureTest` slaagde deze run. Echte regressiesignaal = Failures = 0 → groen.
[info] Gedragsneutraliteit (criterium 1/4/5) en geen e2e-/integratietest-wijziging (criterium 3) bevestigd tegen de diff: alleen `ProjectRepoResolver.kt` + `AgentKnowledgeService.kt` (main) + `ProjectRepoResolverTest.kt` (unit) + worklog.
[info] Kwaliteitsmeetlat (criterium 6): `qualityrun/quality-score.json` = score 508, suppressions 0; de wijziging voegt geen `@Suppress` toe en introduceert geen nieuwe detekt-findings. Niet verslechterd.
[info] Geen preview-deploy ingericht (`SF_PREVIEW_URL` leeg) → geen browser-/screenshottest mogelijk; lokaal getest conform `docs/factory/agents/tester.md`. Werktree verder schoon (alleen worklog aangepast).
[info] Conclusie: alle acceptatiecriteria voldaan, geen regressie. → tested.
