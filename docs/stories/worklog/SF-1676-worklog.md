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
