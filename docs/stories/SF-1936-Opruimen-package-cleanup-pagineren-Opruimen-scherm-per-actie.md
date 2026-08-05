# SF-1936 - Opruimen: package-cleanup pagineren + Opruimen-scherm per actie met resultaat, duur, Nu draaien en runs-historie

## Story

Opruimen: package-cleanup pagineren + Opruimen-scherm per actie met resultaat, duur, Nu draaien en runs-historie

<!-- refined-by-factory -->

## Samenvatting

Het automatisch opruimen van oude releases en container-images werkt te langzaam: per ronde
wordt maar een klein stukje opgeruimd, waardoor het dagen duurt voordat de ingestelde
bewaarregels kloppen. Dat wordt opgelost zodat één ronde de volledige achterstand wegwerkt.

Daarnaast wordt het Opruimen-scherm begrijpelijker. Nu zie je één lange lijst van alle
opruimrondes door elkaar. Straks zie je een lijst van de opruim-acties zelf, met per actie
hoe de laatste ronde afliep (hoeveel weg, hoeveel bewaard, hoe lang het duurde), een knop om
die actie meteen te draaien, en een knop naar de historie van alleen die actie.

## Scope

### Deel 1 — paginatie in de GitHub-clients

In `softwarefactory/.../maintenance/services/`:

1. `GitHubPackageCleanupClient.listVersions` haalt alle pagina's op (`page=1..n`, `per_page=100`)
   en stopt zodra een pagina minder dan `per_page` items teruggeeft of leeg is.
2. Dezelfde behandeling voor `GitHubReleaseCleanupClient.listReleases` (`/releases`) en voor de
   `/pulls?state=open`-call in `GitHubProtectedShaSource.openPullRequestHeadShas`; die hebben
   aantoonbaar dezelfde beperking. De `contents`-call blijft ongewijzigd (geen lijst).
3. Bovengrens op het aantal pagina's, configureerbaar via een `sf.maintenance.*`-property met
   default 20 (= 2000 items). Wordt de grens geraakt, dan een waarschuwing in de log met
   package/repo-naam en het aantal opgehaalde items.
4. Foutafhandeling per vervolgpagina: faalt pagina *n>1* (niet-2xx, timeout, rate limit), dan
   wordt teruggegeven wat al is opgehaald, met een waarschuwing in de log. Faalt pagina 1, dan
   blijft het huidige gedrag (lege lijst).
5. Uitzondering op regel 4 voor `GitHubProtectedShaSource`: die levert een *veiligheids*lijst.
   Een onvolledig resultaat daar zou beschermde images alsnog laten verwijderen. Bij een
   mislukte pagina wordt de package-cleanup voor dat project deze ronde overgeslagen en komt
   dat als `error` op de logregel van die projectronde te staan.
6. De paginatielus moet zonder echte HTTP-call te testen zijn (bijvoorbeeld een pure functie
   die een "haal pagina n op"-functie als parameter krijgt). De bestaande handgeschreven
   subklasse-fakes in `MaintenanceCleanupSchedulerTest` blijven werken: nieuwe
   constructorparameters krijgen een default, want die fakes construeren positioneel.

Buiten scope: het retentie-algoritme zelf (`ReleaseRetentionPlanner`,
`PackageVersionRetentionPlanner`), de `projects.yaml`-configuratie, de clients in
`dashboard/services/` (die voeden schermen, geen opruiming), en throttling van de deletes.

### Deel 2 — Opruimen-scherm per actie

**Backend.** `maintenance.cleanupsList` levert naast `runs`, `errors` en `runningKinds` een
samenvatting: per opruimsoort de laatste ronde, en voor `github-releases` de laatste ronde
per project. Die samenvatting komt uit een eigen repository-query (laatste rij per
`kind`/`project`), niet uit de op 200 rijen afgekapte lijst — anders kan een drukke soort de
laatste ronde van een rustige soort uit beeld duwen. Per samenvattingsregel minimaal: `kind`,
`project`, `startedAt`, `finishedAt`, `itemsDeleted`, `itemsKept`, `dryRun`, `failed`,
`trigger` en de `id` van die ronde. Bestaande velden en endpoints blijven ongewijzigd, zodat
al uitgerolde APK's blijven werken.

**Hoofdscherm (`maintenance_screen.dart`).** Titel blijft `Opruimen`. Het scherm toont een
blok per opruimsoort uit `CleanupKinds` (`github-releases`, `agent-events`, `agent-runs`,
`completion-payloads`, `workspaces`) — alle vijf altijd zichtbaar, ook zonder gelogde ronde.
Per blok:

- de naam van de actie; bij `github-releases` een regel per project met een gelogde ronde;
- het resultaat van de laatste ronde als label/waarde-paren: *verwijderd* (`itemsDeleted`),
  *blijft staan* (`itemsKept`) en *duur* (uit `startedAt`/`finishedAt`, leesbaar als
  bijvoorbeeld `1 m 7 s`, en `< 1 s` bij een zeer korte ronde);
- wanneer die ronde liep, plus de bestaande badges `dry-run`, `handmatig` en `fout` met
  ongewijzigde tekst en tone;
- is er geen gelogde ronde voor die soort, dan een neutrale regel in de trant van
  "laatste ronde: geen wijzigingen gelogd" in plaats van een lege of alarmerende weergave;
- een knop **Nu draaien** die `POST /api/v1/maintenance/run` met die `kind` doet. Gedrag
  ongewijzigd: knop uit zolang een verzoek loopt of `runningKinds` die soort als draaiend
  meldt, dezelfde meldingen per status (`started` / `already_running` / `disabled` /
  `unknown_kind`), en na een start herladen plus doorpollen (3 s) tot niets meer draait;
- een knop **Runs bekijken** naar de historie van alleen die soort.

De soort-dropdown en de `Nu draaien:`-knoppenbalk in hun huidige vorm vervallen. Eén knop
**Alles draaien** (`kind = all`) blijft bovenaan, met de bestaande gecombineerde melding.
Foutbanners uit `errors` blijven bovenaan het scherm.

**Runs-scherm per actie.** Een nieuw scherm met eigen `Scaffold`/`AppBar` dat
`/api/v1/maintenance/cleanups?kind=<kind>` laadt en de rondes nieuwste-eerst toont — per rij
tijdstip, project, `N opgeruimd / M bewaard` en dezelfde badges, zoals de huidige lijst dat
doet. Tikken op een ronde opent het bestaande rondedetail
(`/api/v1/maintenance/cleanups/{id}`) ongewijzigd, inclusief de uitsplitsing
releases/package-versions en de foutmelding.

**Mobiel.** De resultaatweergave blijft binnen de schermbreedte op een smal scherm (geen
horizontaal scrollende tabel); onder de bestaande breekpunt-drempel stapelen de elementen,
zoals de huidige knoppenbalk dat al doet.

Buiten scope: paginering van de historie, een deeplink/routenaam voor de nieuwe schermen, en
wijzigingen aan het rondedetailscherm zelf.

### Documentatie

Bijwerken bij de wijziging: `docs/factory/technical-spec.md` §Opruimen (paginatie + nieuwe
property + schermbeschrijving), `docs/factory/functional-spec.md` §Opruimen,
`docs/factory/ux/screen-map.md` regel `/maintenance`, en `docs/technical/scheduled-jobs.md`
§7/§9.

## Acceptance criteria

### Deel 1

1. Een unit-test toont aan dat `listVersions` meerdere pagina's samenvoegt (bijvoorbeeld
   100 + 100 + 37 = 237 versies) en dat er gestopt wordt zodra een pagina minder dan
   `per_page` items teruggeeft — dus zonder een extra call voor de volgende pagina.
2. Een unit-test toont aan dat bij een mislukte vervolgpagina wordt teruggegeven wat al is
   opgehaald, en dat er bij een mislukte eerste pagina een lege lijst uit komt.
3. Een unit-test toont aan dat de paginagrens wordt gerespecteerd: met een bron die blijft
   doorleveren stopt de lus op het ingestelde maximum.
4. Een test op scheduler-niveau toont aan dat één ronde met een fake-client van 350
   package-versions en `keep = 15` alle 335 overtollige versies verwijdert (dus niet ~85).
5. Ontbrekend `SF_GITHUB_PACKAGES_TOKEN` levert nog steeds een lege lijst met een
   waarschuwing en geen crash; er wordt dan geen enkele HTTP-call gedaan.
6. Faalt het ophalen van de open pull requests, dan wordt er voor dat project géén
   package-version verwijderd en krijgt de logregel van die projectronde een `error`.
7. `GitHubReleaseCleanupClient.listReleases` pagineert aantoonbaar op dezelfde manier.

### Deel 2

8. Het Opruimen-scherm opent met een blok per opruimsoort, elk met verwijderd / blijft staan /
   duur van de laatste ronde; `github-releases` toont dat per project.
9. Een soort zonder gelogde ronde toont een leesbare "geen wijzigingen gelogd"-regel, geen
   foutmelding en geen leeg blok.
10. Elke actie heeft een werkende **Nu draaien**-knop met het bestaande beschermings- en
    pollgedrag; een widget-test dekt dat de knop uit staat zolang die soort in `runningKinds`
    staat of er een verzoek loopt.
11. Elke actie heeft een **Runs bekijken**-knop; daarachter staat de lijst met rondes van
    alléén die soort (nieuwste eerst) en opent een ronde het bestaande rondedetail met de
    aantallen, de uitsplitsing releases/package-versions en de foutmelding.
12. De duur wordt leesbaar getoond (bijvoorbeeld `1 m 7 s`); een test dekt de formattering
    inclusief een ronde onder één seconde.
13. Geen regressie op de foutbanners en op de `dry-run`-, `handmatig`- en `fout`-badges;
    de bestaande badge-tests blijven van kracht (aangepast aan de nieuwe schermindeling).
14. `flutter analyze` en `flutter test` zijn groen; `maintenance_screen_test.dart` is
    bijgewerkt op de vervallen dropdown en knoppenbalk.
15. De genoemde documentatiebestanden beschrijven de nieuwe situatie.

## Aannames

- **Deel 1 wordt in de repo bewezen met tests, niet met een productieronde.** Het originele
  criterium "na een handmatige ronde staan `personal-news-feed-backend`/`-frontend` op 15"
  vergt het echte GitHub-token en productiedata en is in de bouwstraat niet te draaien.
  Criteria 1–7 dekken hetzelfde gedrag; de bevestiging op het echte project gebeurt na deploy
  door de PO.
- **Geen throttling of vertraging tussen deletes.** Een eerste ronde verwijdert nu honderden
  package-versions; loopt GitHub tegen een rate limit aan, dan blijft het bestaande fail-soft
  gedrag gelden (de mislukte delete telt niet als verwijderd, de ronde slaagt) en pakt de
  volgende ronde de rest op. Met paginatie is die rest dan wél in één keer zichtbaar.
- **`Nu draaien` bij `github-releases` blijft één knop voor alle projecten**, want de
  bestaande poort draait één ronde over álle projecten met een `releaseCleanup:`-blok. De
  per-project regels onder die actie zijn alleen weergave, geen aparte knoppen.
- **De vijf acties komen uit de vaste `CleanupKinds`-lijst**, niet uit de data. Zo blijft een
  soort die nog nooit iets logde toch zichtbaar met zijn knoppen.
- **De duur komt uit `startedAt`/`finishedAt` van de gelogde rij.** Ontbreekt `finishedAt`
  onverhoopt, dan toont het scherm een streepje in plaats van een berekende duur.
- **De bestaande endpoints en velden blijven bestaan**; de samenvatting komt er als extra veld
  bij, zodat een oudere, al geïnstalleerde APK niet breekt.
- **Het scherm heet nog steeds `Opruimen`** en de navigatie-ingang verandert niet, zodat de
  exacte labelassertie in `app_shell_test.dart` blijft kloppen.
- **Geen databasemigratie nodig**: alle benodigde kolommen bestaan al in
  `maintenance_cleanup_runs`.

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de story-diff gelezen. Hieronder de eindsamenvatting.

---

# SF-1936 — Eindsamenvatting

**Opruimen: package-cleanup pagineren + Opruimen-scherm per actie met resultaat, duur, Nu draaien en runs-historie**

## Wat is gebouwd

**Deel 1 — paginatie in de GitHub-cleanup-clients (SF-1938)**
De nachtelijke opruimer las maar één pagina van GitHub (100 items), waardoor één ronde bij een achterstand slechts een fractie opruimde. Er is nu een gedeelde, HTTP-loze paginatielus (`GitHubPagination`) die door `GitHubPackageCleanupClient.listVersions`, `GitHubReleaseCleanupClient.listReleases` en `GitHubProtectedShaSource.openPullRequestHeadShas` wordt gebruikt. De lus stopt zodra een pagina minder dan 100 items geeft (zonder extra call) of bij de nieuwe bovengrens `sf.maintenance.github-page-limit` (default 20 = 2000 items), met een waarschuwing incl. package-/reponaam en aantal items.

**Deel 2 — backend-samenvatting + Opruimen-scherm per actie (SF-1939)**
De backend levert bij `maintenance.cleanupsList` een extra `summary`-veld: de laatste ronde per opruimsoort, en per project voor `github-releases`. Het scherm toont nu een blok per opruimsoort (alle vijf altijd zichtbaar) met *verwijderd / blijft staan / duur* van de laatste ronde, de bestaande badges, een **Nu draaien**-knop en een **Runs bekijken**-knop naar een nieuw runs-scherm met alleen die soort. De soort-dropdown en de knoppenbalk zijn vervallen; **Alles draaien** en de foutbanners staan bovenaan.

## Belangrijkste keuzes

- **Fail-soft vs. fail-safe.** Bij releases en package-versions: faalt pagina 1 → lege lijst (bestaand gedrag), faalt pagina n>1 → teruggeven wat al opgehaald is. Bij de *beschermingslijst* (open PR-sha's) is dat bewust anders: een onvolledige lijst zou beschermde preview-images laten verwijderen, dus wordt de package-cleanup voor dat project die ronde overgeslagen met een `error` op de logregel. De release-cleanup van diezelfde ronde loopt wel door.
- **Stoppen op het ruwe aantal array-elementen**, niet op het aantal geparste items — anders zou één onparseerbaar element de rest van de historie onzichtbaar maken.
- **Signatuurwijziging i.p.v. extra methode** bij `protectedTags` (nu `ProtectedTags(tags, complete)`): een tweede methode zou door de handgeschreven test-fake heen vallen en stilletjes echte HTTP-calls doen.
- **Eigen repository-query** (`latestPerKindAndProject`, `DISTINCT ON`) voor de samenvatting i.p.v. selectie uit de op 200 rijen afgekapte lijst — anders kan een drukke soort de laatste ronde van een rustige soort uit beeld duwen.
- **Achterwaarts compatibel:** `summary` is een extra veld met default, bestaande velden en endpoints ongewijzigd, geen databasemigratie. Een al geïnstalleerde APK blijft werken.
- **Duurberekening in de frontend** uit `startedAt`/`finishedAt` (`1 m 7 s`, `43 s`, `< 1 s`, `-` bij ontbrekende tijd), zodat er geen afgeleid veld bijkomt dat oude APK's niet kennen.

## Wat is getest

- Volledig vangnet vanaf repo-root: `mvn -B verify` → **BUILD SUCCESS, 1026 tests, 0 failures/errors**.
- `flutter analyze` → geen issues; `flutter test` → **135 tests groen** (waaronder 21 in `maintenance_screen_test.dart`); `flutter build web --release` → exit 0.
- `tools/audit-documentation` → PASS.
- Alle 15 acceptatiecriteria zijn aan concrete tests gekoppeld: paginatielus (samenvoegen 100+100+37, stoppen zonder extra call, mislukte eerste/vervolgpagina, paginagrens), één ronde met 350 versions en `keep=15` verwijdert alle 335 overtollige versies (was ~85), ontbrekend token doet geen enkele HTTP-call, fail-safe pad, en op schermniveau blokken per soort, "geen wijzigingen gelogd", duurformattering, badge-/foutbanner-regressie, knop-uit-gedrag, doorpollen, runs-scherm en een 400px-viewporttest.

## Bewust niet gedaan

- **Geen productiebewijs op echte GitHub-data.** Het oorspronkelijke criterium ("`personal-news-feed-*` staan op 15 na een handmatige ronde") vergt het echte token en productiedata; dat is een PO-controle ná deploy.
- **Buiten scope gehouden:** het retentie-algoritme zelf, `projects.yaml`, de dashboard-clients, throttling tussen deletes, paginering/verversing van de historie, een deeplink voor de nieuwe schermen en het rondedetailscherm zelf.
- **`Nu draaien` bij `github-releases` blijft één knop** voor alle projecten; de per-projectregels zijn alleen weergave.

## Openstaande suggesties (geen blockers, kandidaat vervolgstory)

- `formatCleanupDuration` toont bij een negatieve duur (klokverschuiving) `< 1 s` i.p.v. `-`.
- Het runs-scherm laadt één keer bij openen en heeft geen ververs-knop.
- Faalt de `contents`-call in `GitHubProtectedShaSource`, dan blijft de lijst als compleet gelden (bestaand gedrag, stond buiten scope).
- `latestPerKindAndProject()` doet een volledige tabelscan per schermlading — prima bij de 90-dagenretentie, maar het staat genoteerd.

<!-- deploy-summary:start -->
Het automatisch opruimen van oude versies werkt nu in één keer de hele achterstand weg, in plaats van er dagen over te doen. Op het Opruimen-scherm zie je voortaan per opruimactie een eigen blok met hoeveel er is opgeruimd, hoeveel blijft staan en hoe lang de laatste keer duurde. Per actie kun je die meteen zelf starten of de geschiedenis van alleen die actie bekijken.
<!-- deploy-summary:end -->
