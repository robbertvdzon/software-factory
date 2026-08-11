# SF-2102 - [Audit] Integratietest voor de changelog-keten: leg de changelog-query en de summarizer-schrijfactie vast

## Story

[Audit] Integratietest voor de changelog-keten: leg de changelog-query en de summarizer-schrijfactie vast

<!-- refined-by-factory -->

## Scope

Alleen testcode. Er wordt geen productiecode gewijzigd (ook geen signatures, defaults of SQL).

Twee toevoegingen aan bestaande testbestanden:

**1. Round-trip-test op de changelog-query** in
`softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/TrackerCapabilityPersistenceE2eTest.kt`
(echte Postgres via Testcontainers, echt Flyway-schema; draait onder failsafe, geen nieuwe infrastructuur).

Bouw de situatie op met `client.createStory(projectKey = "SF", title = …, repo = …)`,
`client.createSubtask(...)` en `client.updateIssueShortDescriptionSummary(key, …)`, en asserteer
op `client.changelogFor(repo)` de vier eigenschappen van
`PostgresTrackerClient.changelogFor` (`softwarefactory/.../tracker/clients/PostgresTrackerClient.kt:160-168`):

- alleen het gevraagde project — een story met een ander `repo` staat er niet in (`WHERE repo = ?`);
- geen subtaken — zet ook op een subtaak een samenvatting en asserteer dat die niet in de lijst staat (`AND parent_key IS NULL`);
- geen lege samenvattingen — een story met `""` valt eruit én een story die nooit een samenvatting kreeg ook (`short_description_summary IS NOT NULL AND != ''`);
- nieuwste eerst (`ORDER BY updated_at DESC`).

Eén test of enkele kleine tests in dit bestand is beide goed; de vier eigenschappen moeten
afzonderlijk kunnen falen (dus niet één assertie die alles tegelijk dekt).

**2. SUMMARIZER-test** in
`softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/runtime/AgentRunCompletionServiceTest.kt`.
Verwerk een geslaagde SUMMARIZER-completion met zowel `descriptionSummary` als
`shortDescriptionSummary` en asserteer dat beide op de tracker landen
(`AgentRunCompletionService.kt:516-523`); `FakeTrackerApi` legt ze al vast in
`descriptionSummaryUpdates` / `shortDescriptionSummaryUpdates` (`FakeTrackerApi.kt:126-127`),
dus dit is een assertie, geen nieuwe fake. Neem de blank-tak mee: een lege of witruimte-only
samenvatting mag niet worden weggeschreven (`?.trim()?.takeIf { it.isNotBlank() }`).

### Buiten scope

De publieke endpoint (`ChangelogController.kt`), de bridge-operatie `changelog.for` en de
dashboard-route (`BridgeApiController.kt:217`). Die zijn ook ongedekt, maar de dure en risicovolle
helft van de keten is de SQL, en die is met bovenstaande twee toevoegingen vastgelegd.

### Valkuil — niet via fake of bridge testen

`TrackerCapabilities.changelogFor` heeft een default-implementatie die `emptyList()` teruggeeft
(`TrackerCapabilities.kt:42`) en géén testfake overschrijft die — `BridgeTestFixtures` en
`FakeTrackerApi` erven hem gewoon. Een test op `changelog.for` via
`BridgeTestFixtures.minimalRequestHandler()` is dus altijd groen en bewijst niets (dezelfde valkuil
als de `changedFiles`-default van vorige ronde). Onderdeel 1 moet daarom rechtstreeks op
`PostgresTrackerClient` tegen de echte database.

## Acceptance criteria

1. `TrackerCapabilityPersistenceE2eTest` bevat testdekking op `client.changelogFor(...)` die
   rechtstreeks tegen de Testcontainers-Postgres draait, niet tegen een fake of via de bridge.
2. Die dekking asserteert alle vier de eigenschappen afzonderlijk: projectfilter, subtaken
   uitgesloten, lege/ontbrekende samenvatting uitgesloten, en volgorde nieuwste-eerst.
3. `AgentRunCompletionServiceTest` bevat een test die een geslaagde SUMMARIZER-completion verwerkt
   en asserteert dat `descriptionSummary` én `shortDescriptionSummary` op de tracker landen
   (via `descriptionSummaryUpdates` / `shortDescriptionSummaryUpdates`).
4. Diezelfde SUMMARIZER-dekking asserteert dat een lege of witruimte-only samenvatting níét wordt
   weggeschreven (de betreffende sleutel ontbreekt in de map).
5. Mutatiebewijs onderdeel 1: met `AND parent_key IS NULL` lokaal tijdelijk uit de query verwijderd
   wordt de subtaak-assertie rood. Dit wordt in het worklog/de PR-beschrijving vastgelegd; de
   productiecode staat bij oplevering weer ongewijzigd.
6. Mutatiebewijs onderdeel 2: de nieuwe SUMMARIZER-test is aantoonbaar niet vacuüm-groen — de
   assertie faalt wanneer de schrijfactie niet plaatsvindt (bijv. bij een niet-geslaagde
   completion of ontbrekende `storyWorkspaceService`).
7. Volledige `mvn verify` (vereist Docker) is groen; alle bestaande tests in beide geraakte
   bestanden blijven ongewijzigd groen.
8. Er staat geen wijziging in `src/main` in de diff.

## Aannames

- **Vier poorten voor de SUMMARIZER-schrijfactie.** De schrijfactie zit in
  `writeFinalStoryAfterSummarizer` en wordt pas bereikt als (a) `request.role == "summarizer"`,
  (b) `request.isSuccessful()` (exitCode 0 en outcome zonder "error"/"failed"), (c)
  `storyRunRepository.get(...)` non-null is en (d) `storyWorkspaceService` non-null is. De test moet
  de service dus met een non-null `storyWorkspaceService` construeren; met `null` schrijft de code
  stilzwijgend niets weg en zou de test vals-negatief zijn. Aanname: de test gebruikt een
  workspace-fake naar het model van de bestaande `ThrowingStoryWorkspaceService` (de
  `writeFinalStory`-aanroep zit in een `runCatching`, dus een gooiende fake blokkeert de
  summary-schrijfactie niet) of een variant die niets doet.
- **`getIssue` in dat pad.** `FakeTrackerApi.getIssue` gooit bij een onbekende sleutel; die
  aanroep zit in dezelfde `runCatching`, dus de test mag het issue in de fake registreren om ruis
  in de logging/ERROR-tak te voorkomen, maar het is niet noodzakelijk voor de assertie.
- **Sorteerstabiliteit.** `updateIssueShortDescriptionSummary` zet zelf `updated_at = now()`
  (`PostgresTrackerClient.kt:335`). Blijkt de volgorde-assertie flaky doordat twee rijen dezelfde
  timestamp krijgen, dan wordt `updated_at` expliciet via `jdbc.update(...)` gezet in plaats van op
  de klok te vertrouwen.
- **Isolatie.** `resetTables()` (`@BeforeEach`) leegt `issues` en aanverwante tabellen per test, dus
  nieuwe rijen raken geen andere test. Het opzetten van een story mét subtaak volgt het bestaande
  patroon in dit bestand.
- **Testtellingen in de oorspronkelijke story zijn indicatief.** Op deze checkout heeft
  `TrackerCapabilityPersistenceE2eTest` 29 `@Test`-methoden en `AgentRunCompletionServiceTest` 19
  (story noemde 28 resp. 24). Het criterium is "alle bestaande tests blijven groen", niet een
  absoluut aantal.
- **Padcorrectie.** `PostgresTrackerClient.kt` staat in
  `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/tracker/clients/`, niet in `tracker/`.
- **Geen doc-drift.** Het changelog-gedrag staat al beschreven in
  `docs/factory/functional-spec.md` §"Changelog per project op een eigen adres (SF-2086 / SF-2087)";
  een test-only story wijzigt dat gedrag niet, dus documentatie hoeft niet bijgewerkt.

## Eindsamenvatting

# Eindsamenvatting SF-2102 — Integratietest voor de changelog-keten

## Wat is opgeleverd
Een test-only story: zeven nieuwe tests in twee bestaande testbestanden, plus het worklog. Geen enkele regel in `src/main` gewijzigd (alleen toevoegingen, 0 verwijderde regels).

**1. Changelog-query vastgelegd (4 e2e-tests)** in `TrackerCapabilityPersistenceE2eTest`, rechtstreeks op `PostgresTrackerClient` tegen de Testcontainers-Postgres met het echte Flyway-schema. Elk van de vier gedragsregels van de query kan afzonderlijk falen:
- alleen stories van het gevraagde project (`WHERE repo = ?`)
- nooit subtaken (`AND parent_key IS NULL`)
- geen lege of ontbrekende samenvattingen (`IS NOT NULL AND != ''`)
- nieuwste eerst (`ORDER BY updated_at DESC`)

**2. SUMMARIZER-schrijfactie vastgelegd (3 tests)** in `AgentRunCompletionServiceTest`: beide samenvattingen landen op de tracker bij een geslaagde completion, blanco/witruimte-only waarden worden niet weggeschreven, en een mislukte completion schrijft niets.

## Keuzes
- **Bewust niet via fake of bridge getest.** `TrackerCapabilities.changelogFor` heeft een default die `emptyList()` teruggeeft en geen fake overschrijft die — dekking langs die weg is altijd groen en bewijst niets. Vandaar rechtstreeks op de echte database.
- **Twee vals-groen-vallen gedicht:** `createSubtask` schrijft geen `repo` weg, dus de subtaak-test zet die expliciet (anders sloot het repo-filter de subtaak al uit en bewees de test niets over de parent-clausule); de volgorde-test zet `updated_at` deterministisch in plaats van op de klok te vertrouwen.
- **Geen nieuwe fake:** de twee registratiemaps zijn als overrides toegevoegd aan de al bestaande lokale `FakeTrackerApi` van dat testbestand (die uit `testsupport/` is in dit pad onbruikbaar).
- **Contrast-test als permanent mutatiebewijs** in plaats van alleen een eenmalig handmatig bewijs.

## Getest
- Mutatiebewijs 1: `AND parent_key IS NULL` tijdelijk uit de query → subtaak-test rood; teruggedraaid.
- Mutatiebewijs 2: `storyWorkspaceService` tijdelijk `null` → positieve summarizer-test rood; teruggedraaid.
- Volledige `mvn verify` door zowel developer als tester onafhankelijk gedraaid: BUILD SUCCESS, 0 failures/errors/skipped over alle vijf modules (~5 min). `TrackerCapabilityPersistenceE2eTest` 32 groen (4 nieuw), `AgentRunCompletionServiceTest` 22 groen (3 nieuw). Reviewer en tester akkoord, geen bevindingen.

## Bewust niet gedaan
- De publieke changelog-endpoint, de bridge-operatie `changelog.for` en de dashboard-route blijven ongedekt — de dure en risicovolle helft van de keten is de SQL, en die is nu vastgelegd.
- Geen documentatie-update: gedrag is niet veranderd en staat al beschreven in de functional spec.

<!-- deploy-summary:start -->
Er is extra controle ingebouwd op de changelog: automatische tests bewaken nu dat de lijst met wijzigingen per project klopt en dat samenvattingen goed worden opgeslagen. Voor jou verandert er niets zichtbaars aan de applicatie. Het voorkomt wel dat toekomstige aanpassingen ongemerkt de changelog stukmaken.
<!-- deploy-summary:end -->
