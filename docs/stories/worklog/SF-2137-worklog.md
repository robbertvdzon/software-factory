# SF-2137 - Worklog

Story-context bij eerste pickup:
Sorteer storyoverzicht aflopend op createdAt

Vervang in dashboard-frontend/lib/screens/stories_screen.dart de sortering van allIssues (nu r177-186: aflopend op _storyNumber) door een sortering aflopend op issue['fields']['createdAt'], geparsed als DateTime (bijv. DateTime.tryParse) en niet als string. De sortering blijft staan op allIssues VOOR het filteren (buckets/repo/zoek), zodat filters de onderlinge volgorde niet beinvloeden. Terugvallen: (a) bij gelijk createdAt aflopend op storynummer via de bestaande helper _storyNumber (die blijft bestaan); (b) stories zonder bruikbaar createdAt (ontbrekend, leeg of niet parseerbaar) komen onderaan, onderling aflopend op storynummer, zonder exception. Werk het toelichtende commentaar boven de sortering bij. Verander niets aan de per-regel getoonde tijdstempel (afgeronde story toont updatedAt, overige createdAt), voeg geen UI-elementen of sorteeropties toe en raak de backend/DB/API niet aan. Breid dashboard-frontend/test/screens/stories_screen_test.dart uit met: (1) een test met minimaal drie stories waarbij de storynummer-volgorde bewust afwijkt van de createdAt-volgorde en die de daadwerkelijke schermvolgorde vastlegt op createdAt aflopend; (2) een test die het terugvalgedrag vastlegt met minimaal een story zonder createdAt naast stories met createdAt; overweeg ook een assertie dat de volgorde gelijk blijft met een actief filter/zoekterm. Werk docs/factory/ux/screens/stories.md paragraaf 'Sorting & filtering (SF-818)' (r30-33) bij naar de nieuwe regel inclusief terugval; laat geen tekst staan die nog 'story number descending' voorschrijft. Zorg dat flutter analyze en flutter test in dashboard-frontend groen zijn en doe een eigen reviewslag op de wijziging.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `dashboard-frontend/lib/screens/stories_screen.dart`: de sortering van `allIssues` (voorheen
  inline aflopend op `_storyNumber`) vervangen door de comparator `_byCreatedAtDesc`. Die gebruikt
  de nieuwe helper `_createdAt(issue)`, die `fields.createdAt` met `DateTime.tryParse` parseert
  (dus geen tekstvergelijking, zodat afwijkende offsets/notaties geen verkeerde volgorde geven) en
  `null` teruggeeft bij ontbrekend/leeg/onparseerbaar. Volgorde: nieuwste `createdAt` bovenaan;
  bij gelijk `createdAt` aflopend op storynummer (deterministisch, want `List.sort` in Dart is niet
  gegarandeerd stabiel); stories zonder bruikbaar `createdAt` onderaan, onderling ook aflopend op
  storynummer. `_storyNumber` blijft bestaan als terugval. De sortering staat nog steeds op
  `allIssues` vóór het filteren, dus buckets/repo/zoekterm laten alleen regels weg en veranderen de
  onderlinge volgorde niet; het toelichtende commentaar is daarop bijgewerkt. Geen wijziging aan de
  per-rij getoonde tijdstempel (afgeronde story blijft `updatedAt` tonen), geen UI-elementen,
  geen backend-/DB-/API-wijziging.
- `dashboard-frontend/test/screens/stories_screen_test.dart`: drie tests toegevoegd plus de helpers
  `_story`/`_storiesPayload`/`_shownStoryKeys` (die laatste leest de storysleutels in
  renderingvolgorde uit de widget-boom). (1) Drie stories waarbij de storynummervolgorde
  (SF-10/SF-30/SF-20) bewust afwijkt van de `createdAt`-volgorde; verwacht `SF-20, SF-30, SF-10`
  (met de oude sortering zou dit `SF-30, SF-20, SF-10` zijn, dus de test kan niet vals-groen zijn),
  plus een assertie dat de volgorde met een actieve zoekterm gelijk blijft. (2) Terugvaltest met
  ontbrekend, leeg én onparseerbaar `createdAt` naast één geldige story: verwacht
  `SF-4, SF-3, SF-2, SF-1` en `takeException() == null`. (3) Gelijk `createdAt` valt terug op
  storynummer aflopend.
- `docs/factory/ux/screens/stories.md` §"Sorting & filtering": de regel "story number descending"
  vervangen door de nieuwe regel (aflopend op `createdAt`, sorteren vóór filteren, geparsed als
  `DateTime`) inclusief beide terugvallen. Andere `docs/factory`-specs raken deze wijziging niet:
  functional-spec/technical-spec beschrijven de sorteervolgorde van dit scherm niet.

Bewijs (13-08-2026):
- `flutter analyze` in `dashboard-frontend`: "No issues found!" (exit 0).
- `flutter test` in `dashboard-frontend`: 167 tests, "All tests passed!" (exit 0).
- `tools/audit-documentation`: `documentation-audit/v1: PASS` (exit 0).
- `repository-maven-verify` uit `.factory/verification.yaml` valt buiten scope: de diff raakt
  uitsluitend `dashboard-frontend/` en `docs/`, dus geen van de JVM-pathPrefixes. Idem voor
  `agent-mini-reactor-smoke` (`docker/`, `pom.xml`).

Review (13-08-2026, SF-2138):
- Volledige story-diff (`git diff main...HEAD`, 4 bestanden) beoordeeld: alleen
  `stories_screen.dart`, de bijbehorende widget-test, `docs/factory/ux/screens/stories.md` en dit
  worklog. Geen scope creep, geen backend-/DB-/API-wijziging, geen secrets.
- `_byCreatedAtDesc` is een totale, deterministische orde: beide `createdAt` bekend → aflopend op
  tijd met terugval op storynummer; precies één onbekend → onbekende onderaan; beide onbekend →
  storynummer aflopend. Geen exception-pad (`DateTime.tryParse`, lege/ontbrekende waarde via
  `text(...)`).
- Sortering staat nog steeds op `allIssues` vóór het filteren; buckets/repo/zoek laten alleen
  regels weg (AC 2). Per-rij tijdstempel (`updatedAt` bij afgerond) ongewijzigd.
- Testdekking dekt AC 6/7 en de gelijke-`createdAt`-terugval; de gekozen storynummers wijken
  bewust af van de `createdAt`-volgorde, dus de asserties zouden rood zijn met de oude sortering.
- Doc-consistentie: `grep` over `docs/`/`specs/` levert geen tekst meer die "story number
  descending" voorschrijft (AC 8); overige specs beschrijven deze sortering niet.
- Testbewijs: `[FACTORY VERIFICATION EVIDENCE]` in het developercomment is groen en
  `testedTreeSha 501106c8…` komt exact overeen met de tree van developercommit `5c03d36`.
- Besluit: akkoord.
