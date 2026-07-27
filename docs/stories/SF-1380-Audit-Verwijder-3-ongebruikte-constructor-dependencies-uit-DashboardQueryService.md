# SF-1380 - [Audit] Verwijder 3 ongebruikte constructor-dependencies uit DashboardQueryService

## Story

[Audit] Verwijder 3 ongebruikte constructor-dependencies uit DashboardQueryService

<!-- refined-by-factory -->

## Scope
`DashboardQueryService` (softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/services/DashboardQueryService.kt) heeft 3 constructor-dependencies die nergens in de class-body worden gebruikt (bevestigd door Detekt UnusedPrivateProperty + repo-brede grep):

- `orchestratorApi: OrchestratorApi` (regel 96, import regel 32)
- `workspaceLauncher: WorkspaceDesktopLauncher` (regel 110)
- `subtaskPlanMaterializer: SubtaskMaterializationApi` (regel 118, import regel 34, incl. de bijbehorende SF-787-toelichtingscomment op regel 117 en het comment op regel 119-120 dat expliciet naar `subtaskPlanMaterializer` verwijst)

Verwijder deze 3 parameters (inclusief nu overbodige imports en de bijbehorende comments) uit de constructor, en pas alle call-sites aan die `DashboardQueryService(...)` met named args bouwen:

- `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/bridge/BridgeTestFixtures.kt` — de `DashboardQueryService(...)`-aanroep binnen `buildFixture` (regels 122-146): schrap de named args `orchestratorApi = orchestrator` (regel 124), `workspaceLauncher = workspaceLauncher` (regel 138) en `subtaskPlanMaterializer = ...` (regel 143).
- `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/web/services/FactoryDashboardServiceTest.kt` — twee vergelijkbare `DashboardQueryService(...)`-aanroepen: in `createQueries(...)` (rond regel 1006-1022) en in `createService(...)` (rond regel 1051-1069). Schrap in beide de named args `orchestratorApi = FakeOrchestratorApi()`, `workspaceLauncher = workspaceLauncher` en `subtaskPlanMaterializer = materializer`.

Let op: de lokale `val orchestrator`/`FakeOrchestratorApi()`, `val workspaceLauncher` en `val materializer` blijven in deze testbestanden bestaan waar ze ook voor andere constructies (`FactoryOperationsService`, `DashboardCommandService`, `SubtaskPlanMaterializer`-gebruik) nodig zijn — alleen de named args richting `DashboardQueryService(...)` vervallen. Als een lokale `val` daardoor nergens anders meer gebruikt wordt, verwijder ook die `val` en de bijbehorende ongebruikte imports.

Buiten scope: alle overige `OrchestratorApi`/`WorkspaceDesktopLauncher`/`SubtaskMaterializationApi`-gebruik in andere classes (o.a. `FactoryOperationsService.kt`, `DashboardCommandService.kt`, `AgentRunCompletionService.kt`) blijft ongewijzigd. Dit is een pure opschoning van dode code binnen `DashboardQueryService`, geen gedragswijziging en geen stap richting de bredere SRP-opsplitsing van deze God-class (zie agent-memory categorie quality, key dashboardqueryservice-god-class).

## Acceptance criteria
- `DashboardQueryService`'s primary constructor heeft geen `orchestratorApi`, `workspaceLauncher` of `subtaskPlanMaterializer` parameter meer.
- De nu ongebruikte imports (`OrchestratorApi`, `WorkspaceDesktopLauncher`, `SubtaskMaterializationApi`) en de bijbehorende toelichtende comments (SF-787-comment en de "zelfde reden"-comment) zijn verwijderd uit `DashboardQueryService.kt`.
- `BridgeTestFixtures.kt` en `FactoryDashboardServiceTest.kt` bouwen `DashboardQueryService` op zonder deze 3 named args; overige, nog wél gebruikte lokale variabelen/imports in die bestanden blijven intact.
- `mvn test` (module `softwarefactory`) slaagt zonder compile-errors en zonder nieuwe/andere testfalen dan vóór de wijziging.
- Detekt rapporteert geen `UnusedPrivateProperty`-finding meer voor deze 3 properties in `DashboardQueryService.kt`.
- Geen enkele andere call-site van `DashboardQueryService(...)` (main of test) bleef ongewijzigd terwijl hij nog een van de 3 verwijderde named args gebruikt — d.w.z. een repo-brede grep naar `DashboardQueryService(` na de wijziging toont geen resterende `orchestratorApi =`, `workspaceLauncher =` of `subtaskPlanMaterializer =` binnen zo'n aanroep.

## Aannames
- Er zijn geen andere call-sites van `DashboardQueryService(...)` dan de 3 hierboven genoemde (in `DashboardQueryService.kt` zelf, `BridgeTestFixtures.kt`, `FactoryDashboardServiceTest.kt`); dit is bevestigd via repo-brede grep tijdens refinement.
- De verwijdering is zuiver mechanisch (parameter + import + comment schrappen, named args schrappen); er is geen wijziging in gedrag van `DashboardQueryService` of andere classes nodig.

## Eindsamenvatting

Voldoende informatie voor de eindsamenvatting. Klaar om die te schrijven.


Eindsamenvatting SF-1380 – Audit: verwijder 3 ongebruikte constructor-dependencies uit DashboardQueryService

Wat is gebouwd:
- Uit `DashboardQueryService.kt` zijn de 3 nooit-gebruikte constructor-parameters verwijderd: `orchestratorApi` (OrchestratorApi), `workspaceLauncher` (WorkspaceDesktopLauncher) en `subtaskPlanMaterializer` (SubtaskMaterializationApi), inclusief de bijbehorende SF-787- en "zelfde reden"-comments en de nu ongebruikte imports (`OrchestratorApi`, `SubtaskMaterializationApi`).
- De twee call-sites die deze constructor met named args opbouwden zijn aangepast: `BridgeTestFixtures.kt` (`buildFixture`) en `FactoryDashboardServiceTest.kt` (`createQueries` en `createService`) — in alle gevallen zijn de 3 named args geschrapt.
- Lokale `val`'s/imports die daardoor nergens anders meer nodig waren (bv. `val workspaceLauncher`/`val materializer` in `createQueries`, `val materializer` in `createService`) zijn ook verwijderd; `val`'s die nog gebruikt worden voor `FactoryOperationsService`/`DashboardCommandService` zijn bewust behouden.

Gemaakte keuzes:
- Puur mechanische opschoning van dode code: geen gedragswijziging, geen wijziging aan het bredere gebruik van `OrchestratorApi`/`WorkspaceDesktopLauncher`/`SubtaskMaterializationApi` elders (o.a. `FactoryOperationsService.kt`, `DashboardCommandService.kt`, `AgentRunCompletionService.kt`) — dit blijft expliciet buiten scope.
- Geen documentatiewijziging in `docs/factory/` doorgevoerd, omdat er geen functional-/technical-spec of UX-tekst is die deze constructor-parameters beschrijft.

Wat is getest:
- Repo-brede grep naar `DashboardQueryService(` bevestigt: nog maar 3 call-sites (de klasse zelf + de 2 testbestanden), en geen daarvan gebruikt nog `orchestratorApi =`, `workspaceLauncher =` of `subtaskPlanMaterializer =`.
- Gerichte tests (`DashboardQueryServiceTest`, `BridgeRequestHandlerTest`): 63 + 32 tests, 0 failures/errors.
- Volledige testsuite `mvn -pl softwarefactory -am test`: 638 tests, 0 failures/errors, BUILD SUCCESS.
- `mvn verify` vanaf de repo-root: BUILD SUCCESS over alle 5 modules.
- Detekt-ratchet: geen `UnusedPrivateProperty`-finding meer voor deze 3 properties in `DashboardQueryService.kt` (was 3 in baseline); een schijnbare "nieuwe" LongParameterList-finding bleek een fingerprint-hash-artefact van de constructor-edit — het totale findingCount voor dit bestand blijft 34 vóór/na, en het reactor-brede findingCount daalde correct van 744 naar 741 (consistent met de 3 verwijderde properties).

Bewust niet gedaan:
- Geen bredere SRP-opsplitsing van `DashboardQueryService` (bekende God-class, zie agent-memory) — expliciet buiten scope van deze audit-story.
- Geen aanpassing van overig gebruik van de 3 verwijderde interfaces elders in de codebase.
