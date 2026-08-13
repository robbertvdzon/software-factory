# SF-2137 - volgorde

## Story

volgorde

<!-- refined-by-factory -->

## Scope

De sortering van het storyoverzicht (`dashboard-frontend/lib/screens/stories_screen.dart`) gaat van "aflopend op storynummer" naar "aflopend op aanmaakmoment".

In scope:
- De sorteerregel in `StoriesScreen` (nu r177-186, met helper `_storyNumber` r67-69): sorteer op `issue['fields']['createdAt']` aflopend, met een deterministische terugval (zie acceptatiecriteria). De sortering blijft vóór het filteren gebeuren, zodat filters/zoeken de volgorde niet beïnvloeden.
- Widget-tests in `dashboard-frontend/test/screens/stories_screen_test.dart` (nu geen enkele volgorde-assertie).
- Bijwerken van `docs/factory/ux/screens/stories.md` §"Sorting & filtering (SF-818)" (r30-33), dat nu nog "sorted by story number descending" voorschrijft.

Buiten scope:
- Backendwijzigingen. `findAllStories()` (`PostgresTrackerClient.kt:139-158`) levert de volledige set zonder LIMIT en `fields.createdAt`/`fields.updatedAt` zitten al in de response (`ISSUE_COLUMNS`, `WorkflowModels.kt:263-268`); de SQL-`ORDER BY updated_at DESC` mag ongewijzigd blijven, want de frontend sorteert zelf.
- Database-migraties, nieuwe endpoints, nieuwe UI-elementen (geen sorteerkeuze-menu, geen groepering/secties).
- De tijdstempel die per regel getoond wordt (afgeronde story toont `updatedAt`, overige `createdAt`) blijft ongewijzigd.
- Andere lijsten en schermen.

## Acceptance criteria

1. In het storyoverzicht staat de story met het meest recente `fields.createdAt` bovenaan en de oudste onderaan.
2. De volgorde is identiek ongeacht de actieve bucketfilters (todo/bezig/klaar), het repo-filter en de zoekterm; filteren verwijdert alleen regels en verandert de onderlinge volgorde niet.
3. Stories met een gelijk `createdAt` staan onderling in aflopende storynummer-volgorde (deterministisch; `List.sort` in Dart is niet gegarandeerd stabiel).
4. Stories zonder bruikbaar `createdAt` (ontbrekend, leeg of niet te parsen) staan onderaan de lijst, onderling aflopend op storynummer, en veroorzaken geen exception.
5. Er is geen backend-, database- of API-wijziging nodig; de bestaande `/api/v1/stories`-response wordt ongewijzigd gebruikt.
6. `dashboard-frontend/test/screens/stories_screen_test.dart` bevat een test met minimaal drie stories waarbij de storynummer-volgorde bewust afwijkt van de `createdAt`-volgorde, en die de daadwerkelijke schermvolgorde vastlegt op `createdAt` aflopend.
7. Er is een test die het gedrag uit criterium 4 vastlegt (minimaal één story zonder `createdAt` naast stories mét `createdAt`).
8. `docs/factory/ux/screens/stories.md` beschrijft de nieuwe sorteerregel inclusief terugval; er blijft geen tekst staan die nog "story number descending" voorschrijft.
9. `flutter analyze` en `flutter test` in `dashboard-frontend` zijn groen.

## Aannames

- "Laatst aangemaakt bovenaan" betekent sorteren op het aanmaakmoment van het issue (tracker-DB `created_at`), niet op het moment van laatste wijziging. `created_at` is `NOT NULL DEFAULT now()` (`V15__tracker_issues.sql:31`), dus in de praktijk is de waarde er altijd; de terugval uit criterium 4 is er alleen voor rollout-scenario's waarin een nieuwe frontend tegen een oudere backend praat.
- `createdAt` komt als ISO-8601-tijdstempel binnen; de implementatie parseert de waarde (bijv. `DateTime.tryParse`) in plaats van te vertrouwen op tekstvergelijking, zodat afwijkende offsets/notaties geen verkeerde volgorde geven.
- Voor afgeronde stories blijft de getoonde tijdstempel het afrondmoment (`updatedAt`), terwijl er op `createdAt` gesorteerd wordt. Die combinatie is bewust: het verzoek gaat expliciet over aanmaakvolgorde.
- Er komt geen instelbare sorteeroptie; de volgorde is vast, net als nu.
- Het storynummer wordt uit de key afgeleid zoals de bestaande helper dat doet (numeriek deel na het laatste streepje, anders onderaan).

## Eindsamenvatting

## Eindsamenvatting SF-2137 — "volgorde" (storyoverzicht sorteren op aanmaakmoment)

**Wat is gebouwd**

Het storyoverzicht in het dashboard sorteerde altijd aflopend op storynummer. Dat is vervangen door sorteren aflopend op het aanmaakmoment van de story (`fields.createdAt`), zodat de laatst aangemaakte story bovenaan staat.

- `dashboard-frontend/lib/screens/stories_screen.dart`: de inline sortering op `_storyNumber` is vervangen door de comparator `_byCreatedAtDesc`, met nieuwe helper `_createdAt()`.
- `docs/factory/ux/screens/stories.md` §"Sorting & filtering" beschrijft de nieuwe regel inclusief terugvallen; er staat nergens meer "story number descending".
- Worklog `docs/stories/worklog/SF-2137-worklog.md` toegevoegd.

**Gemaakte keuzes**

- `createdAt` wordt geparsed met `DateTime.tryParse` in plaats van als tekst vergeleken, zodat afwijkende offsets/notaties niet tot een verkeerde volgorde leiden.
- De sortering blijft staan *vóór* het filteren, zodat bucketfilters, repo-filter en zoekterm alleen regels weglaten en de onderlinge volgorde nooit veranderen.
- Deterministische terugval (Dart's `List.sort` is niet gegarandeerd stabiel): bij gelijk `createdAt` aflopend op storynummer; stories zonder bruikbaar `createdAt` (ontbrekend, leeg of onparseerbaar) onderaan, onderling ook aflopend op storynummer. Geen exception-pad.
- De per-regel getoonde tijdstempel is bewust ongewijzigd: een afgeronde story toont nog steeds het afrondmoment (`updatedAt`), terwijl er op `createdAt` gesorteerd wordt.

**Wat is getest**

- Drie nieuwe widget-tests in `stories_screen_test.dart`: (1) drie stories waarvan de storynummervolgorde bewust afwijkt van `createdAt` (verwacht `SF-20, SF-30, SF-10`) plus een assertie dat de volgorde met actieve zoekterm gelijk blijft; (2) terugval met ontbrekend, leeg én onparseerbaar `createdAt` naast een geldige story, inclusief `takeException() == null`; (3) gelijk `createdAt` valt terug op storynummer aflopend.
- `flutter analyze`: "No issues found!" · `flutter test`: 167 tests groen · `tools/audit-documentation`: PASS.
- Anti-vacuümproef: met de `main`-versie van het scherm wordt de volgorde-test rood — de test kan dus niet vals-groen zijn.
- Browser-E2E op een gebouwde webapp met zes stories: schermvolgorde bevestigd (`SF-300, SF-700, SF-900, SF-100, SF-600 (onparseerbaar), SF-500 (geen createdAt)`), en met bucket- + repo-filter een exacte deelrij daarvan. Screenshots vastgelegd.

**Bewust niet gedaan**

- Geen backend-, database- of API-wijziging; de bestaande `/api/v1/stories`-response wordt ongewijzigd gebruikt (de SQL `ORDER BY updated_at DESC` blijft staan, de frontend sorteert zelf).
- Geen sorteerkeuzemenu, groepering of andere nieuwe UI-elementen; de volgorde is vast, net als voorheen.
- Geen wijziging aan andere lijsten/schermen of aan de getoonde tijdstempel per regel.

<!-- deploy-summary:start -->
In het overzicht van stories staan de nieuwste bovenaan. Voortaan bepaalt het moment waarop een story is aangemaakt de volgorde, in plaats van het nummer van de story. Filteren of zoeken verandert die volgorde niet: er verdwijnen alleen regels uit de lijst.
<!-- deploy-summary:end -->
