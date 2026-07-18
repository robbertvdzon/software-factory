# SF-1068 - Worklog

Story-context bij eerste pickup:
Diagnosticeer en fix regressie in Projects-productieversie/sync-status

Reproduceer het defect (unit-/servicetest tegen projectsOverview() met een rest-restart-config) om vast te stellen welke laag faalt: config-lookup (ProjectConfiguration.deployConfigFor/fromYaml), HTTP-fetch (ProjectDeployClient.fetchVersionBody), JSON-parsing (DashboardQueryService.parsePrdVersionJson) of sync-berekening (buildStatusFor/lastCompletedMainRun). Leg de root cause vast in worklog/PR-beschrijving. Implementeer de gerichte fix in de betreffende laag zonder de API-contractvelden (prdVersion, hasDeployConfig, buildStatus) of overige Projects-schermonderdelen te wijzigen. Voeg een regressietest toe die het faalscenario reproduceert en de fix verifieert, naast de bestaande FactoryDashboardServiceTest/DashboardQueryService-tests en projects_screen_test.dart. Als de oorzaak buiten codescope ligt (bv. ontbrekende/lege omgevingsconfig), meld dit expliciet als blokkade i.p.v. code te patchen.

Stappenplan:
[x]: read issue and target docs
[x]: reproduceer het defect met een servicetest tegen projectsOverview() (rest-restart-config)
[x]: root cause vastgesteld
[x]: gerichte fix geïmplementeerd
[x]: regressietest toegevoegd die het faalscenario reproduceert en de fix verifieert
[x]: mvn verify (volledig vangnet vanaf repo-root) groen
[x]: update story-log met resultaten

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## Diagnose (SF-1069)

Eerste reproductiepoging: een servicetest die `projectsOverview()` aanroept met een project met
een geldige `DeployConfig.RestRestart` en een lokale `HttpServer` als `/api/version`-doel
(`DashboardQueryServiceTest#projectsOverview toont prdVersion en hasDeployConfig ...`) **slaagde
meteen**. Dat sluit config-lookup (`ProjectConfiguration.deployConfigFor`), de HTTP-fetch zelf
(`ProjectDeployClient.fetchVersionBody`) en de JSON-parsing (`parsePrdVersionJson`) uit als
geïsoleerde oorzaak — die lagen werken op zichzelf correct.

Root cause zit niet in één van de vier kandidaat-lagen uit de story-omschrijving, maar in hoe ze
*samen* draaien: `DashboardQueryService.projectsOverview()` fan-out't per project drie dingen
parallel via `CompletableFuture.supplyAsync`/`.thenApplyAsync` **zonder expliciete `Executor`**:
1. de prdVersion-HTTP-fetch (bestond al sinds SF-890),
2. de GitHub Actions-call (`GitHubActionsClient.latestRunsPerWorkflow`/`defaultBranch`, sinds SF-876),
3. de live-component-status via `KubectlDeploymentStatusProbe` (tot 2 blocking `kubectl`-subprocessen
   per component, sinds SF-771), gechained op (2) met `.thenApplyAsync`.

Zonder expliciete executor gebruiken alle drie stilzwijgend `ForkJoinPool.commonPool()` — een
process-breed gedeeld pool, gedimensioneerd op CPU-bound werk (`availableProcessors() - 1`
threads), niet bedoeld voor blocking I/O. Naarmate (2) en (3) er later bijkwamen, is het aantal
gelijktijdige blocking taken op datzelfde gedeelde pool flink gegroeid (elke project met live-
componenten kan er 1 (build) + tot 2×N (kubectl) opeisen). Zodra dat pool verzadigd raakt, wacht
de prdVersion-taak in de wachtrij en overschrijdt de buitenste `future.get(PRD_VERSION_TIMEOUT_MS =
3000ms)`-deadline (`DashboardQueryService.kt`) — voor alle projecten tegelijk, ongeacht of hun
eigen config/HTTP/JSON-pad zelf in orde is. Dat verklaart exact het gerapporteerde symptoom
("voor **alle** projecten" een vraagteken/"Geen productieversie beschikbaar", regressie t.o.v.
eerder werkende staat: de losse features (SF-890 prdVersion, SF-876 builds, SF-771 live-
components) waren elk op zich correct, maar zijn nooit samen belast getest op eenzelfde gedeeld
thread-pool).

Bevestigd met een gerichte regressietest die het `ForkJoinPool.commonPool()` bewust verzadigt
(`parallelism + 2` blocking taken) vlak vóór `projectsOverview()` aangeroepen wordt: tegen de
ongewijzigde code faalt die test deterministisch (`prdVersion` blijft `null`, exact het
symptoom); met de fix slaagt hij.

## Fix

`DashboardQueryService` krijgt een eigen dedicated `ExecutorService`
(`projectsOverviewExecutor`, cached thread pool met daemon-threads) en geeft die expliciet mee aan
alle drie `supplyAsync`/`thenApplyAsync`-calls in `projectsOverview()` (prdVersion-fetch,
GitHub Actions-fetch, live-component-fetch). Zo concurreren deze blocking calls niet meer met
de rest van het proces (en elkaar) op het gedeelde `ForkJoinPool.commonPool()`. Geen wijziging aan
de API-contractvelden (`prdVersion`, `hasDeployConfig`, `buildStatus`) of aan `downloads()`/
`builds()` (die blijven — buiten scope van deze story — nog wel op `commonPool()` draaien).

## Tests

- `DashboardQueryServiceTest#projectsOverview toont prdVersion en hasDeployConfig voor een
  project met een geldige rest-restart-config` — basis end-to-end-servicetest (nieuw), bewijst dat
  de happy-path werkt.
- `DashboardQueryServiceTest#projectsOverview blijft UNAVAILABLE tonen voor een project zonder
  deploy-config` — bewaakt AC2 (bestaand gedrag zonder deploy-config blijft ongewijzigd).
- `DashboardQueryServiceTest#projectsOverview blijft prdVersion ophalen ook als de gedeelde
  ForkJoinPool commonPool verzadigd is` — de eigenlijke regressietest: reproduceert het
  faalscenario (verzadigd `commonPool()`) en verifieert de fix. Handmatig bevestigd dat deze test
  faalt tegen de ongewijzigde productiecode (prdVersion == null) en slaagt met de fix.
- Volledig vangnet: `mvn verify` vanaf de repo-root, exitcode 0 / 0 failures / 0 errors (zie
  build-log; inclusief Testcontainers-e2e).

Geen wijziging nodig aan `docs/factory/*` (functional-spec.md/technical-spec.md/ux/screens/
projects.md): het gedocumenteerde contract en gedrag van de Projects-pagina blijven ongewijzigd,
alleen de interne thread-pool-toewijzing binnen `projectsOverview()` is aangepast.

## Review (SF-1069)

- Root cause is aannemelijk en concreet onderbouwd: `projectsOverview()` gebruikte
  `CompletableFuture.supplyAsync`/`.thenApplyAsync` zonder expliciete executor voor de
  prdVersion-fetch, GitHub Actions-fetch én live-component-fetch (kubectl-subprocessen), die
  daardoor alle drie op het gedeelde `ForkJoinPool.commonPool()` draaiden. Bevestigd in code:
  `future.get(PRD_VERSION_TIMEOUT_MS = 3000ms)` (DashboardQueryService.kt:342) timet uit zodra dat
  pool verzadigd is door de blocking kubectl-calls (DashboardQueryService.kt:414-416).
- Fix is gericht: een dedicated cached `ExecutorService` (`projectsOverviewExecutor`) voor precies
  deze drie fan-out-calls, zonder wijziging aan API-contractvelden of overige Projects-onderdelen.
  `downloads()`/`builds()` blijven bewust op `commonPool()` (buiten scope), correct genoteerd.
- Geverifieerd: `mvn -pl factory-common,softwarefactory -am test-compile` compileert schoon;
  gerichte run `mvn -pl factory-common,softwarefactory -am test -Dtest=DashboardQueryServiceTest`
  → 43 tests, 0 failures, 0 errors (surefire-report). Regressietest verzadigt bewust het
  commonPool en verifieert dat prdVersion toch correct wordt opgehaald; happy-path- en
  UNAVAILABLE-zonder-deploy-config-tests dekken AC1/AC2.
- Geen wijziging aan dashboard-frontend of docs/factory — geen scope creep, consistent met de
  aanname dat dit een pure backend-regressie is.
- Akkoord.

## Test (SF-1070)

- Diff-scope bevestigd: `git diff main...HEAD --name-only` = alleen
  `DashboardQueryService.kt`, `FactoryDashboardServiceTest.kt` en dit worklog. Geen
  wijziging aan dashboard-frontend of docs/factory — geen scope creep.
- Gerichte servicetest 3x achter elkaar gedraaid (`mvn -pl softwarefactory -am test
  -Dtest=DashboardQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false`): telkens
  43 tests, 0 failures, 0 errors. Inclusief de regressietest die het `ForkJoinPool
  .commonPool()`-verzadigingsscenario reproduceert (bewijst dat de dedicated
  `projectsOverviewExecutor` de eigenlijke fix is, niet een toevallig groene run) en de
  happy-path- + UNAVAILABLE-zonder-deploy-config-tests (AC1/AC2).
- Geen preview-omgeving ingericht voor deze factory-repo (SF_PREVIEW_URL leeg) en de
  fix is backend-only (thread-pool-toewijzing, geen UI-wijziging) — geen
  browser/E2E-scenario van toepassing.
- Root cause en fix zijn in het worklog vastgelegd (gedeelde `ForkJoinPool
  .commonPool()`-verzadiging door drie ongeconfigureerde `supplyAsync`/
  `thenApplyAsync`-fan-outs); voldoet aan AC3.
- Volledig vangnet (`mvn verify`) draait automatisch door de harness na deze run;
  niet dubbel lokaal uitgevoerd conform tester-instructie.

{"phase":"reviewed"}
