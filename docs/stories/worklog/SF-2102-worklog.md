# SF-2102 - Worklog

Story-context bij eerste pickup:
Testdekking changelog-query (e2e) en SUMMARIZER-summaryschrijfactie

Alleen testcode; geen wijziging in src/main (geen SQL, signatures of defaults).

1) In softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/TrackerCapabilityPersistenceE2eTest.kt: voeg dekking toe op client.changelogFor(...) rechtstreeks tegen de Testcontainers-Postgres. Bouw op met client.createStory(projectKey = "SF", title = ..., repo = ...), client.createSubtask(...) en client.updateIssueShortDescriptionSummary(...) naar het model van de bestaande test rond r137-152. Leg vier eigenschappen vast in dekking die per eigenschap afzonderlijk kan falen (meerdere kleine @Test-methoden heeft de voorkeur boven een alles-in-een-assertie): (a) projectfilter - een story met een ander repo staat er niet in; (b) subtaken uitgesloten - een subtaak met een eigen short_description_summary staat er niet in; (c) lege/ontbrekende samenvatting uitgesloten - zowel een story met "" als een story die er nooit een kreeg; (d) volgorde nieuwste-eerst.
Valkuil: TrackerCapabilities.changelogFor heeft een default emptyList() (TrackerCapabilities.kt:42) die geen enkele testfake overschrijft - dekking via BridgeTestFixtures, FakeTrackerApi of de bridge-operatie changelog.for is altijd groen en bewijst niets. Alleen rechtstreeks op PostgresTrackerClient.
Risico sorteerstabiliteit: updateIssueShortDescriptionSummary zet zelf updated_at = now(); twee rijen kunnen dezelfde timestamp krijgen. Zet updated_at deterministisch via jdbc.update(...) in plaats van op de klok te vertrouwen.
Isolatie: resetTables() in @BeforeEach leegt de tabellen per test; nieuwe rijen mogen geen andere test raken.

2) In softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/runtime/AgentRunCompletionServiceTest.kt: voeg dekking toe voor de SUMMARIZER-rol die een geslaagde completion met zowel descriptionSummary als shortDescriptionSummary verwerkt en asserteert dat beide op de tracker landen via FakeTrackerApi.descriptionSummaryUpdates / shortDescriptionSummaryUpdates. Neem de blank-tak mee: een lege of witruimte-only waarde mag niet worden weggeschreven (sleutel ontbreekt in de map).
Risico vier poorten: de schrijfactie in writeFinalStoryAfterSummarizer wordt pas bereikt bij role == "summarizer", request.isSuccessful() (exitCode 0 en outcome zonder error/failed), storyRunRepository.get(...) != null en storyWorkspaceService != null. Met storyWorkspaceService = null schrijft de code stilzwijgend niets weg en is de test vals-negatief. Bouw de service met de bestaande ThrowingStoryWorkspaceService (de writeFinalStory-aanroep zit in een runCatching, de summary-schrijfacties staan daarna, dus de throw blokkeert ze niet).
Padcorrectie: de productieklasse staat in runtime/services/AgentRunCompletionService.kt, niet runtime/. De summary-schrijfactie gebruikt request.storyKey terwijl getIssue op storyRun.storyKey gaat (FakeStoryRunRepository geeft altijd KAN-69) - asserteer op request.storyKey.

3) Mutatiebewijs, beide onderdelen, vastleggen in docs/stories/worklog/SF-2102-worklog.md: (a) verwijder lokaal tijdelijk AND parent_key IS NULL uit PostgresTrackerClient.changelogFor en stel vast dat de subtaak-assertie rood wordt; draai daarna terug zodat de productiecode ongewijzigd is; (b) toon aan dat de SUMMARIZER-assertie faalt als de schrijfactie niet plaatsvindt (niet-geslaagde completion of storyWorkspaceService = null).

4) Voer de review-stap zelf uit: controleer dat de diff geen src/main raakt en dat alle bestaande tests in beide bestanden ongewijzigd groen blijven. Geen documentatie-update nodig (test-only, functional-spec-gedrag verandert niet).

Stappenplan:
[x]: read issue and target docs
[x]: changelog-dekking (4 losse e2e-tests) in TrackerCapabilityPersistenceE2eTest
[x]: SUMMARIZER-dekking (3 tests) in AgentRunCompletionServiceTest
[x]: mutatiebewijs beide onderdelen vastleggen
[x]: run relevant tests + volledig vangnet (mvn verify)
[x]: update story-log with results

## In eigen woorden

De changelog-keten was ongedekt op de twee dure plekken: de SQL van
`PostgresTrackerClient.changelogFor` (vier gedragsregels in één query) en de plek waar de
SUMMARIZER zijn twee samenvattingen op de tracker schrijft. Deze story legt allebei vast met
testcode; er verandert niets aan `src/main`.

## Wat is er gedaan

### 1. `TrackerCapabilityPersistenceE2eTest` — vier losse changelog-tests

Rechtstreeks op `PostgresTrackerClient` tegen de Testcontainers-Postgres (echt Flyway-schema),
bewust niet via een fake of de bridge: `TrackerCapabilities.changelogFor` heeft een
default-implementatie die `emptyList()` teruggeeft en geen enkele fake overschrijft die, dus zulke
dekking is altijd groen en bewijst niets.

- `changelogFor only returns stories of the requested project` — `WHERE repo = ?`
- `changelogFor never returns subtasks, even with their own short summary` — `AND parent_key IS NULL`
- `changelogFor skips stories with an empty or missing short summary` — `IS NOT NULL AND != ''`
- `changelogFor returns the newest story first` — `ORDER BY updated_at DESC`

Twee dingen waren nodig om de tests niet vals-groen te laten zijn:

- `createSubtask` neemt de `repo` van de parent niet over (blijft `NULL`), waardoor de repo-filter
  de subtaak al zou uitsluiten. De test zet `repo` op de subtaak expliciet via `jdbc.update(...)`,
  zodat alleen `parent_key IS NULL` 'm nog buiten de lijst houdt.
- `updateIssueShortDescriptionSummary` zet zelf `updated_at = now()`; drie schrijfacties binnen
  dezelfde tik zouden de volgorde onbepaald maken. De volgorde-test zet `updated_at` daarom
  deterministisch (`now() - interval 'N days'`).

### 2. `AgentRunCompletionServiceTest` — drie SUMMARIZER-tests

- `successful summarizer completion writes both summaries to the tracker`: geslaagde completion met
  `descriptionSummary` én `shortDescriptionSummary`; beide landen op `request.storyKey` (KAN-69).
- `summarizer completion never writes a blank summary`: `""` en `"   \n  "` worden niet
  weggeschreven — de sleutels ontbreken in beide maps.
- `a failed summarizer completion writes no summaries at all`: contrast-test met `outcome="error"`,
  `exitCode=1` — permanente borging dat de positieve test niet vacuüm-groen kan staan.

De service wordt met een niet-null `storyWorkspaceService` (de bestaande
`ThrowingStoryWorkspaceService`) gebouwd; met `null` valt de hele tak stil weg. De
`writeFinalStory`-aanroep zit in een `runCatching`, dus de gooiende fake blokkeert de
summary-schrijfacties niet.

Afwijking t.o.v. de story-tekst (bewust, geen scopewijziging): `AgentRunCompletionServiceTest` heeft
zijn eigen private `FakeTrackerApi` en gebruikt niet die uit `testsupport/`. Die laatste laat
`postAgentComment` exploderen (`UnsupportedOperationException`) en is dus niet bruikbaar in dit
completion-pad. De twee registratie-maps zijn daarom met exact dezelfde namen
(`descriptionSummaryUpdates` / `shortDescriptionSummaryUpdates`) aan de lokale fake toegevoegd —
twee overrides, geen nieuwe fake.

## Mutatiebewijs

(a) `AND parent_key IS NULL` lokaal uit `PostgresTrackerClient.changelogFor` verwijderd →
`changelogFor never returns subtasks, even with their own short summary` werd rood:

```
org.opentest4j.AssertionFailedError: expected: <[SF-1]> but was: <[SF-2, SF-1]>
```

Daarna teruggedraaid; `git status` toont geen enkele wijziging in `src/main`.

(b) `storyWorkspaceService` in de nieuwe testhelper lokaal op `null` gezet →
`successful summarizer completion writes both summaries to the tracker` werd rood:

```
org.opentest4j.AssertionFailedError: expected: <Lange samenvatting> but was: <null>
```

Daarna teruggedraaid naar `ThrowingStoryWorkspaceService()`. De permanente variant van dit bewijs
staat als derde test in de suite (niet-geslaagde completion → beide maps leeg).

## Vangnet

`mvn verify` vanaf de repo-root: BUILD SUCCESS, 0 failures, 0 errors (11-08-2026, ~5m11).
`TrackerCapabilityPersistenceE2eTest` 32 tests groen (4 nieuw), `AgentRunCompletionServiceTest`
22 tests groen (3 nieuw). Geen bestaande test gewijzigd.

## Documentatie

Geen doc-update nodig: dit is test-only en het changelog-gedrag staat al beschreven in
`docs/factory/functional-spec.md` (§ changelog per project). De specs blijven dus kloppen.

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
