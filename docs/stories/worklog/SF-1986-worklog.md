# SF-1986 — Eventgestuurde Telegram-meldingen

## Story in eigen woorden

Vervang de vaste meldingenstand door een uitbreidbare set van acht concrete Telegram-events. Alleen
het aanmaakscherm kent drie gebruiksvriendelijke presets; opslag, backend en API's kennen uitsluitend
de eventset. Subtaken erven steeds de actuele parentset, alle events blijven onafhankelijk en de
bestaande idempotentie per issue/toestand blijft behouden.

## Checklist

- [x]: `NotifyMode` uit actuele domein-, tracker-, API-, bridge- en frontendcontracten verwijderen.
- [x]: PostgreSQL-migratie met de voorgeschreven conversiematrix en `TEXT[]`-opslag toevoegen.
- [x]: alle aanmaakroutes atomair een concrete default- of expliciete eventset laten schrijven.
- [x]: auditvoorstellen exact `QUESTION`, `MANUAL_ACTION_REQUIRED`, `ERROR` laten krijgen.
- [x]: notificatieclassificatie op alle acht onafhankelijke events en parent-inheritance zetten.
- [x]: frontend-aanmaakpresets, default **Als deployed** en waarschuwing implementeren.
- [x]: story-detail acht losse checkboxes, lege set en waarschuwing laten ondersteunen.
- [x]: backend-, migratie-, contract-, idempotentie-, parent- en frontendtests schrijven/bijwerken.
- [x]: functionele, technische, API- en UX-specificaties actualiseren.
- [x]: volledige `mvn verify` met 0 failures en 0 errors afronden.
- [x]: volledige repositorygate `tools/verify-repository` afronden.

## Uitvoering en keuzes

`NotificationEvent` bevat exact de acht story-events. `notification_events` is een niet-null
PostgreSQL `TEXT[]`; zo is ook een lege set een echte waarde zonder presetnaam of sentinel.
`V34__notification_events.sql` backfillt de vier historische standen volgens de storymatrix en
verwijdert daarna `notify_mode`.

`createStory` schrijft `approval_mode` en `notification_events` direct mee in dezelfde INSERT.
Dashboard, bridge, tracker-API en `tools/sf-story` geven concrete events door; auditvoorstellen
gebruiken de aparte exacte auditset. `effectiveNotificationEvents` leest voor subtaken telkens de
actuele parent-story.

`TelegramNotificationService` vertaalt toestanden naar `QUESTION`, `APPROVAL_REQUIRED`,
`MANUAL_ACTION_REQUIRED`, `QUOTA_WAIT`, `ERROR`, `STEP_COMPLETED` en `WORKFLOW_COMPLETED`.
Een laatste stap kan onafhankelijk zowel stap- als workflow-events opleveren; een klaarstaande
handmatige merge is een afzonderlijk manual-action-event. `TelegramResultNotifyPoller` filtert op
`DEPLOYED` en behoudt de bestaande DB-signature-idempotentie.

De Flutter-createflow kent uitsluitend de drie presets en verstuurt hun concrete array. Detail
toont acht checkboxes en verstuurt steeds de volledige set; leeg blijft toegestaan. Beide schermen
waarschuwen niet-blokkerend bij goedkeuring na elke stap zonder `APPROVAL_REQUIRED`.

Bijgewerkt: `docs/factory/functional-spec.md`, `docs/factory/technical-spec.md`,
`docs/factory/ux/screens/stories.md`, `docs/factory/ux/screens/story-detail.md` en de actuele API-/
architectuurdocumentatie onder `docs/technical` en `docs/ontwerp-bridge-dashboard.md`, zodat alleen
het concrete eventset-contract als actueel gedrag beschreven staat.

## Verificatiebewijs

- Gerichte backend-regressie na de laatste refactor: 85 tests, 0 failures, 0 errors.
- Gerichte Flutter-regressie: 15 tests groen; `flutter analyze` zonder bevindingen.
- `mvn verify`: exitcode 0 over alle zes reactormodules, 0 failures en 0 errors.
- `tools/verify-repository`: exitcode 0. Daarin waren de contractchecks, een schone `mvn verify`,
  quality-ratchet, module-dependency-check, Flutter-analyse, alle 142 Flutter-tests, mini-reactor,
  Docker build-stage en documentatie-audit groen.
- Quality-ratchet: geen nieuwe bevindingen of suppressies; zeven bestaande bevindingen opgelost door
  kleine extracties in de gewijzigde code en aangrenzende bestaande hotfix-/repositorycontractcode.
- Een eerdere gatepoging kreeg één tijdelijke Testcontainers/Ryuk-`Broken pipe`; de exacte test
  slaagde direct daarna 5/5 en de daaropvolgende volledige repositorygate eindigde volledig groen.

## Review 2026-08-05

- [blocker] AC16 is nog niet aantoonbaar afgedekt. Er zijn geen positieve notificatiegedragstests
  voor `APPROVAL_REQUIRED` en `MANUAL_ACTION_REQUIRED`; de audit-aanmaakroute heeft geen test die de
  exacte atomair opgeslagen auditset controleert; en de migratiematrix wordt alleen als SQL-tekst
  geassert. Voeg gedragstests toe voor alle acht categorieën, de drie manual-action-toestanden, de
  auditor-INSERT en een echte V33→V34-datamigratie met alle vier legacywaarden.
- [blocker] De bijgewerkte documentatie is intern inconsistent. `docs/onboarding-senior-developer.md`
  zegt dat een lege eventset toch een vraagbericht kan opleveren, terwijl de story expliciet bepaalt
  dat leeg alle Telegram-meldingen onderdrukt. `docs/technical/external-systems.md` zegt bovendien
  dat V34 bestaande rijen niet bijwerkt, terwijl V34 juist alle bestaande `notify_mode`-waarden
  converteert.
- [info] Gerichte reviewchecks waren groen: geselecteerde Maven-tests exit 0; de twee geraakte
  Flutter-testsuites 15/15 groen; `tools/audit-documentation` PASS; `git diff --check` schoon.

## Reviewherstel 2026-08-05

- [x]: positieve notificatiegedragstest voor `APPROVAL_REQUIRED` toegevoegd.
- [x]: de gewone handmatige subtaak, vaste manual-approve-poort en handmatige merge-actie als
  afzonderlijke `MANUAL_ACTION_REQUIRED`-toestanden getest.
- [x]: de auditor-aanmaakroute door het volledige `auditOutcome`-pad getest op de exacte, atomair
  meegegeven set `QUESTION`, `MANUAL_ACTION_REQUIRED`, `ERROR`.
- [x]: een echte PostgreSQL/Flyway V33→V34-datamigratie met alle vier legacywaarden getest.
- [x]: de twee tegenstrijdige documentatiepassages over een lege set en de V34-backfill hersteld.
- [x]: volledige `mvn verify` na het reviewherstel met 0 failures en 0 errors afgerond.
- [x]: volledige `tools/verify-repository` na het reviewherstel afgerond.

De notificatietests gebruiken per toestand uitsluitend het geselecteerde event op de parent-story.
Daarmee bewijzen ze tegelijk de positieve classificatie, parent-inheritance en dat de categorie niet
per ongeluk via een andere geselecteerde gebeurtenis wordt verstuurd. De migratietest bouwt in een
apart schema de echte database op tot V33, schrijft de vier historische `notify_mode`-waarden, voert
daarna V34 uit en leest zowel de geordende arrays als het verdwijnen van de oude kolom terug.

De auditregressie schrijft een echt `agent-result.json` met een voorgestelde story en verwerkt dit
via `AuditGatewayAdapter.auditOutcome`. De geverifieerde `createStory`-aanroep bevat de auditset in
dezelfde create-operatie; het opgeslagen auditrapport verwijst vervolgens naar de aangemaakte key.

Bijgewerkt: `docs/onboarding-senior-developer.md` verduidelijkt dat een lege eventset ook vragen
onderdrukt. `docs/technical/external-systems.md` beschrijft nu correct dat V34 iedere bestaande rij
converteert voordat `notify_mode` wordt verwijderd. `tools/audit-documentation` en
`git diff --check` zijn na deze correcties groen.

Boyscout-herstel tijdens het volledige vangnet: `FactoryApiControllerTest` gebruikte voor de geldige
restart-route de echte `FactoryProcessService`, die na 600 ms de test-JVM met `Runtime.halt(0)` kon
beëindigen. De test mockt die final service nu en verifieert `requestRestart()`, zodat de controller
hetzelfde contract bewijst zonder een timingafhankelijke Surefire-forkcrash.

Verificatie na alle codewijzigingen: de geïsoleerde controllerregressie draaide 5/5 groen. De finale
`tools/verify-repository` eindigde met exitcode 0: de schone Maven-reactor over zes modules had 0
failures en 0 errors, de quality-ratchet had geen nieuwe bevindingen of suppressies, module-drift was
schoon, Flutter-analyse en alle 142 Flutter-tests waren groen, en ook mini-reactor, Docker build-stage
en documentatie-audit slaagden.

## Herreview 2026-08-05

- [blocker] `NotificationEvent.parse` negeert onbekende waarden en de create-/update-adapters slaan
  het resultaat daarna als geldige set op. Daardoor wordt bijvoorbeeld `['EROR']` stilzwijgend een
  lege eventset, terwijl leeg een bewuste, functioneel afwijkende keuze is. Valideer de externe
  create- en updatecontracten strikt tegen exact de acht ondersteunde waarden en dek af dat een
  onbekende waarde wordt afgewezen zonder de bestaande set te overschrijven.
- [blocker] `effectiveNotificationEvents` valt voor een subtaak bij een ontbrekende of falende
  parent-lookup terug op `issue.fields.notificationEvents`. Subtaakrijen krijgen via de niet-null
  database-default juist de standaardset, zodat een tijdelijke lookupfout meldingen kan versturen
  terwijl de parent bewust een lege of beperktere set heeft. Dit botst met parent-only opslag/
  inheritance en met de garantie dat een lege parentset alle Telegram-meldingen onderdrukt. Maak
  dit pad fail-closed/parent-authoritative en voeg een regressietest toe voor parentset leeg plus
  mislukte parent-resolutie.
- [info] Geldige gerichte herreviewchecks: `BridgeApiControllerTest` 34/34 groen; met de gewijzigde
  reactordependency meegenomen (`-am`) waren `BridgeRequestHandlerTest` en
  `TelegramNotificationServiceTest` samen 82/82 groen. `git diff --check main...HEAD` was schoon.

## Herreviewherstel 2026-08-05

- [x]: onbekende notification-eventnamen strikt en case-sensitive tegen exact de acht publieke
  waarden valideren.
- [x]: bridge-create en -update met `INVALID_PARAMS` laten falen vóór een tracker-write.
- [x]: tracker-token-API create/update met HTTP 400 laten falen en partial updates vooraf volledig
  valideren.
- [x]: subtask-inheritance parent-authoritative en fail-closed maken bij een ontbrekende link of
  falende parent-read.
- [x]: Telegram bij iedere onverwachte resolutiefout naar een lege set laten terugvallen.
- [x]: regressietests voor parser, beide create-/updateroutes en parentset-lekkage toevoegen.
- [x]: functionele, technische, bridge- en moduledocumentatie met het strikte/fail-closed contract
  actualiseren.
- [x]: gerichte regressie en volledige `mvn verify` met 0 failures en 0 errors afronden.
- [x]: volledige `tools/verify-repository` over de definitieve code-/testtree afronden.

`NotificationEvent.parse` accepteert nu uitsluitend de exacte enumnaam. Daardoor kunnen een
typefout of afwijkende casing niet meer stilzwijgend tot de functioneel geldige lege set leiden.
De bridge vertaalt de parserfout naar `INVALID_PARAMS`; de token-API vertaalt hem naar HTTP 400 en
parseert bij partial update vóór de eerste mutatie, zodat bestaande waarden behouden blijven.

`effectiveNotificationEvents` gebruikt voor subtaken nooit meer hun eigen database-default. Een
ontbrekende parent-link of mislukte parent-read levert een lege set op. De notifier zelf gebruikt
dezelfde fail-closed fallback en stopt vóór classificatie als de effectieve set leeg is. De
regressietest simuleert eerst een onleesbare parent en herstelt die daarna met een lege set; in beide
polls blijft Telegram stil ondanks de volledige eventset op de subtaakfixture.

Bijgewerkt: `docs/factory/functional-spec.md`, `docs/factory/technical-spec.md`,
`docs/ontwerp-bridge-dashboard.md` en `docs/technical/modules.md`, zodat exacte externe waarden,
preventie van writes vóór validatie en fail-closed parent-inheritance expliciet zijn vastgelegd.

Verificatie tot aan de repositorygate: de gerichte Maven-run had 99 tests, 0 failures en 0 errors.
De volledige `mvn verify` over alle zes reactormodules eindigde met exitcode 0, 0 failures en
0 errors; de softwarefactory-module draaide daarbij 853 unit-tests plus de volledige
Testcontainers-/e2e-suite.

De finale `tools/verify-repository` eindigde met exitcode 0. De schone Maven-reactor, quality
ratchet zonder nieuwe bevindingen of suppressies, moduledependencycontrole, Flutter-analyse en
alle 142 Flutter-tests waren groen. Ook de mini-reactor-smoke, Docker build-stage en
documentatie-audit slaagden.

## Eindreview 2026-08-05

- [blocker] `TelegramNotificationService.classifyWorkflowEvents` behandelt een menselijke gate
  als exclusief: zodra `HumanGate.APPROVAL` aanwezig is, retourneert de methode alleen
  `APPROVAL_REQUIRED` en wordt de voltooide workflowstap niet ook als `STEP_COMPLETED`
  geclassificeerd. Bij goedkeuring=`elke-stap` en de preset **Na elke stap** ontbreken daardoor
  onafhankelijke stap-klaarmeldingen voor precies de overgangen die tegelijk op goedkeuring
  wachten. Dit botst met de story-aanname dat meerdere events op één overgang onafhankelijk
  blijven en met AC14/AC16. Voeg gecombineerde gedragstests toe die beide geselecteerde events
  voor dezelfde afgeronde stap aantonen.
- [blocker] De actuele technische documentatie is nog intern inconsistent over de Flyway-versie:
  `docs/technical/overview.md` zegt dat uitbreidingen tot en met V33 lopen en noemt direct daarna
  V34; `docs/technical/external-systems.md` noemt eveneens alleen `V1`–`V33`. Werk beide actuele
  passages bij naar V34. Volgens de reviewer-regels blokkeert inconsistentie in relevante specs
  de merge.
- [info] De volledige diff `main...HEAD` is beoordeeld. De definitieve developercomment claimt
  revisiongebonden groen bewijs voor `mvn verify` en `tools/verify-repository`, inclusief 142
  Flutter-tests, Docker-build en documentatie-audit. Gerichte statische checks: `git diff --check
  main...HEAD` schoon; `.factory/verification.yaml` ongewijzigd en zonder shell-string/fail-open
  commandocontract; geen implementatiebestanden gewijzigd tijdens deze review.

## Eindreviewherstel 2026-08-05

- [x]: `APPROVAL_REQUIRED` en `STEP_COMPLETED` onafhankelijk laten classificeren bij dezelfde
  afgeronde stap die op goedkeuring wacht.
- [x]: gecombineerd gedrag voor zowel een story-gate als een subtaak-gate testen, inclusief
  idempotentie bij een herhaalde poll.
- [x]: de actuele Flyway-reeksen in `docs/technical/overview.md` en
  `docs/technical/external-systems.md` corrigeren naar V34.
- [x]: gerichte regressietests afronden.
- [x]: volledige `mvn verify` met 0 failures en 0 errors afronden.
- [x]: volledige `tools/verify-repository` afronden.

Een approval-gate vertegenwoordigt twee gelijktijdige domeingebeurtenissen: de workflowstap is
afgerond en de uitkomst wacht op menselijke goedkeuring. De notifier levert daarom twee events met
verschillende signatures (`approve:<fase>` en `done:<fase>`). Selectie en database-idempotentie
blijven per event onafhankelijk; een tweede poll verstuurt geen van beide opnieuw. Vraag- en
handmatige wachtgates blijven enkelvoudig, omdat die toestanden zelf geen afgeronde stap betekenen.

Gerichte regressie: `TelegramNotificationServiceTest` draaide 39 tests met 0 failures en 0 errors.
De eerste losse module-aanroep gebruikte een verouderde lokaal geïnstalleerde cross-module
dependency; de reactorvariant met `-am` bouwde de actuele checkout en was volledig groen.

Volledige backendverificatie: `mvn verify` vanaf de repositoryroot eindigde met exitcode 0. Alle
zes reactormodules waren succesvol; de softwarefactory-module draaide 855 unit-tests en daarna de
volledige Failsafe-/Testcontainers-e2e-laag zonder failures of errors.

De finale `tools/verify-repository` eindigde eveneens met exitcode 0. Daarmee waren de vier
gate-contracttests, een schone Maven-reactor, de quality-ratchet, moduledependencycontrole,
Flutter-analyse en 142 Flutter-tests, de mini-reactor-smoke, de Docker build-stage en de
documentatie-audit groen. De runner miste aanvankelijk alleen de `docker`-CLI; na het tijdelijk
beschikbaar maken van de officiële client buiten de repository is de volledige canonieke gate
ongewijzigd opnieuw gestart en tot het einde doorlopen.

## Review na eindreviewherstel 2026-08-05

- [blocker] De documenter-fasen ontbreken in `HumanActionPolicy.gateFor`. De coordinator houdt
  `DOCUMENTATION_WITH_QUESTIONS` terecht vast op een gebruikersantwoord en houdt `DOCUMENTED` bij
  `ApprovalMode=elke-stap` vast op goedkeuring, maar de gedeelde gateclassificatie retourneert voor
  beide fasen `null`. Daardoor verstuurt `TelegramNotificationService` geen geselecteerde
  `QUESTION` respectievelijk `APPROVAL_REQUIRED` (en bij `DOCUMENTED` ook geen gelijktijdige
  `STEP_COMPLETED`), terwijl de dashboardactie eveneens ontbreekt. Voeg beide documenter-fasen aan
  de centrale classificatie toe en dek zowel de vraag als de gecombineerde approval-/stapmelding
  inclusief idempotentie af.
- [info] De volledige story-diff `main...HEAD` is opnieuw beoordeeld. Gerichte reviewchecks waren
  groen: `HumanActionPolicyTest` plus `TelegramNotificationServiceTest` 46/46, de
  documentatie-audit PASS en `git diff --check main...HEAD` schoon. Het groene resultaat bevestigt
  tevens dat de huidige tests de ontbrekende documenter-gates niet afdekken.

## Herstel documenter-gates 2026-08-05

- [x]: `DOCUMENTATION_WITH_QUESTIONS` centraal als `QUESTION` classificeren.
- [x]: `DOCUMENTED` centraal als `APPROVAL_REQUIRED` classificeren wanneer auto-approve uit staat.
- [x]: dashboardactie voor beide documenter-wachtfasen testen.
- [x]: Telegrammelding en idempotentie voor de documentatievraag testen.
- [x]: gelijktijdige approval-/stapmelding en idempotentie voor afgeronde documentatie testen.
- [x]: technische specificatie met de centrale documenter-classificatie actualiseren.
- [x]: gerichte regressie volledig groen afronden.
- [x]: volledige `mvn verify` met 0 failures en 0 errors afronden.
- [x]: volledige repositorygate `tools/verify-repository` afronden.

De documenter gebruikt dezelfde vraag- en goedkeuringssemantiek als de overige AI-rollen. Daarom
zijn de twee ontbrekende fasen toegevoegd aan `HumanActionPolicy`, de gedeelde bron die zowel de
dashboardactie als de Telegramclassificatie voedt. `DOCUMENTED` blijft twee onafhankelijke events
opleveren: de stap is afgerond én wacht op goedkeuring. De tests pollen Telegram tweemaal en bewijzen
dat de afzonderlijke signatures niet opnieuw worden verstuurd.

Bijgewerkt: `docs/factory/technical-spec.md`, zodat expliciet vastligt dat ook de documenterfasen
via de centrale menselijke-actiepolicy worden geclassificeerd.

Gerichte regressie: `HumanActionPolicyTest` en `TelegramNotificationServiceTest` draaiden samen
50 tests groen; `DashboardQueryServiceTest` draaide 74 tests groen. De volledige `mvn verify` vanaf
de repositoryroot eindigde met exitcode 0: alle zes reactormodules waren succesvol, met 861
softwarefactory-unit-tests en 88 Failsafe-/e2e-tests zonder failures of errors.

De eerste repositorygate kwam na groene Maven-, quality- en 142 Fluttertests tot de Docker
build-stage, maar de container had geen `docker`-CLI in `PATH` (exit 127), terwijl Testcontainers de
daemon wel succesvol gebruikte. Buiten de checkout is tijdelijk de officiële aarch64 Docker-client
28.3.0 beschikbaar gemaakt, passend bij daemon 28.3.0. Daarna is de volledige gate vanaf het begin
ongewijzigd herhaald en geëindigd met exitcode 0: contractscripts, clean Maven-reactor, quality-
ratchet, modulecontrole, Flutter-analyse, alle 142 Fluttertests, mini-reactor, Docker build-stage en
documentatie-audit waren groen.

## Review 2026-08-05 (na herstel documenter-gates)

- [bug] AC13 is nog niet afgedwongen op de wijzigingscontracten. Zowel
  `DashboardCommandService.setNotificationEvents` (bridge/dashboard) als
  `TrackerStoryApiController.update` (token-API) accepteren iedere bestaande issue-key en schrijven
  `NOTIFICATION_EVENTS` zonder te controleren dat `issueType == STORY`. Een subtaak-key krijgt zo
  een succesvol antwoord en een zelfstandig opgeslagen eventset, terwijl de story expliciet bepaalt
  dat subtaken geen zelfstandig instelbare set hebben en uitsluitend de actuele parentset gebruiken.
  Weiger subtaak-keys vóór enige write in beide externe updatepaden en voeg regressietests toe die
  bewijzen dat de subtaakrij ongewijzigd blijft.
- [info] De volledige story-diff `main...HEAD` is beoordeeld. Gerichte reviewer-run:
  `HumanActionPolicyTest`, `TelegramNotificationServiceTest`, `DashboardQueryServiceTest`,
  `BridgeRequestHandlerTest`, `TrackerStoryApiControllerTest` en `BridgeApiControllerTest` samen
  214 tests groen; `tools/audit-documentation` PASS en `git diff --check main...HEAD` schoon.

## Herstel story-only eventupdates 2026-08-06

Story in eigen woorden: een concrete notification-eventset is uitsluitend eigendom van een story.
De bridge/dashboardroute en token-API mogen daarom nooit een eventset op een subtaak opslaan, ook
niet wanneer een geldige subtaak-key aan het bestaande updatecontract wordt meegegeven.

- [x]: `DashboardCommandService.setNotificationEvents` het issue laten lezen en subtaken vóór de
  tracker-write weigeren.
- [x]: `TrackerStoryApiController.update` een partial update met `notificationEvents` op een
  subtaak vóór iedere partial write met HTTP 400 laten weigeren.
- [x]: regressietest voor de bridge/dashboardroute toevoegen die `INVALID_PARAMS` en nul writes
  bewijst.
- [x]: regressietest voor de token-API toevoegen die HTTP 400 en nul writes bewijst.
- [x]: functionele, technische en bridge-documentatie met de afgedwongen story-only writeguard
  actualiseren.
- [x]: gerichte regressietests met 0 failures en 0 errors afronden.
- [x]: volledige `mvn verify` met 0 failures en 0 errors afronden.
- [x]: volledige repositorygate `tools/verify-repository` afronden.

Beide routes valideren nog steeds eerst de exacte eventnamen. Daarna lezen ze het doelissue en
controleren ze expliciet `IssueType.STORY` vóór `updateIssueFields`. De token-API voert deze guard
alleen uit als de partial update daadwerkelijk `notificationEvents` bevat; bestaande updates van
andere subtaakvelden blijven daardoor buiten deze gerichte wijziging. De regressietests gebruiken
een echte `Task`-fixture en bewijzen via de geregistreerde trackerwrites dat de subtaakrij niet
wordt gewijzigd.

Bijgewerkt: `docs/factory/functional-spec.md`, `docs/factory/technical-spec.md` en
`docs/ontwerp-bridge-dashboard.md`, zodat zowel het story-only eigenaarschap als de afwijzing vóór
iedere write expliciet beschreven zijn.

Gerichte regressie: `BridgeRequestHandlerTest` en `TrackerStoryApiControllerTest` draaiden samen
58 tests met 0 failures en 0 errors.

Volledige backendverificatie: `mvn verify` vanaf de repositoryroot eindigde met exitcode 0. Alle
zes reactormodules waren succesvol en zowel de unit- als volledige Failsafe-/Testcontainerslaag
eindigde met 0 failures en 0 errors.

De eerste repositorygate kwam na de groene Maven-, quality- en Flutterstappen tot de Docker
build-stage, maar de runner had geen `docker`-CLI in `PATH` (exit 127); Testcontainers had de daemon
wel al succesvol gebruikt. Buiten de checkout is daarom tijdelijk de officiële aarch64
Docker-client 28.3.0 beschikbaar gemaakt, gelijk aan de daemonversie. Vervolgens is de volledige
gate ongewijzigd vanaf het begin herhaald en geëindigd met exitcode 0: contracttests, clean
Maven-reactor (863 softwarefactory-unit-tests en de volledige Failsafe-/e2e-laag), quality-ratchet,
modulecontrole, Flutter-analyse, alle 142 Fluttertests, mini-reactor, Docker build-stage en
documentatie-audit waren groen.
