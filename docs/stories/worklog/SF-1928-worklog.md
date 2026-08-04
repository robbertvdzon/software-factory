# SF-1928 - Worklog

## Story in eigen woorden

Het Opruimen-scherm laat zien wat de factory heeft opgeruimd, maar je kunt een opruiming niet zelf
starten — je wacht tot de cron of de poller weer afgaat. Deze subtaak (SF-1929) bouwt de backend
daarvoor: per opruimsoort één aanroepbare "doe één ronde"-ingang die zowel de scheduler als een
handmatig verzoek gebruikt, een bewaking zodat er nooit twee rondes van dezelfde soort tegelijk
lopen, een `trigger`-kolom (`scheduled`/`manual`) zodat het scherm handmatige rondes kan herkennen,
en de bridge-operatie + HTTP-endpoint waar de knop (SF-1930) straks op klikt.

## Checklist

- [x]: story, factory-docs en bestaande opruimcode gelezen
- [x]: migratie `V32` (`trigger TEXT NOT NULL DEFAULT 'scheduled'`) + repository/DTO's
- [x]: één ronde-entrypoint per soort, gedeeld door scheduler en handmatige route
- [x]: dubbel-draaien-bescherming (`CleanupRunGuard`), geldig voor beide paden
- [x]: niet-blokkerende start op een executor, statussen `started/already_running/disabled/unknown_kind`
- [x]: handmatige ronde levert altijd een rij op; scheduled-onderdrukking blijft
- [x]: root-package-poorten voor dashboard (`runtime.CleanupRunNowApi`, `maintenance.MaintenanceCleanupApi`)
- [x]: bridge-operatie `maintenance.runNow` + `POST /api/v1/maintenance/run`
- [x]: `maintenance.cleanupsList` geeft `runningKinds` terug
- [x]: unit-tests (starten per soort, alles, dubbel-draaien ×2, altijd-loggen, foutpad, disabled, bridge ×2, migratie)
- [x]: `mvn verify` groen vanaf de repo-root
- [x]: kwaliteitsratchet gedraaid en het resultaat geduid
- [x]: documentatie bijgewerkt (`docs/technical/scheduled-jobs.md`, `docs/factory/technical-spec.md`,
       `docs/technical/module-dependencies.md` via `tools/generate-module-dependencies`)

## Wat er gedaan is, en waarom

### Datamodel

`V32__maintenance_cleanup_trigger.sql` voegt `trigger TEXT NOT NULL DEFAULT 'scheduled'` toe. Vrije
TEXT met de waarden als afspraak in code (`CleanupTriggers`), net als `kind` in V31 — een enum-tabel
of CHECK zou elke nieuwe aanleiding een migratie kosten. Bestaande rijen komen per definitie van een
geplande ronde, dus de DEFAULT volstaat. `MaintenanceCleanupRunRecord`/`NewMaintenanceCleanupRun` en
de lees-DTO's van `cleanupsList`/`cleanupDetail` dragen het veld mee.

### Eén ronde, twee triggers

Er is bewust geen tweede implementatie bijgekomen:

- `CleanupRunner` (runtime :: services) is de gedeelde vorm: `cleanupKind`, `cleanupEnabled()` en
  `runCleanupRoundLocked(trigger)`. De bestaande pollers (`AgentEventRetentionPoller`,
  `AgentRunRetentionPoller`, `WorkCleanupPoller`) implementeren 'm; hun `poll()` roept nu
  `CleanupLogWriter.runGuarded(...)` aan met dezelfde `cleanupOnce()`.
- `CleanupLogWriter` is uitgebreid van "logregel schrijven" naar "ronde uitvoeren + logregel":
  `runGuarded` (enabled-check + bewaking + logregel, het geplande pad) en `runLocked` (de aanroeper
  houdt de bewaking al vast, het handmatige pad). Zo staat de suppressieregel op één plek.
- De payload-purge zat inline in `AgentRunCompletionService.reconcileDurableCompletions()` en is
  verhuisd naar een eigen component `CompletionPayloadCleanup` (met de `SF_COMPLETION_RETENTION_DAYS`-
  configuratie mee). De ~2s-poll roept `purgeScheduled()` aan; het gedrag (alleen loggen bij werk of
  fout) is ongewijzigd. Reden voor de extractie: de purge moest van buitenaf aanroepbaar worden, en
  `AgentRunCompletionService` was al zo groot dat elke toevoeging de LargeClass-grens raakte.
- De GitHub-cleanup houdt zijn ronde in `MaintenanceCleanupScheduler`, nu als
  `runCleanupRoundLocked(trigger)`; `tick()` pakt eerst de bewaking en doet daarna de log-retentie
  (`purgeOldRuns`), die bewust aan de cron blijft hangen.

### Dubbel-draaien-bescherming

`maintenance.CleanupRunGuard` (poort in het root-package) met één implementatie
`maintenance.services.InMemoryCleanupRunGuard`: een set met de soorten die nu draaien, in het
geheugen van de factory-JVM. Zowel de schedulers als de handmatige route gebruiken 'm, dus
handmatig+handmatig, handmatig+scheduler en scheduler+handmatig leveren allemaal één ronde op.

`MaintenanceCleanupScheduler` krijgt de bewaking via een `@Autowired(required = false)`-setter in
plaats van een zevende constructorparameter: bij precies 7 ctor-params slaat detekts
LongParameterList aan en dat zou een nieuwe blokkerende ratchet-bevinding zijn. Zelfde patroon als
`AgentRunCompletionService.configureDurableCompletion`.

### Asynchroon + antwoordvorm

`CleanupRunNowService` (runtime :: services) claimt de bewaking **synchroon** — anders zou een tweede
snelle klik ook `started` krijgen — en zet de ronde daarna op een executor met daemon-threads. Het
antwoord volgt `audit.runNow`: HTTP 200 met `status` (`started`, `already_running`, `disabled`,
`unknown_kind`) plus `kinds` (per soort, nodig voor "alles draaien"). De executor is géén
constructorparameter maar een secundaire-constructor-seam: de Spring-context heeft twee
`Executor`-beans (`applicationTaskExecutor`, `taskScheduler`) en zou anders op ambiguïteit stuklopen.

### Modulith

Twee root-package-poorten volgens het precedent `runtime.AgentLogApi`/`pipeline.DeployTargetStatusApi`:
`runtime.CleanupRunNowApi` (vier factory-brede opruimers + doorgeefluik naar GitHub) en
`maintenance.MaintenanceCleanupApi` (de GitHub-ronde). `bridge` blijft buiten `runtime`/`maintenance`
en loopt via `DashboardCommands`/`DashboardQueries`. `ModulithArchitectureTest` is groen; aan
`allowedDependencies` zijn alleen het root-package `maintenance` en de *named interface*
`maintenance :: types` (de `CleanupRunStatus`-enum, exact zoals `audit :: types` bij `audit.runNow`)
toegevoegd — geen interne subpackages. `ModuleApiConventionTest` (shrink-only allowlist) dwong die
opzet trouwens af: root-packages mogen alleen interfaces bevatten, dus de enum staat in
`maintenance :: types`, het datamodel in `runtime :: models` en de guard-implementatie in
`maintenance :: services`.

### Bridge / API

`maintenance.runNow` is aangehangen in het bestaande `dispatchSystemAction`-blok (geen vijfde
when-blok, i.v.m. de LongMethod-ratchet). `POST /api/v1/maintenance/run` volgt het
`POST /api/v1/audits/run-now`-patroon (autorisatie + `respond()`-foutvertaling).
`maintenance.cleanupsList` geeft naast `runs` nu `runningKinds` terug.

## Bewijs

- `mvn -B clean verify` vanaf de repo-root: **BUILD SUCCESS** (04-08-2026, 4m40).
  Tests: factory-contracts 16, factory-common 55, softwarefactory 869, agentworker 61,
  dashboard-backend 59 — 0 failures, 0 errors.
- `tools/audit-documentation`: PASS. `tools/check-composition-roots`: PASS (27 paden).
  `tools/generate-module-dependencies --check`: actueel. `tools/test-verify-repository`: valid.
- `./quality/run.sh`: `findingCount 767`, `newSuppressions []`, `new` bevat **uitsluitend** twee
  `TooManyFunctions`-bevindingen op `orchestrator/repositories/RunRepositories.kt` en
  `core/contracts/RunRepositories.kt`. Die komen **niet** uit deze diff: een detekt+ratchet-run op een
  schone `git worktree` van HEAD geeft exact dezelfde twee (`findingCount 762`, `resolved 2`). Het is
  bestaande drift op `main` op interfaces die deze story niet raakt; ze opsplitsen is een aparte,
  risicovolle ingreep. De baseline is bewust niet opgerekt. De drie bevindingen die mijn eerste opzet
  wél toevoegde (LargeClass op `AgentRunCompletionService`, twee keer ReturnCount) zijn écht
  opgelost: de purge is geëxtraheerd en de twee helpers zijn naar één `when`-expressie herschreven.

## Testdekking (nieuw of uitgebreid)

- `CleanupRunNowServiceTest`: starten per soort, GitHub via de poort, "alles draaien",
  alles-met-overgeslagen-soort, twee snelle klikken, scheduler-draait-al, uitgezet, onbekende soort,
  falende ronde laat de bewaking niet hangen.
- `CleanupLogWriterTest`: handmatige ronde logt altijd (ook 0 items), foutpad met `trigger = manual`,
  geplande ronde blijft onderdrukt, `runGuarded` slaat over bij een lopende ronde, disabled draait niet.
- `CompletionPayloadCleanupTest`: uitgezet zonder coordinator, handmatige rij, overslaan bij lopende ronde.
- `AgentRunRetentionPollerTest`: handmatige ronde met `trigger = manual`, geplande tick slaat over.
- `MaintenanceCleanupSchedulerTest`: `scheduled`/`manual` op de rijen, handmatige ronde doet dezelfde
  deletes en raakt de log-retentie niet, tick slaat over bij een lopende ronde.
- `MaintenanceCleanupRunMigrationTest`: bestaande rij is na `V32` `scheduled`.
- `MaintenanceCleanupRunRepositoryTest`: trigger-rondgang met default.
- `BridgeRequestHandlerTest`: `maintenance.runNow` per soort en met `all`, `INVALID_PARAMS` zonder
  `kind`, plus `runningKinds` in `cleanupsList`.
- `BridgeApiControllerTest` (dashboard-backend): `POST /api/v1/maintenance/run` vertaalt naar
  `maintenance.runNow`, en is zonder token unauthorized.

## Aangepaste specs

- `docs/technical/scheduled-jobs.md`: §7 aangevuld (bewaking + gedeelde ronde, cron ongewijzigd) en
  nieuwe §9 over de handmatige route, de statussen en het `trigger`-veld.
- `docs/factory/technical-spec.md` §Opruimen: nieuwe alinea "Nu draaien" met poorten, statussen,
  bewaking en de altijd-loggen-regel.
- `docs/technical/module-dependencies.md`: gegenereerd met `tools/generate-module-dependencies` na de
  wijziging aan de `package-info.java`'s van `runtime` en `dashboard`.

## Opengelaten voor de volgende subtaken

- SF-1930 (frontend): knoppenrij, `handmatig`-badge en polling op `runningKinds`.

## SF-1930 — Frontend (04-08-2026)

### Story in eigen woorden (deze subtaak)

De backend kan sinds SF-1929 een opruimronde handmatig starten; het Opruimen-scherm gebruikte dat nog
niet. Deze subtaak zet er de knoppen op (per soort één plus "Alles draaien"), toont per status een
begrijpelijke melding, houdt een knop uit zolang die soort draait, laat de lijst na het starten
vanzelf bijwerken zolang er nog iets loopt, en markeert handmatige rondes met een `handmatig`-badge
in de lijst én op de detailpagina.

### Checklist

- [x]: knoppenrij (`Wrap` met `TextButton`s in een `Panel`, stacking onder 560px) met per soort
       een knop plus "Alles draaien"
- [x]: `POST /api/v1/maintenance/run` via `postJson`, met `kind` (een `cleanupKinds`-waarde of `all`)
- [x]: melding per status via `showActionResult` (`started`/`already_running`/`disabled`/`unknown_kind`),
       plus de gestart/overgeslagen-opsomming voor "Alles draaien"
- [x]: knop uit tijdens een eigen verzoek én zolang `runningKinds` die soort meldt
- [x]: `GlobalKey<DataScreenState>` voor herladen na een start, bij een filterwissel en tijdens pollen
- [x]: pollen elke 3s zolang er iets draait, en stoppen zodra `runningKinds` leeg is
- [x]: `handmatig`-badge in de lijst en op het detailscherm
- [x]: widget-tests uitgebreid (POST, melding, knop uit, "Alles draaien", draait-al, uitgezet,
       foutpad, badge, polling-start-en-stop) + mutatiecheck
- [x]: `flutter analyze`, `flutter test` en `mvn verify` groen
- [x]: `docs/factory/functional-spec.md` en `docs/factory/ux/screen-map.md` bijgewerkt

### Wat er gedaan is, en waarom

**`maintenance_screen.dart`.** De `DataScreen` had een `ValueKey(_kind)` om bij een filterwissel te
herladen; dat kan niet samen met de `GlobalKey<DataScreenState>` die nodig is om ná een start te
herladen. De key is nu de GlobalKey en de filterwissel roept zelf `reload()` aan — hetzelfde effect,
één mechanisme minder.

De knoppen dragen de soort-sleutel als label (`github-releases`, `agent-events`, …), in lijn met de
keuze uit SF-1921 om de sleutel zelf te tonen in plaats van een vertaallaag; de rij begint met het
kopje "Nu draaien:". `_canStart` combineert twee redenen om uit te staan: er loopt een eigen verzoek
(`_busyKind`), of de backend meldt die soort in `runningKinds` — dat laatste dekt ook een tweede tab
en een lopende scheduler-ronde. "Alles draaien" blijft aan zolang er nog één vrije soort is.

Pollen gebeurt met één `Timer.periodic(3s)` die in de builder aan/uit gaat op basis van
`runningKinds` (`_syncPolling`): loopt er iets, dan herladen we door; is de lijst leeg, dan wordt de
timer gecanceld. `dispose()` cancelt 'm ook, zodat een weggenavigeerd scherm niet doorpollt.

`already_running` levert bewust een **niet-rode** melding op. De review van SF-1929 wees erop dat de
completion-payload-purge de bewaking elke ~2s kortstondig bezet; "draait al" is daar een normale
uitkomst en geen fout. `disabled`, `unknown_kind` en een echte exceptie blijven wel rood.

Voor "Alles draaien" gebruikt de melding de `kinds`-map uit het antwoord:
"Gestart: … Overgeslagen: x (draait al), y (uit)." — precies de scope-eis dat het antwoord vertelt
wat er gestart is en wat is overgeslagen.

De `handmatig`-badge (`BadgeTone.active`) hangt aan `trigger == 'manual'` en staat naast de
bestaande soort-, dry-run- en fout-badges; een respons zonder `trigger` valt terug op "gepland".

### Bewijs

- `flutter analyze`: **No issues found!** (6,5s)
- `flutter test`: **All tests passed** — 131 tests, waarvan 17 in `maintenance_screen_test.dart`.
- `mvn -B clean verify` vanaf de repo-root: **BUILD SUCCESS**, 0 failures / 0 errors (de diff raakt
  geen JVM-module, maar het vangnet is volledig gedraaid).
- Mutatiecheck: `_syncPolling` z'n timer uitzetten laat de polling-test vallen, en `_canStart`
  altijd `true` laten teruggeven laat de uitgeschakelde-knop-test vallen — beide tests zijn dus geen
  vacuüm groen.

### Testdekking (nieuw)

`test/screens/maintenance_screen_test.dart` (+11 tests): knoppenrij compleet; klikken doet de POST
met de juiste `kind`, zet álle knoppen uit zolang het verzoek loopt (gate-`Completer`), toont
"Opruimronde gestart voor …" en herlaadt de lijst; "Alles draaien" post `kind=all` en noemt gestarte
én overgeslagen soorten; `already_running`, `disabled` en een HTTP-fout leveren elk hun eigen
melding; een soort uit `runningKinds` heeft een uitgeschakelde knop terwijl de andere knoppen aan
blijven; het scherm pollt door zolang er iets draait en stopt daarna (de test zou anders vallen op
een pending timer); `handmatig`-badge in lijst én detail, en géén badge bij een geplande ronde.

### Aangepaste specs

- `docs/factory/functional-spec.md` §Opruimen: alinea over de knoppen, de meldingen, het
  automatisch bijwerken en de `handmatig`-badge; de zin "Er is geen 'Run now'-knop" klopte niet meer.
- `docs/factory/ux/screen-map.md` (`/maintenance`-rij): knoppenrij, statussen, uitgeschakelde
  knoppen op `runningKinds`, het pollen en de extra badge; ook daar stond nog "no run now".

## Review SF-1929 (04-08-2026)

Akkoord. De volledige story-diff t.o.v. `main` is doorgelopen (41 bestanden): datamodel/V32, de
gedeelde ronde-entrypoints, `CleanupRunGuard`, `CleanupRunNowService`, de bridge-operatie en de
dashboard-DTO's. De bewaking is aantoonbaar één gedeelde singleton (poort in `maintenance` root,
`InMemoryCleanupRunGuard` als enige `@Component`), die zowel `CleanupLogWriter.runGuarded` (geplande
pad), `MaintenanceCleanupScheduler.tick()` (setter-injectie), `CleanupRunNowService` (handmatig) als
`DashboardQueryService.runningKinds` gebruiken — daarmee dekt AC5/AC6 beide richtingen.
Cron-expressies en poll-intervallen zijn ongewijzigd (AC9); `purgeOldRuns` blijft aan de cron.

Gerichte hercontrole naast het developerbewijs (45s, exit 0):
`mvn -pl factory-common,softwarefactory,dashboard-backend -am test -Dtest=CleanupRunNowServiceTest,
CleanupLogWriterTest,CompletionPayloadCleanupTest,AgentRunRetentionPollerTest,
MaintenanceCleanupSchedulerTest,BridgeRequestHandlerTest,ModulithArchitectureTest,
ModuleApiConventionTest,BridgeApiControllerTest,DashboardQueryServiceTest` → BUILD SUCCESS, 0
failures/errors (o.a. ModulithArchitectureTest 4, ModuleApiConventionTest 5, BridgeRequestHandlerTest
44, BridgeApiControllerTest 32). Daarnaast `tools/generate-module-dependencies --check` (actueel),
`tools/audit-documentation` (PASS) en `tools/check-composition-roots` (PASS, 27 paden).

Niet-blokkerende punten voor een volgende subtaak:

- `docs/technical/scheduled-jobs.md` §8 noemt de payload-purge nog "in
  `AgentRunCompletionService.reconcileDurableCompletions()`"; die zit nu in `CompletionPayloadCleanup`
  (SF-1933 kan dat rechttrekken).
- `CleanupLogWriter.runGuarded` geeft een `CleanupRunStatus` terug die geen productie-aanroeper leest
  (de pollers negeren 'm); alleen tests gebruiken de returnwaarde.
- Een handmatige `github-releases`-ronde in een factory zónder project met `releaseCleanup:` levert
  geen enkele rij op — het "altijd een rij"-gedrag geldt daar via de per-project-lus, niet per ronde.
- De ~2s completion-payload-poll bezet de bewaking kortstondig; SF-1930 moet daar tegen kunnen
  (knop even uit is acceptabel, maar niet als foutmelding presenteren).

## Review SF-1930 (04-08-2026)

Akkoord. De volledige story-diff t.o.v. `main` is opnieuw doorgelopen (45 bestanden); de
backend-helft is ongewijzigd sinds de SF-1929-review, deze ronde is inhoudelijk op de frontend
gericht.

Gecontroleerd en in orde:

- **Contract-fit.** `POST /api/v1/maintenance/run` met `{"kind": …}` sluit aan op
  `MaintenanceRunNowRequest`; de gelezen velden `started`/`status`/`kinds` komen exact uit
  `BridgeRequestHandler.CleanupRunNowBody`, en `runningKinds`/`trigger` uit
  `MaintenanceCleanupListPageData` resp. de summary/detail-views. De frontend-constante
  `cleanupKinds` is één-op-één gelijk aan `CleanupKinds.ALL` (zelfde volgorde) en
  `allCleanupKinds = 'all'` aan `CleanupKinds.ALL_KINDS` — `_canStart('all')` klopt daarmee.
- **AC1/AC9.** Knoppenrij is een `Panel` met een `Wrap` van `TextButton`s plus de 560px-stacking,
  gelijk aan `audit_screen.dart`; het soort-filter en het detailscherm blijven werken (de
  `ValueKey(_kind)` → `GlobalKey` + expliciete `reload()` is een correcte 1-op-1-vervanging, met
  regressiedekking in de bestaande filter- en detailtests).
- **AC3/AC4.** `_syncPolling` start/stopt één `Timer.periodic(3s)` op basis van `runningKinds` en
  `dispose()` cancelt 'm; `DataScreenState._load()` zet `loading = data == null`, dus een poll-tick
  ververst zonder spinner-flikkering.
- **AC5/AC7/AC8.** Statusteksten dekken `started`/`already_running`/`disabled`/`unknown_kind` met de
  `started`-fallback uit `_runNowMessage` in `audit_screen.dart`; `already_running` is bewust niet
  rood — precies het review-punt uit SF-1929 over de ~2s payload-purge.
- **Specs.** `docs/factory/functional-spec.md` §Opruimen en de `/maintenance`-rij in
  `docs/factory/ux/screen-map.md` zijn bijgewerkt; de oude "geen Run now-knop"-zinnen staan er niet
  meer in. Geen scope creep: buiten `maintenance_screen.dart`, de bijbehorende test en die twee
  docs is er in deze subtaak niets aangeraakt.

Gerichte hercontrole naast het harness-geverifieerde developerbewijs:
`flutter analyze` → "No issues found!" (6,3s) en
`flutter test test/screens/maintenance_screen_test.dart` → **17 tests, All tests passed**
(7 bestaand + 10 nieuw). Werktree bleef schoon; `flutter pub get` liet `pubspec.lock` ongewijzigd.

Niet-blokkerende punten:

- [info] AC5 letterlijk gelezen ("de tweede klik levert een zichtbare melding") wordt in dezelfde
  tab niet gehaald: de knop staat dan uit, dus de tweede klik doet niets in plaats van "draait al"
  te melden. Dat is wat de subtaakomschrijving expliciet vraagt (knop uitschakelen), en het
  `already_running`-pad is wél gedekt voor de tweede-tab-/scheduler-situatie.
- [info] `_canStart` zet álle knoppen uit zolang er één verzoek loopt, niet alleen de aangeklikte
  soort. Veilig, maar iets strenger dan nodig.
- [info] Faalt een poll-tick, dan toont `DataScreen` de foutstaat en wordt de builder niet meer
  aangeroepen, waardoor de 3s-timer doorloopt tot een tick weer slaagt of het scherm wordt
  weggenavigeerd. Zelfherstellend, dus geen bevinding.
