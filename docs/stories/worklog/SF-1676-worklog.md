# SF-1676 - Worklog

Story-context bij eerste pickup:
Dashboard-scherm verwijderen en audit-memory omzetten naar volledige pagina

Alles in dashboard-frontend.

1) Dashboard-scherm verwijderen:
- Haal de _NavEntry 'Dashboard' uit _primaryEntries in lib/app_shell.dart (~r39). Bottom-nav/NavigationRail houden Stories, My actions, Agents (+ 'Meer'); _secondaryEntries (Projects, Builds, App-updates, Audits, Settings) blijft ongewijzigd. De 'Meer'-logica rekent met _primaryEntries.length en badges gaan op label, dus geen index-aanpassingen nodig - verifieer dit wel.
- Verwijder lib/screens/dashboard_overview_screen.dart (inclusief de alleen daar gebruikte private helper _Metric) en de regel export 'dashboard_overview_screen.dart'; uit lib/screens/overview_screens.dart. De barrel zelf blijft bestaan.
- Verwijder test/screens/dashboard_overview_screen_test.dart.
- Ruim imports op die hierdoor ongebruikt raken. Controleer met grep dat er in lib/ en test/ geen verwijzing meer is naar DashboardOverviewScreen of dashboard_overview_screen.dart.
- Raak dashboard-backend/softwarefactory NIET aan: /api/v1/dashboard, dashboard.get, DashboardApi.dashboard() en DashboardQueryService.dashboard() blijven bewust staan (uitgerolde APK's roepen ze nog aan). Vermeld dit in de samenvatting.

2) Audit-memory als volledige pagina:
- Vervang _AuditMemoryDialog in lib/screens/audit_screen.dart (~r507) door een paginascherm en open het vanaf de 'Open memory'-knop (~r235) via Navigator.push(MaterialPageRoute(...)) in plaats van showDialog.
- Volg het bestaande patroon van _AuditReportDetailScreen (~r418): eigen Scaffold met AppBar, titel 'Memory - <auditType>', standaard terug-knop, en dezelfde breedte-aanpak (links uitgelijnd, ConstrainedBox(maxWidth: 860)).
- De '+'-knop voor een nieuwe tip wordt een AppBar-action; de losse Icons.close-sluitknop vervalt.
- Laad- en muteerlogica blijft functioneel identiek: ophalen van /api/v1/audit-memory met client-side filter op (project, auditType), POST /api/v1/audit-memory/update, POST /api/v1/audit-memory/delete, foutafhandeling via showActionResult/ErrorBanner, loading-spinner en EmptyState('Nog geen memory-tips.'). Lijst ververst na elke actie.
- Bewerken en verwijderen blijven kleine AlertDialogs.
- Let op use_build_context_synchronously: na elke await een mounted-check vóór gebruik van context/setState.
- Behoud het gedrag dat het audit-scherm na terugkeer in een consistente staat is (kijk of de dialog een resultaat teruggaf waar de aanroeper op reageerde).

Afronding: pas bestaande tests aan waar nodig (nieuwe widget-test voor de memory-pagina mag, is geen eis) en zorg dat flutter analyze en flutter test in dashboard-frontend schoon zijn. Is er lokaal geen Flutter SDK, meld dat expliciet in de worklog. Doe daarna zelf een review van je diff (dode code, ongebruikte imports, gedragsbehoud).

Stappenplan:
[x]: read issue and target docs
[x]: Dashboard-scherm + nav-entry + test verwijderen
[x]: audit-memory van dialog naar volledige pagina
[x]: tests schrijven (memory-pagina + app-shell-navigatie)
[x]: run relevant tests (flutter analyze/test, mvn verify, documentation audit)
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## Uitvoering (developer, SF-1677)

### 1. Dashboard-scherm verwijderd
- `lib/app_shell.dart`: de `_NavEntry('Dashboard', ...)` uit `_primaryEntries` gehaald. Geverifieerd
  dat er geen index-aannames zijn: de 'Meer'-tak rekent met `_primaryEntries.length` en
  `_navIcon` matcht op label ('My actions'/'Audits'), dus de badge-tellers blijven werken. De
  doc-comment 'de vier meest gebruikte secties' is naar 'de drie' bijgewerkt.
- `lib/screens/dashboard_overview_screen.dart` (incl. de private `_Metric`-helper) en
  `test/screens/dashboard_overview_screen_test.dart` verwijderd; de export-regel uit de barrel
  `lib/screens/overview_screens.dart` gehaald (de barrel zelf blijft, wordt nog gebruikt door
  `app_shell.dart`, `projects_screen_test.dart` en `settings_screen_test.dart`).
- `grep -rn 'DashboardOverviewScreen\|dashboard_overview_screen' lib test` geeft geen hits meer.
- Backend bewust ongemoeid: `/api/v1/dashboard`, bridge-op `dashboard.get`, `DashboardApi.dashboard()`
  en `DashboardQueryService.dashboard()` blijven staan omdat al uitgerolde APK's die nog aanroepen.

### 2. Audit-memory als volledige pagina
- `_AuditMemoryDialog` in `lib/screens/audit_screen.dart` vervangen door `AuditMemoryScreen`: eigen
  `Scaffold` + `AppBar` met titel `Memory — <auditType>`, standaard terug-knop, en dezelfde
  breedte-aanpak als `_AuditReportDetailScreen` (`Align(topLeft)` + `ConstrainedBox(maxWidth: 860)`).
  De 'Open memory'-knop opent hem via `Navigator.push(MaterialPageRoute(...))` i.p.v. `showDialog`.
- De '+'-knop staat nu als `AppBar`-action; de losse `Icons.close`-knop is vervallen (de `AppBar`
  heeft al een terug-knop).
- Laad-/muteerlogica is functioneel identiek gebleven: `/api/v1/audit-memory` met client-side filter
  op (project, auditType), `POST .../update` en `POST .../delete`, `showActionResult`/`ErrorBanner`,
  loading-spinner en `EmptyState('Nog geen memory-tips.')`; bewerken/verwijderen blijven `AlertDialog`s.
  De klasse is public gemaakt zodat de widget-test hem rechtstreeks kan bouwen.
- De oude dialog gaf geen resultaat terug en de aanroeper deed niets bij sluiten; de nieuwe pagina
  doet dat ook niet, dus het audit-scherm staat na terugkeer in dezelfde staat als voorheen.

### Tests (zelf geschreven)
- `test/screens/audit_memory_screen_test.dart` (nieuw): filtering op (project, auditType), AppBar-titel
  en 'geen Dialog meer', lege staat, verwijderen post naar de delete-endpoint en ververst de lijst, en
  de +-knop in de AppBar opent het formulier. Let op: interacties ná het openen moeten binnen dezelfde
  `http.runWithClient`-zone draaien, anders zien ze de MockClient niet meer.
- `test/screens/app_shell_test.dart` (nieuw): bottom-nav is exact ['Stories','My actions','Agents','Meer'],
  de NavigationRail bevat geen 'Dashboard' meer, en 'Meer' → 'Audits' navigeert nog steeds.

### Vangnet (01-08-2026)
- `flutter analyze` in `dashboard-frontend`: **No issues found!** (exit 0).
- `flutter test` in `dashboard-frontend`: **111 tests, All tests passed** (exit 0).
- `mvn -B --no-transfer-progress clean verify` vanaf repo-root: **BUILD SUCCESS**, exit 0, 0 failures /
  0 errors (~4m13; strikt genomen buiten de pathPrefixes van `repository-maven-verify`, maar voor de
  zekerheid gedraaid).
- `tools/audit-documentation`: `documentation-audit/v1: PASS` (exit 0).
- `pubspec.lock` is door `flutter pub get` niet gewijzigd (geen ruis in de diff).

### Specs
- Geen `docs/factory/`-specs aangepast: de refined story belegt de UX-documentatie
  (`ux/screens/dashboard.md`, `ux/screen-map.md`) expliciet bij de documenter-stap (SF-1680).

## Review (reviewer, SF-1677, 01-08-2026)

Beoordeeld: volledige story-diff `git diff main...HEAD` (8 bestanden, 1 commit `8e9ff3d`).

Bevindingen: geen blockers. Akkoord.

- [info] AC1-3 (Dashboard weg): `_primaryEntries` bevat nog Stories/My actions/Agents; `_openMoreSheet`
  rekent met `_primaryEntries.length` en `_navIcon` matcht op label, dus geen index-regressie op de
  badge-tellers of de 'Meer'-tak. Scherm, barrel-export en oude test zijn weg; grep op
  `DashboardOverviewScreen|dashboard_overview_screen` in `dashboard-frontend/` geeft 0 hits.
- [info] AC4-6 (memory-pagina): `AuditMemoryScreen` volgt exact het patroon van
  `_AuditReportDetailScreen` (`Scaffold`/`AppBar`, `Align(topLeft)` + `ConstrainedBox(maxWidth: 860)`);
  '+' als AppBar-action, `Icons.close` weg. Laad-/muteerlogica, het client-side (project, auditType)-filter,
  `showActionResult`/`ErrorBanner`, spinner en `EmptyState` zijn regel-voor-regel ongewijzigd t.o.v. de
  oude dialog; `mounted`-checks staan na elke await. De oude dialog gaf geen resultaat terug en de
  aanroeper reageerde nergens op, dus `Navigator.push` zonder resultaatafhandeling is gedragsbehoudend.
- [info] Vangnet zelf gericht herhaald in de reviewomgeving op deze HEAD: `flutter analyze` →
  "No issues found!" (exit 0), `flutter test` → 111/111 groen (exit 0), `pubspec.lock` na de run
  ongewijzigd (`git status` schoon). Geen wijziging in `.factory/verification.yaml`.
- [info] Scope: diff raakt alleen `dashboard-frontend/` + dit worklog; backendketen achter
  `/api/v1/dashboard` bewust ongemoeid conform Aannames. Geen secrets in de diff.
- [suggestie] `_AuditMemoryScreenState._openNoteDialog` maakt nog steeds twee `TextEditingController`s
  zonder `dispose()`. Pre-existent en terecht buiten deze diff gehouden; kandidaat voor een
  opruimstory.
- [info] `docs/factory/ux/screen-map.md` (r9/31/57-65) en `ux/screens/dashboard.md` beschrijven het
  Dashboard-scherm nog. Dat is nu inconsistent met de code, maar de refined story belegt dit expliciet
  bij documenter-subtaak SF-1680, die vóór de merge (SF-1681) draait. Aandachtspunt voor SF-1680:
  behalve `screens/dashboard.md` ook de nav-lijst en het mermaid-diagram in `screen-map.md` bijwerken.

## Test (tester, SF-1678, 01-08-2026)

Getest op HEAD `f9cf275` (branch `ai/SF-1676`); diff raakt uitsluitend `dashboard-frontend/` +
dit worklog, dus van `.factory/verification.yaml` matchen `dashboard-flutter-*` en
`repository-documentation-audit` (geen pathPrefixes); `repository-maven-verify` valt buiten scope.

Uitgevoerd:
- `flutter pub get` (dashboard-frontend) → exit 0, `pubspec.lock` ongewijzigd.
- `flutter analyze` → **No issues found!**, exit 0.
- `flutter test` → **111 tests, All tests passed**, exit 0, geen flakes.
- `flutter build web --release` → exit 0 (`✓ Built build/web`); bevestigt dat de wijziging ook naar
  het echte webtarget compileert, niet alleen onder de testbinding. `build/` daarna opgeruimd.
- `tools/audit-documentation` → `documentation-audit/v1: PASS`, exit 0.

AC-verificatie:
- AC1/AC2: `_primaryEntries` = Stories/My actions/Agents; `_secondaryEntries` ongewijzigd.
  `grep -rn 'DashboardOverviewScreen\|dashboard_overview_screen' dashboard-frontend --include='*.dart'`
  → 0 hits; beide bestanden bestaan niet meer; barrel-export weg. Gedragsbewijs via
  `app_shell_test.dart`: bottom-nav is exact `['Stories','My actions','Agents','Meer']`, rail bevat
  8 items zonder Dashboard.
- AC3: `_openMoreSheet` rekent met `_primaryEntries.length` en `_navIcon` matcht op label
  ('My actions'/'Audits'), dus geen index-regressie op de badges; 'Meer' → 'Audits' navigeert nog.
- AC4: 'Open memory' doet `Navigator.push(MaterialPageRoute(...))` naar `AuditMemoryScreen`;
  `Scaffold` + `AppBar('Memory — <auditType>')` levert de standaard terug-knop, en
  `Align(topLeft)` + `ConstrainedBox(maxWidth: 860)` geeft op telefoonbreedte de volle breedte
  i.p.v. de oude 480px-popup. Test asserteert ook `find.byType(Dialog) == findsNothing`.
- AC5/AC6: laad- en muteerlogica regel-voor-regel gelijk aan de oude dialog (client-side filter op
  (project, auditType), `/api/v1/audit-memory` + `.../update` + `.../delete`, `showActionResult`,
  `ErrorBanner`, spinner, `EmptyState`); `mounted`-checks na elke await. Gedekt door
  `audit_memory_screen_test.dart` (filtering, lege staat, delete + herlaad, '+' opent formulier).
- AC7: analyze en test schoon (zie boven).

Beperking: in de tester-sandbox is geen browser en geen `SF_PREVIEW_URL` beschikbaar, dus geen
klikbare E2E-run en geen screenshots in `/work/screenshots`. `flutter build web --release` is
daarvoor als dichtstbijzijnde vervanging gedraaid.

Conclusie: geen bevindingen, geen flakes. Akkoord.
