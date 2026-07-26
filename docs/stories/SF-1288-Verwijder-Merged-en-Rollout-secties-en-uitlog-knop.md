# SF-1288 - Verwijder Merged- en Rollout-secties en uitlog-knop uit dashboard

## Story

Verwijder Merged- en Rollout-secties en uitlog-knop uit dashboard

<!-- refined-by-factory -->

## Scope
Verwijder drie losstaande onderdelen uit het Flutter-dashboard (dashboard-frontend) en de bijbehorende backend (dashboard-backend + softwarefactory bridge-laag).

### Frontend (dashboard-frontend)
- Verwijder `lib/screens/merged_screen.dart` en `lib/screens/rollout_screen.dart`.
- Verwijder de nav-items 'Merged' en 'Rollout' uit `_secondaryEntries` in `lib/app_shell.dart` (incl. de `rollout_screen`-import).
- Verwijder de uitlog-knop op beide plekken in `lib/app_shell.dart`: de `IconButton` in de NavigationRail-trailing (regel 82) en de 'Uitloggen'-`ListTile` in `_openMoreSheet` (regel 149-157).
- Verwijder de daardoor dode plumbing: het `onLoggedOut`-parameter van `AppShell` en de aanroep ervan in `lib/main.dart` (regel 237-240). `clearSession()` blijft bestaan, want die wordt ook aangeroepen bij 401-afhandeling in `api_client.dart` (`_throwOnError`).
- De login/sessie-flow blijft ongewijzigd; de gebruiker blijft ingelogd via het remember-token. Er komt geen alternatieve uitlogmogelijkheid in de UI terug.

### Backend (dashboard-backend)
- Verwijder de endpoints `@GetMapping /api/v1/merged` en `@GetMapping /api/v1/rollout` uit `BridgeApiController.kt`.
- Verwijder of pas de bijbehorende tests aan in `BridgeApiControllerTest.kt` (o.a. de test `rollout vertaalt naar de rollout-list-operatie`).

### Backend (softwarefactory — bridge-operaties)
- Verwijder de nu ongebruikte `"merged.list"`/`"rollout.list"` cases uit `BridgeRequestHandler.kt`.
- Verwijder de daardoor dode code in de softwarefactory-module: `DashboardApi.merged()`/`rollout()`, de implementaties in `DashboardQueryService` (incl. `rolloutTargetsFor`), `FactoryDashboardRepository.mergedStoryRuns()`, en de modellen `MergedPageData`/`RolloutPageData`/`RolloutStoryItem` in `FactoryDashboardModels.kt`.
- `FactoryDashboardRepository.mergedStoryKeys()` blijft bestaan; die wordt elders gebruikt voor overview-data en heeft niets met de Merged-tab te maken.

## Acceptance criteria
- De 'Merged'- en 'Rollout'-navigatie-items zijn nergens meer zichtbaar in het dashboard (rail én bottom-sheet 'Meer').
- De uitlog-knop is nergens meer zichtbaar in de UI; een ingelogde gebruiker blijft ingelogd (geen regressie op de login/sessie-flow, geen ongebruikte `onLoggedOut`/knop-code meer in de codebase).
- `GET /api/v1/merged` en `GET /api/v1/rollout` bestaan niet meer in `BridgeApiController.kt`.
- Geen ongebruikte bridge-operaties, service-methodes of modellen gerelateerd aan merged/rollout blijven achter in dashboard-backend of softwarefactory (behalve `mergedStoryKeys()`, die blijft).
- Het `deployRolloutStage`-badge in `story_detail_screen.dart` (per-story rolloutinformatie) blijft ongewijzigd werken.
- `flutter analyze` en de Flutter-build slagen zonder fouten of ongebruikte-import-warnings gerelateerd aan deze wijziging.
- De Kotlin-backend (dashboard-backend + softwarefactory) compileert en de volledige testsuite is groen, inclusief de aangepaste/verwijderde tests in `BridgeApiControllerTest.kt`.

## Aannames
- De scope van "bijbehorende bridge-operaties die alleen daarvoor bestaan" omvat ook de dieper liggende, nu wees geworden code in de softwarefactory-module (`BridgeRequestHandler`, `DashboardApi`, `DashboardQueryService`, `FactoryDashboardRepository.mergedStoryRuns`, page-data models), niet alleen `BridgeApiController.kt` zelf — anders blijft dode code achter.
- `clearSession()` wordt niet verwijderd, aangezien deze ook wordt gebruikt in de 401-afhandeling van `api_client.dart`.
- Er komt geen vervangende uitlogmogelijkheid (bv. in Settings) terug; dat is expliciet buiten scope volgens de story.

## Eindsamenvatting

## Eindsamenvatting SF-1288: Verwijder Merged- en Rollout-secties en uitlog-knop uit dashboard

**Wat is gebouwd**

*Frontend (dashboard-frontend)*
- `merged_screen.dart` en `rollout_screen.dart` verwijderd, inclusief de bijbehorende export/import in `overview_screens.dart` en `app_shell.dart`.
- Nav-items 'Merged' en 'Rollout' verwijderd uit `_secondaryEntries` in `app_shell.dart`.
- Uitlog-knop verwijderd op beide plekken: de `IconButton` in de NavigationRail-trailing en de 'Uitloggen'-`ListTile` in `_openMoreSheet`.
- De daardoor dode plumbing (`onLoggedOut`-parameter van `AppShell` en de doorgifte ervan in `main.dart`) opgeruimd. `clearSession()` blijft bestaan, want die is nog nodig voor de 401-afhandeling in `api_client.dart`.
- Verwijderde test `rollout_screen_test.dart` (testte het verwijderde scherm).

*Backend (dashboard-backend)*
- Endpoints `GET /api/v1/merged` en `GET /api/v1/rollout` verwijderd uit `BridgeApiController.kt`, inclusief bijbehorende test.

*Backend (softwarefactory)*
- De `"merged.list"`/`"rollout.list"`-routering uit `BridgeRequestHandler.kt` verwijderd.
- Onderliggende dode code opgeruimd: `DashboardApi.merged()`/`rollout()`, de implementaties + `rolloutTargetsFor`-helper in `DashboardQueryService`, `FactoryDashboardRepository.mergedStoryRuns()` en de dashboard-repo-variant van `runsAwaitingDeployConfirmation`, en de modellen `MergedPageData`/`RolloutPageData`/`RolloutStoryItem`.
- Bijbehorende tests (`FactoryDashboardServiceTest`, `BridgeRequestHandlerTest`, `BridgeTestFixtures`) meegenomen.

**Belangrijke keuzes**
- `mergedStoryKeys()` bewust ongewijzigd gelaten: wordt elders gebruikt voor de merged-indicator op het stories-overzicht en staat los van de Merged-tab.
- `DeployRolloutStatusApi`/`StoryDeployReconciler` bewust ongewijzigd: intern nog nodig voor de eigen `deployedAt`-reconciliatie, los van de verwijderde Rollout-tab.
- Het `deployRolloutStage`-badge in `story_detail_screen.dart` is niet aangeraakt en blijft ongewijzigd werken.
- Design-documenten (`ux/README.md`, `screen-map.md`, `dashboard-v2.md`, `screens/merged.md`) bewust niet aangepast: dit zijn expliciet pre-implementatie ontwerpdocumenten voor een oudere Spring MVC/Thymeleaf-UI die al vóór deze story niet de huidige Flutter-implementatie beschreven.

**Getest**
- `mvn verify` (alle Maven-modules incl. Testcontainers/e2e-tests): BUILD SUCCESS, 0 failures/errors.
- `flutter analyze`: geen issues.
- `flutter test`: 88/88 tests groen.
- Reviewer heeft de volledige diff nagelopen tegen de scope/acceptatiecriteria en gericht gegrept op dangling references naar verwijderde symbolen (o.a. `RolloutPageData`, `MergedScreen`, `onLoggedOut`) — geen bevindingen; oordeel akkoord, geen blockers.

**Bewust niet gedaan**
- Geen alternatieve uitlogmogelijkheid toegevoegd (expliciet buiten scope).
- Geen wijzigingen aan `story_detail_screen.dart`/het `deployRolloutStage`-badge.
