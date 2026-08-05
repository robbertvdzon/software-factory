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
