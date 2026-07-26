# SF-1288 - Worklog

Story-context bij eerste pickup:
Verwijder Merged/Rollout-tabs, endpoints en uitlog-knop

Frontend: verwijder lib/screens/merged_screen.dart en rollout_screen.dart; verwijder de nav-items 'Merged'/'Rollout' uit _secondaryEntries in lib/app_shell.dart (incl. rollout_screen-import); verwijder de uitlog-IconButton in de NavigationRail-trailing en de 'Uitloggen'-ListTile in _openMoreSheet; verwijder het onLoggedOut-parameter van AppShell en de aanroep ervan in lib/main.dart. clearSession() in api_client.dart blijft bestaan (401-afhandeling). Backend (dashboard-backend): verwijder @GetMapping /api/v1/merged en /api/v1/rollout uit BridgeApiController.kt. Backend (softwarefactory): verwijder de 'merged.list'/'rollout.list' cases uit BridgeRequestHandler.kt, DashboardApi.merged()/rollout(), DashboardQueryService-implementaties (incl. rolloutTargetsFor), FactoryDashboardRepository.mergedStoryRuns(), en de modellen MergedPageData/RolloutPageData/RolloutStoryItem in FactoryDashboardModels.kt (mergedStoryKeys() blijft ongewijzigd). Pas de bijbehorende bestaande unit tests aan/verwijder ze (o.a. BridgeApiControllerTest 'rollout vertaalt naar de rollout-list-operatie') als onderdeel van dit ontwikkelwerk. Zorg dat het deployRolloutStage-badge in story_detail_screen.dart ongewijzigd blijft werken.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## Frontend (dashboard-frontend)
- `lib/screens/merged_screen.dart` en `lib/screens/rollout_screen.dart` verwijderd; de `export`
  van `merged_screen.dart` uit `lib/screens/overview_screens.dart` verwijderd (rollout_screen
  werd al niet ge-export't via die barrel, alleen direct geïmporteerd in `app_shell.dart`).
- `lib/app_shell.dart`: `Merged`/`Rollout`-entries uit `_secondaryEntries` verwijderd (incl. de
  `rollout_screen.dart`-import), de uitlog-`IconButton` in de NavigationRail-`trailing` verwijderd
  (en de nu overbodige `trailing`-property zelf), en de 'Uitloggen'-`ListTile` in `_openMoreSheet`
  verwijderd. `onLoggedOut`-parameter van `AppShell` verwijderd.
- `lib/main.dart`: de `onLoggedOut`-callback niet langer doorgegeven aan `AppShell(...)`.
  `clearSession()` in `api_client.dart` is ongewijzigd gelaten (blijft nodig voor de
  401-afhandeling in `_throwOnError`).
- `test/screens/rollout_screen_test.dart` verwijderd (testte het verwijderde scherm). Geen
  test bestond voor `merged_screen.dart` of voor de uitlog-knop/`onLoggedOut` zelf.
- `story_detail_screen.dart` (het `deployRolloutStage`-badge, Story 4) is niet aangeraakt —
  dat is losstaand van de verwijderde Rollout-tab/endpoint.

## Backend (dashboard-backend)
- `BridgeApiController.kt`: `GET /api/v1/merged` en `GET /api/v1/rollout` endpoints verwijderd.
- `BridgeApiControllerTest.kt`: de test `rollout vertaalt naar de rollout-list-operatie`
  verwijderd (geen test voor `/api/v1/merged` bestond).

## Backend (softwarefactory)
- `BridgeRequestHandler.kt`: de `"merged.list"`/`"rollout.list"`-cases uit de
  `dispatchStoryRead`-when verwijderd.
- `DashboardApi.kt` (`DashboardQueries`-interface): `merged()`/`rollout()`-signatures verwijderd.
- `DashboardQueryService.kt`: de `merged()`/`rollout()`-implementaties en de interne
  `rolloutTargetsFor(...)`-helper verwijderd, samen met de nu overbodige
  `deployRolloutStatusApi: DeployRolloutStatusApi`-constructorparameter (en de bijbehorende import).
  `DeployRolloutStatusApi` zelf (interface + implementatie in `StoryDeployReconciler`) blijft
  bestaan: `StoryDeployReconciler.poll()`/`reconcile()` roept `liveStatusFor(...)` intern nog
  rechtstreeks aan voor de eigen `deployedAt`-reconciliatie — dat gebruik is losstaand van de
  (nu verwijderde) Rollout-tab.
- `FactoryDashboardRepository.kt`: `mergedStoryRuns()` verwijderd (alleen gebruikt door de
  verwijderde `merged()`). `runsAwaitingDeployConfirmation(limit)` (de dashboard-repo-variant,
  alleen gebruikt door de verwijderde `rollout()`) eveneens verwijderd — dit is een andere
  methode dan `StoryRunRepository.runsAwaitingDeployConfirmation()` in
  `core/contracts/RunRepositories.kt`, die blijft bestaan en gebruikt wordt door
  `StoryDeployReconciler.poll()`. `mergedStoryKeys()` is ongewijzigd gelaten (blijft gebruikt
  voor de merged-indicator op het stories-overzicht, zoals de story voorschrijft).
- `FactoryDashboardModels.kt`: `MergedPageData`, `RolloutPageData` en `RolloutStoryItem`
  verwijderd, plus de daardoor ongebruikte import van `DeployTargetLiveStatus`.
- Testaanpassingen: `FactoryDashboardServiceTest.kt` (de drie `rolloutTargetsFor`-tests, de
  `rollout degrades gracefully`-test, de nu ongebruikte `uiStoryRun()`-testhelper, en de
  `deployRolloutStatusApi`-parameter/-argumenten in `createQueries`/`createService` +
  ongebruikte imports verwijderd), `BridgeRequestHandlerTest.kt` (`merged.list`-assertie uit de
  gecombineerde routeringstest verwijderd, testnaam aangepast), `BridgeTestFixtures.kt`
  (`deployRolloutStatusApi`-argument + import verwijderd).

## Documentatie
- `docs/factory/ux/README.md`, `screen-map.md`, `dashboard-v2.md` en `screens/merged.md`
  bewust ongewijzigd gelaten: dit zijn expliciet pre-implementatie ontwerpdocumenten voor een
  oudere Spring MVC/Thymeleaf-UI (zie de disclaimer in `ux/README.md`), die al vóór deze story
  niet de huidige Flutter-implementatie beschreven. `functional-spec.md`/`technical-spec.md`
  bevatten geen verwijzingen naar de Merged/Rollout-tabs of de uitlog-knop.

## Verificatie
- `mvn verify` (repo-root): `BUILD SUCCESS`, alle Maven-modules (`factory-contracts`,
  `factory-common`, `softwarefactory`, `agentworker`, `softwarefactory-dashboard-backend`)
  groen, 0 failures/0 errors (softwarefactory-module inclusief de Testcontainers-/failsafe-e2e-
  tests, ~3:35 min; dashboard-backend 47/47 unit tests groen, incl. de aangepaste
  `BridgeApiControllerTest`).
- `flutter analyze` (dashboard-frontend): "No issues found!".
- `flutter test` (dashboard-frontend): 88/88 tests groen.

## Niet gedaan / bewust buiten scope
- Geen alternatieve uitlogmogelijkheid toegevoegd (expliciet buiten scope volgens de story).
- Geen wijzigingen aan `story_detail_screen.dart`/`deployRolloutStage` (blijft ongewijzigd
  werken, zoals vereist).

## Review-notities (SF-1289, reviewer)
- Volledige diff (`git diff main...HEAD`) doorgenomen: frontend- (app_shell.dart, main.dart,
  overview_screens.dart, verwijderde screens+test) en backend-wijzigingen (BridgeApiController,
  BridgeRequestHandler, DashboardApi, DashboardQueryService, FactoryDashboardRepository,
  FactoryDashboardModels + bijbehorende tests) komen 1-op-1 overeen met de scope/AC's uit
  `.task.md`. `mergedStoryKeys()` en `DeployRolloutStatusApi`/`StoryDeployReconciler` blijven
  terecht ongewijzigd. Geen dangling references naar verwijderde symbolen gevonden (grep op
  `RolloutPageData`/`MergedPageData`/`RolloutStoryItem`/`rolloutTargetsFor`/`mergedStoryRuns`/
  `merged.list`/`rollout.list`/`MergedScreen`/`RolloutScreen`/`onLoggedOut` — enige hit is een
  ongerelateerde testnaam in `StoryDeployReconcilerTest.kt` die het woord "rollout" bevat).
  functional-spec.md/technical-spec.md bevatten geen verwijzingen naar deze tabs; ux/README.md
  is expliciet een pre-implementatie Spring MVC/Thymeleaf-ontwerpdocument, dus geen spec-
  inconsistentie.
- Gerichte lokale sanity-checks (geen volledige hertest van het vangnet): `mvn -q -pl
  factory-common,softwarefactory,dashboard-backend -am test-compile` → schoon (main+test
  compileert); `flutter pub get` + `flutter analyze` in `dashboard-frontend/` → "No issues
  found!". Bevestigt het door de developer gerapporteerde bewijs (`mvn verify` BUILD SUCCESS,
  `flutter analyze`/`flutter test` 88/88 groen).
- Oordeel: akkoord, geen blockers/bugs gevonden.
