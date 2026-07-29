# SF-1446 - Testrapport in Telegram bevat de rauwe JSON-controleblokken van de agent

## Story

Testrapport in Telegram bevat de rauwe JSON-controleblokken van de agent

<!-- refined-by-factory -->

## Samenvatting

Testrapporten (en andere plekken waar agent-samenvattingen aan een mens getoond worden) eindigen vaak met de rauwe JSON-protocolblokken van de agent, zoals `{"phase":"tested"}` of `{"agent_tips_update":[]}`. Deze JSON is bedoeld als afspraak tussen agent en factory, niet om te lezen. Er bestaat al een werkende oplossing voor auditrapporten; die gaan we herbruikbaar maken en ook toepassen op het testrapport in Telegram en op andere plekken die dezelfde rauwe tekst laten zien.

## Scope

- Verplaats de bestaande stripfunctionaliteit (`stripTrailingControlJson` + de quote-bewuste helper `topLevelJsonObjectSpans`, nu `private` in `AuditGatewayAdapter.kt`) naar een gedeelde, publiek/internal bruikbare plek in de `factory-common`-module (bv. een nieuw object binnen een geschikt package, zoals `support`). `factory-common` is al een Maven-dependency van `softwarefactory`, dus dit raakt de `ModulithArchitectureTest`-grenzen niet.
- Gebruik deze gedeelde functie in `AuditGatewayAdapter.kt` (bestaand gebruik, gedrag ongewijzigd) én in `TelegramNotificationService.testerReport()` (`softwarefactory/.../telegram/services/TelegramNotificationService.kt`), vóórdat daar wordt afgekapt op `TESTER_REPORT_LIMIT`.
- Pas dezelfde strip ook toe in `FactoryOperationsService.testerReportFor()` (`softwarefactory/.../dashboard/services/FactoryOperationsService.kt`), zodat elke consument van deze functie al schone tekst krijgt — dit dekt zowel het Telegram-pad als eventuele toekomstige consumenten in één keer af.
- Pas de strip toe op de `summaryText`-velden die via `DashboardQueryService.storyDetail()` (`allAgentRuns`) naar het `/api/v1/stories/{storyKey}`-endpoint gaan, zodat het story-detail in het dashboard (getoond in `dashboard-frontend/lib/screens/story_detail_screen.dart`, `_BriefingPanel`) ook geen rauwe controleblokken meer toont.
- `StoryStatusPresenter` en `TelegramReplyService` tonen geen `summaryText` en zijn dus buiten scope — geen wijziging nodig.
- Geen wijziging aan `ModulithArchitectureTest` nodig (bevestigd: scant alleen binnen het `softwarefactory`-Maven-artefact, `factory-common` valt daarbuiten).

## Acceptance criteria

- Een testrapport met trailing controlblokken komt schoon in de Telegram-melding terecht: geen `{"phase":...}` en geen `{"agent_tips_update":...}` meer zichtbaar.
- De inhoudelijke rapporttekst blijft verder ongewijzigd (geen woorden kwijt aan begin of eind van de tekst).
- Het strippen gebeurt vóór het afkappen op `TESTER_REPORT_LIMIT`.
- Story-detail in het dashboard (`allAgentRuns`/`_BriefingPanel`) toont ook geen rauwe trailing controlblokken meer.
- Bestaand gedrag van de auditrapporten (`AuditGatewayAdapter`) blijft ongewijzigd werken; deze gebruiken dezelfde, nu gedeelde, functie.
- Er is een (nieuwe) test voor de gedeelde stripfunctie zelf (in `factory-common`) én minstens één test die vastlegt dat het testrapport in `TelegramNotificationService`/`FactoryOperationsService` geen trailing JSON meer bevat, zodat dit niet stil terugkomt.
- `mvn verify` (of het equivalente buildcommando uit `docs/factory/development.md`) slaagt, inclusief `ModulithArchitectureTest`.

## Aannames

- De gedeelde stripfunctie wordt als nieuw object in `factory-common` geplaatst (bv. `nl.vdzon.softwarefactory.support.ControlJsonStripper` of vergelijkbaar); er bestaat nog geen logische bestaande util-klasse om op aan te haken.
- `dashboard-backend` zelf toont geen rauwe `summaryText` rechtstreeks (het geeft de al door de backend opgebouwde JSON van `softwarefactory` door), dus die module hoeft niet aangepast te worden; alleen `softwarefactory` (backend) en `dashboard-frontend` (weergave) zijn in scope.
- De opvolgstap uit de story ("`docs/images/telegram-progress.png` opnieuw maken en de known-bug-alinea uit `docs/images/README.md` verwijderen") is expliciet *niet* onderdeel van deze story maar losstaand vervolgwerk, zoals de description zelf aangeeft ("Daarna").

## Eindsamenvatting

Ik heb genoeg context uit de worklog. Ik schrijf de eindsamenvatting voor de PO.

**Eindsamenvatting SF-1446 — Testrapport in Telegram bevat rauwe JSON-controleblokken**

**Wat is gebouwd**
- De strip-logica voor rauwe controle-JSON (`stripTrailingControlJson`, voorheen `private` in `AuditGatewayAdapter`) is verplaatst naar een nieuw, gedeeld object `ControlJsonStripper` in `factory-common` (`nl.vdzon.softwarefactory.support`).
- Deze gedeelde functie wordt nu op vier plekken toegepast, zodat elke consument schone tekst krijgt:
  1. `AuditGatewayAdapter.reportContent()` — bestaand gebruik, gedrag ongewijzigd.
  2. `FactoryOperationsService.testerReportFor()` (via nieuwe helper `testerReportFrom`).
  3. `TelegramNotificationService.testerReport()` — strip vóór de `.take(TESTER_REPORT_LIMIT)`-afkapping, zoals vereist door de acceptatiecriteria.
  4. `DashboardQueryService.storyDetail()` (via nieuwe helper `stripSummaryText`) — dekt `agentRuns`/`allAgentRuns`, dus ook het story-detail-scherm in het dashboard.

**Belangrijke keuze**
- In `DashboardQueryService` worden `agentQuestions`/`agentNoDecisionKeys` bewust op de *ongestripte* runs berekend, omdat een "...-with-questions"-blok zowel een `phase`- als `questions`-sleutel heeft en anders vóór vraag-extractie zou zijn weggeknipt.

**Onverwachte bevinding tijdens bouw**
- De aanname dat `ModulithArchitectureTest` ongemoeid zou blijven, klopte niet volledig: `factory-common`'s `support`-package deelt het base package met `softwarefactory`, waardoor Spring Modulith deze meescant als onderdeel van de bestaande lege marker-module `support`. Hierdoor moesten de modules `telegram` en `dashboard` `"support"` expliciet aan hun eigen `allowedDependencies` toevoegen (kleine, verwachte package-info-aanpassing, geen architectuurwijziging).

**Getest**
- Nieuwe unit test `ControlJsonStripperTest` (6 tests) in `factory-common`: quote-bewustheid, meerdere trailing blokken, geen inhoudsverlies.
- Nieuwe tests in `TelegramNotificationServiceTest` en `DashboardQueryServiceTest` die vastleggen dat het testrapport respectievelijk het story-detail geen rauwe controle-JSON meer bevatten.
- Gerichte testrun (`ControlJsonStripperTest`, `TelegramNotificationServiceTest`, `FactoryDashboardServiceTest`): alles groen, 0 failures/0 errors.
- `mvn verify` op de volledige repo: BUILD SUCCESS, inclusief `ModulithArchitectureTest` en de Testcontainers-e2e-tests.
- Reviewer en tester zijn beiden akkoord gegaan zonder blockers of scope-afwijkingen.

**Bewust niet gedaan**
- Geen wijziging aan documentatie (`functional-spec.md`/`technical-spec.md`/`ux/`): dit is een interne bugfix zonder nieuw zichtbaar gedrag (documentatie-update volgt in de aparte subtaak SF-1477).
- Geen browser/UI-verificatie: backend-only wijziging zonder preview-omgeving voor deze repo; getest via codelezing en gerichte testruns.
- Vervolgwerk uit de story (opnieuw maken van `docs/images/telegram-progress.png` en verwijderen van de known-bug-alinea) is expliciet buiten scope van deze story.
