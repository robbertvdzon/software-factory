# SF-2087 - Worklog

Story-context bij eerste pickup:
Changelog op eigen bookmarkbare URL in dashboard-frontend

In eigen woorden: de changelog van een project krijgt in de webversie van het dashboard een
eigen adres `/changelog/<projectnaam>` dat je kunt kopiëren en bookmarken. De changelog-knop in
de projectenlijst opent op web dat adres in een nieuw tabblad; een koude laadbeurt op dat adres
toont de changelog als zelfstandige pagina (geen app-navigatie, geen terug-knop), ook direct na
een verse login. Op de Android-APK blijft alles zoals het was. Alleen `dashboard-frontend`;
geen backend-, database- of manifestwijziging en geen nieuwe externe dependency.

Stappenplan:
[x]: read issue and target docs
[x]: UI-vrije deep-link-afleiding (`lib/deep_link.dart`) met encoding/decoding en lege-projectafhandeling
[x]: pad-gebaseerde URL-strategie op web (conditionele import, no-op op Android)
[x]: deep-link vasthouden in `main.dart`/`RootScreen` en changelog als zelfstandige pagina tonen
[x]: changelog-knop op web naar een nieuw tabblad; niet-web ongewijzigd
[x]: projectnaam encoderen in het API-pad `GET /api/v1/changelog/{name}`
[x]: eigen tests schrijven (deep link, changelog-scherm, deep-link-root)
[x]: run relevant tests (`flutter analyze`, `flutter test`, documentatie-audit)
[x]: docs/factory bijwerken (technical-spec + ux/screen-map)
[x]: update story-log with results

Done / rationale:

- **`lib/deep_link.dart` (nieuw).** `parseDeepLink(path)` levert `ChangelogDestination(project)` voor
  `/changelog/...` en `null` voor elk ander pad; `changelogPathFor(name)` bouwt het pad met
  `Uri.encodeComponent`. Bewust zonder Flutter-imports zodat het met gewone unit-tests te dekken is.
  Query/fragment worden afgeknipt, segmenten worden gedecodeerd, een projectnaam met `/` wordt weer
  samengevoegd, en een ongeldige escape-reeks (`%zz`) valt terug op de ruwe tekst i.p.v. te crashen.
  Een leeg/ontbrekend projectdeel geeft `ChangelogDestination('')` — het changelog-scherm toont dan
  zijn eigen foutmelding/lege staat (AC 7).
- **URL-strategie.** `lib/url_strategy_web.dart` roept `usePathUrlStrategy()` aan
  (`flutter_web_plugins`, uit de Flutter-SDK); `lib/url_strategy_stub.dart` is een no-op voor
  Android. Gekoppeld via dezelfde conditionele-importvorm als de bestaande GIS-knop. In `pubspec.yaml`
  staat `flutter_web_plugins: sdk: flutter` erbij — een SDK-pakket, geen nieuwe externe dependency,
  maar wel nodig om de import te laten resolven in `flutter analyze`.
- **`main.dart`.** `main()` zet eerst de URL-strategie en leest daarna op web éénmalig
  `Uri.base.path`. De afgeleide bestemming gaat als veld door naar `RootScreen`, dus ze blijft
  bestaan zolang de app draait: zowel bij een herstelde sessie (AC 4) als na een verse Google-login
  (AC 5) wordt daarna de changelog getoond in plaats van de app-shell. Bij een actieve bestemming
  rendert `RootScreen` `ChangelogScreen` direct als root-widget — geen `AppShell`, geen overlay, en
  omdat het de root van de navigator is ook geen terug-knop (AC 6).
- **`projects_screen.dart`.** De changelog-knop gaat via `_openChangelog`: op web
  `launchUrl(Uri.base.resolve(changelogPathFor(name)), webOnlyWindowName: '_blank')` (nieuw tabblad,
  bestaand `url_launcher`), op niet-web de ongewijzigde `Navigator.push` (AC 9).
- **`changelog_screen.dart`.** De projectnaam wordt nu met `Uri.encodeComponent` in het bestaande
  geauthenticeerde pad `GET /api/v1/changelog/{name}` gezet, zodat namen met spaties/speciale tekens
  ook via de URL werken. Verder ongewijzigd (zelfde inhoud, volgorde en lege staat, AC 2).
- De SPA-fallback in `nginx.conf` (`try_files $uri $uri/ /index.html`) serveert willekeurige paden al,
  dus er was geen webserver-wijziging nodig (aanname uit de story bevestigd).

Tests (zelf geschreven):

- `test/deep_link_test.dart` — changelog-pad levert de bestemming (a), willekeurig ander pad niet (b),
  plus encoding/decoding met spaties en speciale tekens, query/fragment, leeg projectdeel, ongeldige
  escape en een heen-en-weer-roundtrip (AC 10).
- `test/screens/changelog_screen_test.dart` — items in de aangeleverde volgorde, lege-staat-melding,
  geëncodeerd API-pad en geen terug-knop als root-pagina.
- `test/widget_test.dart` — met een geldige (opgeslagen) sessie toont een deep link direct de
  changelog i.p.v. de app-shell, en zonder deep link blijft het bestaande gedrag ongewijzigd.

Bewijs (11-08-2026, in `dashboard-frontend/`):

- `flutter pub get` — exit 0.
- `flutter analyze` — `No issues found!` (6,5s).
- `flutter test` — `All tests passed!`, 162 tests (was 152).

`repository-maven-verify` valt volgens `.factory/verification.yaml` buiten scope: de diff raakt
alleen `dashboard-frontend/` en `docs/` en geen enkel `pathPrefixes`-pad van dat commando. De
Kotlin-code is niet aangeraakt, dus de detekt/quality-ratchet verandert niet (AC 11).

Specs bijgewerkt:

- `docs/factory/technical-spec.md` — beschrijving van `dashboard-frontend` uitgebreid met de
  pad-URL-strategie, de deep-link-afleiding en de SPA-fallback die dat pad al serveert.
- `docs/factory/ux/screen-map.md` — routetabel uitgebreid met `/changelog/{project}` inclusief
  het web-vs-APK-gedrag van de knop en de zelfstandige-pagina-vorm.

## Review SF-2088 (11-08-2026) — afgekeurd

Eigen gerichte hercontrole (werktree schoon gebleven): `flutter analyze` = "No issues found!" (6,4s),
`flutter test test/deep_link_test.dart test/screens/changelog_screen_test.dart test/widget_test.dart`
= 15 tests groen, `tools/audit-documentation` = PASS. Specs (technical-spec + ux/screen-map) sluiten
aan op de diff. De deep-link-afleiding, de conditionele URL-strategie, het knopgedrag en de encoding
in het API-pad kloppen inhoudelijk.

**[blocker] Het adres in het changelog-tabblad wordt na het laden door Flutter zelf teruggezet naar `/`.**
`MaterialApp` (non-router, met `home:`) zet `Navigator(initialRoute: <browserpad>,
reportsRouteUpdateToEngine: true)`. Voor `/changelog/demo` levert `Navigator.defaultGenerateInitialRoutes`
geen route op (alleen `'/'` bestaat als route), valt terug op de home-route met `settings.name == '/'`
en meldt die naam daarna aan de engine. Met `usePathUrlStrategy()` doet
`SingleEntryBrowserHistory.setRouteName('/')` een `replaceState(..., '/')`, dus de adresbalk springt
van `/changelog/demo` naar `/`. Reproduceerbaar gemeten met een tijdelijke probe-test
(`platformDispatcher.defaultRouteNameTestValue = '/changelog/demo'` + mock op
`SystemChannels.navigation`): gemeld wordt `selectSingleEntryHistory` gevolgd door
`routeInformationUpdated {uri: /, replace: false}`, plus de framework-melding "Could not navigate to
initial route". Gevolg: de inhoud klopt wel, maar het tabblad staat op `/`; kopiëren/bookmarken vanuit
dat tabblad en een refresh leveren de app-shell in plaats van de changelog (AC 1 en de kern van AC 3).
Richting voor een fix: de gevraagde route zelf laten bestaan met behoud van "één route" (bv.
`MaterialApp.onGenerateInitialRoutes` die precies één route met `RouteSettings(name: initialRoute)`
teruggeeft), zodat de aangemelde naam gelijk is aan het gevraagde pad en er nog steeds geen terug-knop
is. Dekking is wél mogelijk: bovenstaande probe-opzet asserteert de aangemelde routenaam zonder
browser.

**[suggestie]** `test/widget_test.dart` — de test "zonder deep link blijft het bestaande
app-shell-gedrag ongewijzigd" asserteert alleen de afwezigheid van de changelog-tekst; die blijft ook
groen als de app-shell helemaal niet meer rendert. Assert liever een element van de app-shell zelf.

**[info]** `Uri.encodeComponent` encodeert `/` als `%2F`; een projectnaam met een slash komt daardoor
als encoded slash in `GET /api/v1/changelog/{name}` en wordt door Tomcat standaard geweigerd. Niet
realistisch voor bestaande projectnamen, geen actie nodig.
