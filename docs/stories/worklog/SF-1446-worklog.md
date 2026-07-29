# SF-1446 - Worklog

Story-context bij eerste pickup:
Deel stripTrailingControlJson uit AuditGatewayAdapter en pas toe op alle summaryText-doorgeefroutes

Verplaats stripTrailingControlJson()+topLevelJsonObjectSpans() (nu private in dashboard.services.AuditGatewayAdapter, r312-367) naar een nieuw, object-achtig stuk gedeelde code in factory-common, package nl.vdzon.softwarefactory.support (naast het bestaande support.services.SecretRedactor - vergelijkbare stijl, eigen ObjectMapper-instantie). Update AuditGatewayAdapter om de gedeelde functie aan te roepen i.p.v. de eigen private implementatie (gedrag ongewijzigd). Pas de gedeelde strip vervolgens toe op: (1) FactoryOperationsService.testerReportFor() (r89-96) - strip summaryText voordat die wordt teruggegeven; (2) TelegramNotificationService.testerReport() (r320-323) - strip vóór de .take(TESTER_REPORT_LIMIT)-afkapping (volgorde is een acceptatiecriterium); (3) DashboardQueryService.storyDetail() (r321-355) - strip summaryText op de opgehaalde allRuns-lijst (via AgentRun.copy(summaryText = ...)) vóórdat die naar agentRuns/allAgentRuns/StoryDetailPageData gaat, zodat zowel het /api/v1/stories/{storyKey}-endpoint als _BriefingPanel in dashboard-frontend/lib/screens/story_detail_screen.dart schone tekst tonen. StoryStatusPresenter en TelegramReplyService blijven ongewijzigd (raken summaryText niet). Schrijf unit tests voor de gedeelde stripfunctie (quote-bewustheid, meerdere trailing blokken, geen inhoud wegknippen) en tests die vastleggen dat het testrapport (Telegram/FactoryOperationsService) en het story-detail (DashboardQueryService) geen rauwe controle-JSON meer bevatten, en dat bestaand auditrapport-gedrag ongewijzigd blijft.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-1474 (subtaak van deze story) — Deel stripTrailingControlJson uit AuditGatewayAdapter

- Nieuw object `nl.vdzon.softwarefactory.support.ControlJsonStripper` in `factory-common`
  (`factory-common/src/main/kotlin/.../support/ControlJsonStripper.kt`), met de verplaatste
  `stripTrailingControlJson`/`topLevelJsonObjectSpans`-logica 1-op-1 uit `AuditGatewayAdapter`
  (eigen `ObjectMapper`-instantie, zelfde stijl als `support.services.SecretRedactor`).
- `AuditGatewayAdapter.reportContent()` roept nu `ControlJsonStripper.stripTrailingControlJson`
  aan i.p.v. de eigen private implementatie; de twee private helper-functies zijn verwijderd.
  Gedrag ongewijzigd (verhuisde code, geen logicawijziging).
- `FactoryOperationsService.testerReportFor()` strip nu ook, via een nieuwe testbare companion-
  helper `testerReportFrom(runs)` (analoog aan `latestAgentQuestions`/`noDecisionKeys`).
- `TelegramNotificationService.testerReport()` strip expliciet vóór de `.take(TESTER_REPORT_LIMIT)`
  -afkapping (volgorde is een acceptatiecriterium); dit is defense-in-depth bovenop de strip in
  `FactoryOperationsService` (idempotent, dus geen dubbele knip).
- `DashboardQueryService.storyDetail()` strip `summaryText` op de runs die naar
  `agentRuns`/`allAgentRuns` gaan, via een nieuwe companion-helper `stripSummaryText(runs)`.
  BELANGRIJK: `agentQuestions`/`agentNoDecisionKeys` blijven op de ONgestripte `allRuns` berekend —
  een "...-with-questions"-controleblok heeft zowel een `phase`- als een `questions`-sleutel, dus
  zou anders door de stripper zijn weggeknipt vóórdat `questionTextFrom` de vragen eruit kan halen.
- Modulith-verrassing (story-aanname bleek onjuist op dit punt): `factory-common`'s package
  `nl.vdzon.softwarefactory.support` deelt de EXACTE base package met `softwarefactory`
  (`nl.vdzon.softwarefactory`), dus Spring Modulith scant `ControlJsonStripper` mee als onderdeel
  van het al bestaande, lege marker-module `support` (package-info met `allowedDependencies = {}`,
  zie `softwarefactory/.../support/package-info.java`). `ModulithArchitectureTest` faalde daardoor
  in eerste instantie: modules `telegram` en `dashboard` moesten `"support"` expliciet aan hun eigen
  `allowedDependencies` toevoegen. Zonder deze twee package-info-wijzigingen was de story-aanname
  ("geen wijziging aan ModulithArchitectureTest nodig") juist gebleven qua testbestand zelf, maar
  niet qua module-package-info's die de test valideert — beide package-info.java's (telegram,
  dashboard) zijn nu bijgewerkt.
- Tests: nieuw `factory-common` `ControlJsonStripperTest` (6 tests: enkel/meerdere trailing
  blokken, quote-bewustheid, geen inhoud wegknippen bij tekst ná het blok of bij een blok zonder
  herkenbare sleutel). Nieuwe tests in `TelegramNotificationServiceTest` (testrapport zonder
  trailing JSON in de melding) en in `DashboardQueryServiceTest`
  (`testerReportFrom`/`stripSummaryText` companion-helpers).
- Bewijs: `mvn verify` vanaf de repo-root — BUILD SUCCESS, alle modules groen (incl.
  `ModulithArchitectureTest` en de Testcontainers-e2e-tests van `softwarefactory`), 0 failures,
  0 errors.
- Geen wijzigingen aan `docs/factory/functional-spec.md`/`technical-spec.md`/`ux/`: de gedeelde
  stripfunctie is interne implementatiedetail zonder zichtbare functionele/UX-wijziging (het
  testrapport en story-detail zagen er met de bug al "bijna schoon" uit voor gebruikers die de
  JSON negeerden; dit is een bugfix, geen nieuw gedrag om te documenteren).

## Review (SF-1474)

- Diff tegen main gecontroleerd: `ControlJsonStripper` (factory-common) bevat de logica 1-op-1
  verplaatst uit `AuditGatewayAdapter` (geen gedragswijziging); `AuditGatewayAdapter.reportContent()`
  roept nu de gedeelde functie aan.
- Strip correct toegepast op alle drie de doorgeefroutes: `FactoryOperationsService.testerReportFor`
  (via `testerReportFrom`), `TelegramNotificationService.testerReport` (vóór `.take(TESTER_REPORT_LIMIT)`,
  bewust defense-in-depth bovenop de vorige laag), en `DashboardQueryService.storyDetail`
  (`stripSummaryText` op `agentRuns`/`allAgentRuns`).
- Geverifieerd dat `agentQuestions`/`agentNoDecisionKeys` in `DashboardQueryService` bewust op de
  ONgestripte `allRuns` blijven berekend — correct, anders zou een `...-with-questions`-blok (heeft
  zowel `phase` als `questions`) worden weggeknipt vóór vraag-extractie.
- Modulith package-info-wijzigingen (`telegram`, `dashboard` +`"support"`) zijn coherent met de
  gedocumenteerde surprise dat `factory-common`'s `support`-package hetzelfde base package deelt.
- Tests: nieuwe `ControlJsonStripperTest` (6, quote-bewust/meerdere blokken/geen inhoud kwijt),
  plus dekkende tests in `TelegramNotificationServiceTest` en `DashboardQueryServiceTest`
  (`testerReportFrom`/`stripSummaryText`) die aantonen dat trailing controle-JSON verdwijnt.
- Testbewijs: alle surefire/failsafe-rapporten in de werktree (o.a. `factory-common`,
  `softwarefactory`) tonen 0 failures/0 errors, consistent met de developer-claim "BUILD SUCCESS"
  voor exact deze HEAD.
- Geen spec-inconsistenties gevonden in `docs/factory/` (interne implementatiedetail, geen
  zichtbaar gedrag om te documenteren, terecht).
- Geen scope creep; geen blockers.

Oordeel: akkoord.
