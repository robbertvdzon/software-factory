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
