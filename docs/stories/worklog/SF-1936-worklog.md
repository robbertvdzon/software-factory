# SF-1936 - Worklog

Story-context bij eerste pickup:
Deel 1: paginatie in de GitHub-cleanup-clients

Voeg paginatie toe aan de maintenance-clients in softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/maintenance/services/. Zet een gedeelde, pure paginatielus neer die een 'haal pagina n op'-functie als parameter krijgt en gelukt-vol / gelukt-deelpagina / mislukt onderscheidt; stop zodra een pagina minder dan per_page (100) items levert (zonder extra call) of bij het paginamaximum. Voeg een paginagrens-property toe aan MaintenanceCleanupSettings (sf.maintenance.*, default 20 pagina's) met een waarschuwing incl. package-/reponaam en aantal items als de grens geraakt wordt. Pas GitHubPackageCleanupClient.listVersions en GitHubReleaseCleanupClient.listReleases aan: faalt pagina 1 -> lege lijst (huidig gedrag), faalt pagina n>1 -> teruggeven wat al is opgehaald met een waarschuwing in de log. GitHubProtectedShaSource.openPullRequestHeadShas pagineert eveneens, maar is fail-safe: bij een mislukte of onvolledige lijst wordt de package-cleanup voor dat project deze ronde overgeslagen en komt dat als error op de logregel van die projectronde (MaintenanceCleanupScheduler). Ongewijzigd: ontbrekend SF_GITHUB_PACKAGES_TOKEN -> lege lijst + eenmalige waarschuwing en geen enkele HTTP-call; de contents-call en de retentie-planners blijven buiten scope. LET OP: nieuwe constructorparameters altijd met default, want de fakes in MaintenanceCleanupSchedulerTest construeren positioneel met de 1-arg constructor. Schrijf zelf de tests: meerdere pagina's samenvoegen (bv. 100+100+37=237), stoppen bij deelpagina zonder extra call, mislukte eerste en mislukte vervolgpagina, paginagrens gerespecteerd, ontbrekend token, fail-safe pull-requests-pad, paginatie van listReleases, en een scheduler-test die met een fake-client van 350 package-versions en keep=15 alle 335 overtollige versies verwijdert. Sluit af met een eigen review-stap en mvn verify.

## In eigen woorden

De nachtelijke opruimer keek maar naar de eerste pagina die GitHub teruggaf (100 items). Bij een
achterstand van honderden releases/images ruimde één ronde daarom maar een fractie op en duurde het
dagen voor de bewaarregels klopten. Deel 1 (SF-1938) lost dat op met een gedeelde paginatielus in de
drie GitHub-cleanup-clients, een configureerbare bovengrens, en fail-soft/fail-safe foutafhandeling.
Deel 2 (het Opruimen-scherm per actie) zit in SF-1939 en valt buiten deze subtaak.

## Stappenplan

[x]: read issue and target docs
[x]: gedeelde, pure paginatielus (`GitHubPagination`) met test-seams in de drie clients
[x]: paginagrens-property `sf.maintenance.github-page-limit` (default 20) in `MaintenanceCleanupSettings`
[x]: `GitHubPackageCleanupClient.listVersions` + `GitHubReleaseCleanupClient.listReleases` pagineren (fail-soft)
[x]: `GitHubProtectedShaSource` pagineert fail-safe; scheduler slaat package-cleanup over met `error`
[x]: zelf tests geschreven (lus, drie clients, scheduler-ronde met 350 versions)
[x]: docs bijgewerkt (technical-spec, functional-spec, scheduled-jobs §7)
[x]: volledig vangnet gedraaid (`mvn verify` vanaf de repo-root)

## Done / rationale

- **`maintenance/services/GitHubPagination.kt` (nieuw).** De lus kent geen HTTP: hij krijgt een
  `(page: Int) -> GitHubPage<T>`-functie en is dus volledig zonder netwerk te testen. `GitHubPage`
  onderscheidt `Fetched(items, rawCount)` van `Failed`; `PagedItems` draagt de items plus
  `failedPage`/`pageLimitReached` en daarmee een afgeleide `complete`.
  - Stoppen gebeurt op het **ruwe** aantal array-elementen, niet op het aantal geparste items:
    de parsers gooien ongeldige elementen weg, dus anders zou één onparseerbaar element op een volle
    pagina de hele rest van de historie onzichtbaar maken. Er is een expliciete test voor.
  - De lus is geschreven zonder `break` (één `while`-conditie + een `mayHaveMore`-vlag) en met één
    `return`, zodat detekt's `LoopWithTooManyJumpStatements` (max 1) en `ReturnCount` (max 2) niet
    alsnog een nieuwe ratchet-bevinding opleveren.
- **Paginagrens.** `MaintenanceCleanupSettings.githubPageLimit`
  (`sf.maintenance.github-page-limit`, default 20 = 2000 items). Alle velden van die data class
  hebben nu óók een Kotlin-default met dezelfde waarde als de property-default, zodat de clients de
  bean als *optionele* constructorparameter kunnen nemen (`= MaintenanceCleanupSettings()`) — dat was
  de expliciete eis, want de fakes in `MaintenanceCleanupSchedulerTest` construeren positioneel met
  de 1-arg constructor. Die fakes zijn dan ook ongewijzigd blijven werken.
- **Fail-soft (releases + package-versions).** `listVersions`/`listReleases` gebruiken de lus; faalt
  pagina 1 dan komt er een lege lijst uit (bestaand gedrag), faalt pagina *n>1* dan komt terug wat al
  is opgehaald. Bij een gefaalde pagina én bij het raken van de paginagrens gaat er één
  waarschuwing uit met package-/reponaam en het aantal opgehaalde items
  (`GitHubPagination.warnIfIncomplete`).
- **Fail-safe (beschermde sha's).** `GitHubProtectedShaSource.protectedTags` geeft nu een
  `ProtectedTags(tags, complete)` terug in plaats van een kale `Set<String>`. Dat is bewust een
  signatuurwijziging en geen extra methode: een tweede methode naast de bestaande zou door de
  handgeschreven fake (die alleen `protectedTags` overschrijft) heen vallen en stilletjes echte
  HTTP-calls doen. `MaintenanceCleanupScheduler.cleanupPackages` slaat bij `complete == false` de
  hele package-cleanup voor dat project over en zet dat als `error` op de logregel van die
  projectronde; de release-cleanup van diezelfde ronde loopt gewoon door. Ook het raken van de
  paginagrens telt hier als "niet compleet" — de story-description noemt expliciet "mislukte of
  onvolledige lijst", en een halve veiligheidslijst zou beschermde preview-images laten verwijderen.
  Daarvoor kregen `CleanupStep`/`CleanupOutcome` een `error`-veld.
- **Ongewijzigd gelaten:** de `contents`-call (geen lijst), `ReleaseRetentionPlanner`,
  `PackageVersionRetentionPlanner`, `projects.yaml`, de dashboard-clients en de deletes (geen
  throttling — zie de aanname in de story).

### Tests (zelf geschreven)

- `GitHubPaginationTest` (nieuw, 8 tests): 100+100+37 = 237 samengevoegd met exact 3 calls; lege
  eerste/vervolgpagina; mislukte vervolgpagina geeft terug wat opgehaald was; mislukte eerste pagina
  geeft leeg; paginagrens gerespecteerd (5 calls, `pageLimitReached`); grens 0 wordt als 1 behandeld;
  volle-pagina-met-gefilterd-element haalt tóch de volgende pagina op. Elke test assert de lijst
  opgevraagde paginanummers, zodat "en dus géén extra call" hard bewezen is.
- `GitHubPackageCleanupClientTest` (+5): paginatie via de `listVersionsWith`-seam, mislukte eerste/
  vervolgpagina, paginagrens, en **zonder `SF_GITHUB_PACKAGES_TOKEN` wordt er geen enkele pagina
  opgehaald** (de fetch-functie registreert 0 aanroepen — dat is het bewijs voor "geen HTTP-call").
- `GitHubReleaseCleanupClientTest` (+4): dezelfde paginatie op `listReleases`.
- `GitHubProtectedShaSourceTest` (+3): alle pagina's opgehaald en `complete`; een gefaalde pagina én
  het raken van de paginagrens maken de beschermingslijst incompleet.
- `MaintenanceCleanupSchedulerTest` (+2): één ronde met 350 package-versions en `keep = 15`
  verwijdert alle 335 overtollige versies (was ~85 vóór deze story); een onvolledige
  beschermingslijst slaat de package-cleanup over, zet een `error` op de rij en laat de
  release-cleanup van dezelfde ronde staan.

### Documentatie

- `docs/factory/technical-spec.md` §Opruimen: nieuwe alinea over `GitHubPagination`, de property, de
  stopconditie en het verschil fail-soft vs. fail-safe.
- `docs/factory/functional-spec.md` §Opruimen: in gewone taal dat één ronde nu de hele achterstand
  wegwerkt, plus de uitzondering rond preview-images.
- `docs/technical/scheduled-jobs.md` §7: de nieuwe property in de config-regel en een bullet over het
  paginatiegedrag.
- `docs/factory/ux/screen-map.md` en de schermbeschrijving blijven ongemoeid: Deel 1 raakt geen UI.

### Bewijs

- `mvn -o -pl softwarefactory test -Dtest='GitHub*Test,MaintenanceCleanupSchedulerTest,PackageVersionRetentionPlannerTest'`
  → Tests run: 87, Failures: 0, Errors: 0.
- Volledig vangnet `mvn -B clean verify` vanaf de repo-root: **BUILD SUCCESS, exitcode 0**, 4m39,
  0 failures / 0 errors over alle modules (softwarefactory unit 694 + failsafe/e2e 78, agentworker 61,
  dashboard-backend 59, factory-contracts/-common). Geen bestaande rode test aangetroffen, dus geen
  boyscout-herstel nodig.
- Geen nieuwe cross-module afhankelijkheid (alles blijft binnen `maintenance :: services` + `config`),
  dus `package-info.java` en `tools/generate-module-dependencies` bleven ongewijzigd.
- `.factory/verification.yaml` ongewijzigd: de canonieke build/testcommando's veranderen niet.

## Review SF-1939 (05-08-2026) — akkoord

Beoordeeld: de volledige story-diff `git diff main...HEAD` (23 bestanden, Deel 1 + Deel 2). Geen
implementatiebestand aangeraakt tijdens de review; werktree was schoon (alles gecommit).

- **Backend.** `latestPerKindAndProject()` is een eigen query (`DISTINCT ON (kind, COALESCE(project,
  ''))` met dezelfde expressies vooraan in de `ORDER BY`) en dus terecht níet afgeleid uit de op 200
  rijen afgekapte `recent()`; de 220-rijen-test bewijst dat verschil machinaal. `summary` is een
  extra veld mét default op `MaintenanceCleanupListPageData` en de mapping is ontdubbeld naar één
  `toCleanupSummaryView()` — bestaande velden en endpoints ongewijzigd, dus een uitgerolde APK
  breekt niet. Geen migratie nodig. De `kind`-doorgifte in `BridgeApiController` is nu getest,
  inclusief "geen lege `project`-param".
- **Frontend.** Blok per soort uit de vaste `cleanupKinds`-lijst; die lijst is één-op-één (incl.
  volgorde) gelijk aan `CleanupKinds.ALL` r23, dus `_canStart('all')` blijft kloppen.
  `_canStart`/`_runNow`/`_runNowMessage`/`_syncPolling` zijn ongewijzigd overgenomen: knop uit bij
  `runningKinds` én bij een lopend verzoek, dezelfde meldingsteksten per status, herladen + 3 s
  doorpollen. Dropdown en knoppenbalk zijn weg, `Alles draaien` en de foutbanners staan bovenaan.
  `CleanupRunsScreen` heeft eigen `Scaffold`/`AppBar`, laadt `?kind=<kind>` en opent het rondedetail
  volledig ongewijzigd.
- **AC-dekking.** 8–15 zijn elk aan een concrete test te koppelen (21 widget-tests: blokken,
  per-projectregel, "geen wijzigingen gelogd", duur incl. `< 1 s` en ontbrekende eindtijd, badge- en
  foutbanner-regressie, knop-uit-bewijs per `Key('run-now-<kind>')`, pollen, runs-scherm + detail,
  400px-viewport). AC 1–7 waren al akkoord in de review van SF-1938 hierboven.
- **Specs consistent** met de diff: `technical-spec.md` §Opruimen (query, `summary`, schermindeling),
  `functional-spec.md` §Opruimen, `ux/screen-map.md` regel `/maintenance` en
  `scheduled-jobs.md` §7/§9. `.factory/verification.yaml` ongewijzigd (terecht: geen nieuwe
  canonieke commando's).
- **Gerichte hercontrole** (naast het harness-geverifieerde developerbewijs):
  `flutter analyze` → No issues (7,8 s); `flutter test test/screens/maintenance_screen_test.dart`
  → 21/21 groen; `mvn -B -o -pl factory-common,softwarefactory,dashboard-backend -am test
  -Dtest=BridgeApiControllerTest,DashboardQueryServiceTest,BridgeRequestHandlerTest
  -Dsurefire.failIfNoSpecifiedTests=false` → BUILD SUCCESS, 0 failures/errors (45 s);
  `tools/audit-documentation` → PASS. De Testcontainers-repositorytests zijn hier niet te draaien
  (geen docker in de reviewsandbox) en steunen op het groene `mvn clean verify`-bewijs.
- [suggestie] `formatCleanupDuration` geeft bij een `finishedAt` vóór `startedAt` (klokverschuiving)
  `< 1 s` in plaats van `-`. Onwaarschijnlijk en onschadelijk, maar een `isNegative`-check zou het
  netter maken.
- [suggestie] `CleanupRunsScreen` laadt één keer in `initState` en heeft geen ververs-knop; kom je
  vanaf een net gestarte ronde terug, dan zie je die pas na opnieuw openen. Paginering/verversing van
  de historie staat expliciet buiten scope — kandidaat voor een vervolgstory.
- [info] Er is geen service-level test op de `summary`-mapping in `DashboardQueryService`; de
  uiteinden zijn wel gedekt (repository-query-test + widget-tests op het `summary`-veld) en de
  mapping is dezelfde ontdubbelde functie als voor `runs`.
- [info] `latestPerKindAndProject()` staat zonder `WHERE`/`LIMIT`; met de 90-dagenretentie en een
  handvol (kind, project)-combinaties is dat prima, maar het is wel een volledige tabelscan per
  schermlading.

## Review SF-1938 (05-08-2026) — akkoord

Beoordeeld: volledige story-diff `git diff main...HEAD` (15 bestanden, alleen `maintenance/services`
+ docs). Geen implementatiebestand aangeraakt tijdens de review.

- Paginatielus, stopconditie op het *ruwe* aantal, foutafhandeling per pagina, de paginagrens en het
  fail-safe pad van de beschermingslijst kloppen met scope-punten 1–6 en AC 1–7. Elke AC is aan een
  concrete test te koppelen; de `requestedPages`-asserties maken "en dus géén extra call" hard.
- Geen andere aanroepers van `protectedTags`/`listVersions`/`listReleases` buiten
  `maintenance/services` (grep over `--include=*.kt`), dus de signatuurwijziging is contained.
- Specs consistent: `technical-spec.md` §Opruimen, `functional-spec.md` §Opruimen en
  `scheduled-jobs.md` §7 beschrijven de nieuwe property, de stopconditie en fail-soft vs. fail-safe.
  `ux/screen-map.md` hoort bij Deel 2 (SF-1939) en blijft terecht ongemoeid.
- Gerichte hercontrole (naast het harness-geverifieerde developerbewijs):
  `mvn -B -pl factory-common,softwarefactory -am test -Dtest=GitHubPaginationTest,GitHubPackageCleanupClientTest,GitHubReleaseCleanupClientTest,GitHubProtectedShaSourceTest,MaintenanceCleanupSchedulerTest -Dsurefire.failIfNoSpecifiedTests=false`
  → 45 tests, 0 failures / 0 errors, BUILD SUCCESS (36 s).
- [suggestie] `GitHubProtectedShaSource.protectedTags` markeert alleen een mislukte `/pulls`-pagina
  als incompleet; faalt de `contents`-call, dan blijft `complete == true` en kan een manifest-sha
  onbeschermd blijven. Dat is bestaand gedrag en de `contents`-call staat expliciet buiten scope —
  geen blocker, wel een kandidaat voor een vervolgstory.
- [info] Levert een lijst exact `githubPageLimit * 100` items op, dan staat `pageLimitReached` op
  `true` terwijl er niets ontbreekt. Voor de beschermingslijst betekent dat een overgeslagen
  package-cleanup; met 2000 open PR's/versies praktisch onbereikbaar en fail-safe de goede kant op.

## SF-1939 — Deel 2: backend-samenvatting per kind + Opruimen-scherm per actie

### In eigen woorden

Het Opruimen-scherm was één lange lijst met alle rondes van alle opruimers door elkaar, met een
soort-dropdown erboven en een balk met vijf "nu draaien"-knoppen. Wat je eigenlijk wilt weten — "hoe
liep de laatste ronde van déze opruimer af, en kan ik 'm nu draaien?" — moest je zelf uit die lijst
vissen. Deel 2 draait dat om: een blok per opruimactie met het resultaat van de laatste ronde
(verwijderd / blijft staan / duur), een knop om die actie te starten en een knop naar de historie van
alléén die actie. De backend levert daarvoor een samenvatting uit een eigen query, want de bestaande
lijst is op 200 rijen afgekapt en zou een rustige soort onzichtbaar kunnen maken.

### Stappenplan

[x]: read issue, worklog Deel 1 en de bestaande schermen/tests
[x]: `MaintenanceCleanupRunRepository.latestPerKindAndProject()` (laatste rij per kind/project)
[x]: `summary` als extra veld op `MaintenanceCleanupListPageData` + mapping in `DashboardQueryService`
[x]: `BridgeApiController`-doorgifte van de `kind`-queryparameter geborgd met een test
[x]: `maintenance_screen.dart` herbouwd: blok per soort, Nu draaien, Runs bekijken, Alles draaien
[x]: nieuw `CleanupRunsScreen` (eigen Scaffold/AppBar, `?kind=<kind>`), rondedetail ongewijzigd
[x]: duurformattering `formatCleanupDuration` (`1 m 7 s` / `43 s` / `< 1 s` / `-`)
[x]: zelf tests geschreven (3 repository-tests, 1 controller-test, 21 widget-tests)
[x]: docs bijgewerkt (technical-spec, functional-spec, screen-map, scheduled-jobs §7/§9)
[x]: volledig vangnet gedraaid (`mvn clean verify` repo-root, `flutter analyze`, `flutter test`)

### Done / rationale

**Backend.**

- `MaintenanceCleanupRunRepository.latestPerKindAndProject()` — Postgres `SELECT DISTINCT ON (kind,
  COALESCE(project, ''))` met `ORDER BY kind, COALESCE(project, ''), started_at DESC, id DESC`. Het
  `COALESCE` staat er omdat factory-brede rondes `project IS NULL` hebben; zonder dat zou NULL als
  aparte groep gaan sorteren. Bewust een eigen query en geen selectie uit `recent()`: die lijst is op
  200 rijen afgekapt, dus een drukke soort (bijv. de payload-purge) kan de laatste ronde van een
  rustige soort uit beeld duwen. Daar is een expliciete test voor (220 drukke rondes + één oude
  `workspaces`-ronde: `recent()` kent 'm niet meer, de samenvatting wél).
- `MaintenanceCleanupListPageData.summary: List<MaintenanceCleanupRunSummaryView> = emptyList()`.
  Hergebruik van het bestaande view-type in plaats van een nieuw DTO: de gevraagde velden (`id`,
  `kind`, `project`, `startedAt`, `finishedAt`, `itemsDeleted`, `itemsKept`, `dryRun`, `failed`,
  `trigger`) zijn precies die van een lijstregel. Extra veld met default, bestaande velden en
  endpoints ongewijzigd → een al uitgerolde APK blijft werken. Geen databasemigratie.
- `DashboardQueryService.maintenanceCleanups` vult `summary` via hetzelfde soft-fail-`load` als
  `runs` (een onbereikbare database wordt een `errors`-regel, geen crash) en de mapping zit nu in één
  extensie `toCleanupSummaryView()` in plaats van twee keer uitgeschreven. De samenvatting wordt
  bewust *niet* meegefilterd op `project`/`kind`: het runs-scherm per actie gebruikt dezelfde route
  met `?kind=`, en dan hoort de samenvatting nog steeds over alle soorten te gaan.
- `BridgeApiController.maintenanceCleanups` gaf de `kind`-param al door maar was ongetest; er staat
  nu een test op die zowel de doorgifte als "geen lege `project`-param" vastlegt.

**Frontend (`dashboard-frontend/lib/screens/maintenance_screen.dart`).**

- Titel blijft `Opruimen`. Eén `Panel` per soort uit de vaste `cleanupKinds`-lijst (alle vijf altijd
  zichtbaar, ook zonder gelogde ronde) met de actienaam, de samenvattingsregel(s) en twee knoppen.
  Bij `github-releases` één regel per project met een gelogde ronde — alleen weergave, want de poort
  draait één ronde over álle projecten (aanname in de story).
- Resultaat als label/waarde-paren `verwijderd: N` / `blijft staan: M` / `duur: …`, plus
  `laatste ronde: <tijdstip>` en de ongewijzigde badges `dry-run` / `handmatig` / `fout`. Zonder
  gelogde ronde: `laatste ronde: geen wijzigingen gelogd` — neutraal, geen foutmelding.
- `formatCleanupDuration(startedAt, finishedAt)` → `1 m 7 s`, `43 s`, `< 1 s` bij een ronde onder de
  seconde en `-` als een van beide tijdstippen ontbreekt/onparseerbaar is. Bewust in de frontend en
  niet in de backend: het scherm gebruikt dan dezelfde velden als de rest van de weergave, en er komt
  geen afgeleid veld bij dat oude APK's niet kennen.
- Knoppen dragen een `Key('run-now-<kind>')` / `Key('view-runs-<kind>')`. Zonder key zou een test
  vijf identieke `Nu draaien`-knoppen niet uit elkaar kunnen houden; met key is per soort te
  bewijzen dat de juiste knop uit staat.
- Gedrag van "Nu draaien" is één-op-één overgenomen (`_canStart`, `_runNow`, `_runNowMessage`,
  `_syncPolling`): knop uit zolang een verzoek loopt of `runningKinds` die soort meldt, dezelfde
  meldingsteksten per status, herladen na een start en doorpollen (3 s) tot niets meer draait.
  `Alles draaien` (`kind = all`) blijft bovenaan met de bestaande gecombineerde melding; de
  soort-dropdown en de `Nu draaien:`-knoppenbalk zijn vervallen. Foutbanners uit `errors` blijven
  bovenaan.
- Nieuw `CleanupRunsScreen` (publiek, want het is een eigen scherm): eigen `Scaffold`/`AppBar`
  (`Rondes: <kind>`), laadt `/api/v1/maintenance/cleanups?kind=<kind>` en toont de rondes
  nieuwste-eerst met tijdstip, project, `N opgeruimd / M bewaard` en dezelfde badges — precies de rij
  die eerst op het hoofdscherm stond, verplaatst naar `_CleanupRunTile`. Tikken opent het bestaande
  rondedetail (`/api/v1/maintenance/cleanups/{id}`) volledig ongewijzigd.
- Mobiel: titel + knoppen stapelen onder 560px (`LayoutBuilder`, dezelfde drempel als de vervallen
  knoppenbalk) en de regels zitten in `Wrap`s; geen horizontaal scrollende tabel.

### Tests (zelf geschreven)

- `MaintenanceCleanupRunRepositoryTest` (+3, Testcontainers): laatste ronde per soort én per project
  voor `github-releases`; de samenvatting kijkt voorbij de 200-rijenlimiet van `recent()`; leeg zolang
  er niets gelogd is.
- `BridgeApiControllerTest` (+1): `?kind=agent-runs` landt als `kind`-param op `maintenance.cleanupsList`
  en er gaat geen lege `project`-param mee.
- `maintenance_screen_test.dart` herschreven naar 21 tests: blok per soort met verwijderd/blijft
  staan/duur, regel per project bij `github-releases`, de "geen wijzigingen gelogd"-regel (4x),
  duurformattering incl. `< 1 s` en de ontbrekende eindtijd, badge-regressie (`dry-run`/`handmatig`/
  `fout` en "geplande ronde krijgt geen badge"), foutbanner-regressie, het vervallen zijn van
  dropdown en knoppenbalk, POST + knoppen-uit-tijdens-verzoek + melding per status
  (`started`/`already_running`/`disabled`), knop uit bij `runningKinds`, doorpollen-en-stoppen,
  mislukte start, `Runs bekijken` (juiste URL, nieuwste eerst, badges, lege staat), het rondedetail
  met en zonder release-uitsplitsing, en een 400px-viewport-test tegen overflow.
  Praktisch punt: vijf blokken passen niet in het 800x600-testvenster, dus taps lopen via een
  `tapKey`-helper met `ensureVisible` — zonder dat mist `tap()` de onderste knoppen.

### Documentatie

- `docs/factory/technical-spec.md` §Opruimen: de nieuwe repository-query en het `summary`-veld
  (inclusief waaróm het niet uit `recent()` komt) plus de volledige nieuwe schermindeling.
- `docs/factory/functional-spec.md` §Opruimen: in gewone taal het scherm per actie, de neutrale regel
  zonder gelogde ronde en de twee knoppen per actie.
- `docs/factory/ux/screen-map.md` regel `/maintenance`: herschreven naar de blok-per-actie-indeling,
  de widget-keys, `CleanupRunsScreen` en het vervallen van dropdown/knoppenbalk.
- `docs/technical/scheduled-jobs.md` §7 (het `summary`-leespad; de scheduler zelf verandert niet) en
  §9 (de knop hangt nu per actie in zijn eigen blok, gedrag ongewijzigd).

### Bewijs

- `flutter analyze` → No issues found (7,2 s).
- `flutter test` (volledige suite) → **135 tests, all passed**; `maintenance_screen_test.dart` los:
  21/21 groen.
- Volledig vangnet `mvn -B clean verify` vanaf de repo-root: **BUILD SUCCESS, exitcode 0**, 4m43,
  0 failures / 0 errors (factory-contracts 16, factory-common 55, softwarefactory unit 816 +
  failsafe/e2e 78, agentworker 61, dashboard-backend 60). De eerste run viel om op de bekende
  surefire-forkflake (`The forked VM terminated without properly saying goodbye`, `Process Exit
  Code: 0`) ná `Tests run: 811, Failures: 0, Errors: 0` — geen enkele testfailure. Conform de
  agent-tip één keer volledig herdraaid; die run is groen. Geen bestaande rode test aangetroffen,
  dus geen boyscout-herstel nodig.
- `bash tools/audit-documentation` → `documentation-audit/v1: PASS`.
- Geen nieuwe cross-module afhankelijkheid (`dashboard/services` gebruikte `maintenance/repositories`
  al), dus `package-info.java` en `tools/generate-module-dependencies` bleven ongewijzigd.
- `.factory/verification.yaml` ongewijzigd: de canonieke build/testcommando's veranderen niet.
