# SF-1676 - Dashboard-scherm verwijderen + audit-memory als volledige pagina

## Story

Dashboard-scherm verwijderen + audit-memory als volledige pagina

<!-- refined-by-factory -->

## Samenvatting

Twee opschoningen in de Flutter-app.

Het Dashboard-overzichtsscherm wordt nooit gebruikt en verdwijnt helemaal: het item is weg uit de navigatie en het scherm zelf bestaat niet meer. Alle andere schermen blijven precies werken zoals nu.

Daarnaast opent 'Open memory' in het Audits-scherm nu nog een klein popup-venster. Op de telefoon is dat een smalle koker waarin de tekst na twee, drie woorden afbreekt. Dat wordt een volwaardige pagina met een terug-knop, zodat de memory-tips gewoon leesbaar zijn. Toevoegen, bewerken en verwijderen van tips blijft ongewijzigd werken.

## Scope

Alles in `dashboard-frontend`.

**1. Dashboard-scherm verwijderen**
- Verwijder de nav-entry `'Dashboard'` uit `_primaryEntries` in `lib/app_shell.dart` (~r39). De bottom-nav houdt dan drie primaire items (Stories, My actions, Agents) plus 'Meer'; `_secondaryEntries` (Projects, Builds, App-updates, Audits, Settings) blijft ongewijzigd.
- Verwijder `lib/screens/dashboard_overview_screen.dart` (inclusief de private helper `_Metric`, die alleen daar wordt gebruikt) en de regel `export 'dashboard_overview_screen.dart';` uit `lib/screens/overview_screens.dart`.
- Verwijder `test/screens/dashboard_overview_screen_test.dart`; die test bouwt `DashboardOverviewScreen` en kan niet blijven staan.
- Ruim imports op die hierdoor ongebruikt raken. `lib/screens/overview_screens.dart` blijft bestaan als barrel-export (wordt nog geïmporteerd door `app_shell.dart` en door `projects_screen_test.dart` / `settings_screen_test.dart`).

**2. Audit-memory als volledige pagina**
- Vervang `_AuditMemoryDialog` in `lib/screens/audit_screen.dart` door een paginascherm en open het via `Navigator.push(MaterialPageRoute(...))` in plaats van `showDialog` op de 'Open memory'-knop (~r235).
- Volg het bestaande patroon van `_AuditReportDetailScreen` (~r416) qua opbouw en styling: eigen `Scaffold` met `AppBar`, titel `'Memory — <auditType>'`, de standaard terug-knop van de `AppBar`, en dezelfde breedte-aanpak (links uitgelijnd, `ConstrainedBox(maxWidth: 860)`) zodat lange tips leesbaar blijven op zowel telefoon als brede monitor.
- De '+'-knop voor een nieuwe tip verhuist naar de `AppBar` als action; de losse sluitknop (`Icons.close`) uit de dialog vervalt, want de `AppBar` heeft al een terug-knop.
- De bestaande laad- en muteerlogica blijft functioneel identiek: ophalen van `/api/v1/audit-memory` met client-side filter op (`project`, `auditType`), `POST /api/v1/audit-memory/update` en `POST /api/v1/audit-memory/delete`, plus de foutafhandeling via `showActionResult` / `ErrorBanner` en de loading-spinner en `EmptyState('Nog geen memory-tips.')`.
- Bewerken en verwijderen van een tip blijven kleine `AlertDialog`s zoals nu.

**Buiten scope**
- Geen wijzigingen in dashboard-backend of softwarefactory: `/api/v1/dashboard`, de bridge-operatie `dashboard.get`, `DashboardApi.dashboard()` en `DashboardQueryService.dashboard()` blijven staan (zie Aannames).
- Geen wijzigingen aan de andere audit-onderdelen (`_AuditReportsDialog`, `_AuditReportDetailScreen`, `_AuditQuestionBox`, 'Run now').
- Documentatie in `docs/factory/ux/` (o.a. `screens/dashboard.md`, `screen-map.md`) hoort bij de documenter-stap, niet bij deze wijziging.

## Acceptance criteria

1. Er staat geen 'Dashboard'-item meer in de NavigationRail (breed) of de bottom-nav (smal); de bottom-nav toont Stories, My actions, Agents en 'Meer'.
2. `lib/screens/dashboard_overview_screen.dart` en `test/screens/dashboard_overview_screen_test.dart` bestaan niet meer, en er is nergens in `lib/` of `test/` nog een verwijzing naar `DashboardOverviewScreen` of `dashboard_overview_screen.dart`.
3. De overige nav-items (Stories, My actions, Agents, en Projects/Builds/App-updates/Audits/Settings onder 'Meer') openen nog steeds het juiste scherm; de badge-tellers op 'My actions' en 'Audits' werken nog.
4. Vanuit Audits → 'Open memory' opent een volledige pagina met een `AppBar` met titel `Memory — <auditType>` en een werkende terug-knop; de tips gebruiken de volle beschikbare breedte in plaats van een 480px-popup.
5. Op die pagina werken toevoegen (+), bewerken en verwijderen van een memory-tip nog steeds, en de lijst ververst na elke actie; fouten tonen dezelfde melding als nu.
6. Alleen tips van de betreffende (project, auditType) worden getoond; de aanroep van `/api/v1/audit-memory` is ongewijzigd.
7. `flutter analyze` en `flutter test` in `dashboard-frontend` zijn schoon — geen ongebruikte imports, geen dode verwijzingen, geen falende tests.

## Aannames

- Het backend-endpoint `/api/v1/dashboard` en de hele keten erachter (`dashboard.get` → `DashboardApi.dashboard()` → `DashboardQueryService.dashboard()` → `DashboardPageData`) voedde alleen dit ene scherm, maar blijft bewust staan: al uitgerolde APK-versies in gebruik blijven het aanroepen en er hangt bestaande testdekking aan (`BridgeRequestHandlerTest`). Opruimen is een aparte, latere opschoningsstory.
- Het startscherm blijft Stories (`selectedIndex` start op 0); het verwijderen van Dashboard verandert niets aan welk scherm bij openen laadt.
- Er komt geen vervangend overzichtsscherm en geen redirect; het Dashboard verdwijnt zonder alternatief.
- De memory-pagina hoeft geen eigen route-naam of deeplink; `Navigator.push` met een `MaterialPageRoute` volstaat, net als bij het rapport-detailscherm.
- Er wordt geen nieuwe widget-test geëist voor de memory-pagina; bestaande dekking plus een schone `flutter analyze`/`flutter test` is voldoende. Een test toevoegen mag, maar is geen acceptatiecriterium.

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de diff gelezen en de kernclaims machinaal geverifieerd (0 hits op `DashboardOverviewScreen`, `_primaryEntries` = 3 items, `AuditMemoryScreen` via `Navigator.push`).

## Eindsamenvatting SF-1676 — Dashboard-scherm verwijderen + audit-memory als volledige pagina

### Wat is gebouwd

**1. Dashboard-scherm helemaal weg**
- Het nav-item 'Dashboard' is uit `lib/app_shell.dart` verwijderd. De bottom-nav toont nu Stories, My actions, Agents en 'Meer'; de NavigationRail bevat 8 items zonder Dashboard. De groep onder 'Meer' (Projects, Builds, App-updates, Audits, Settings) is ongewijzigd.
- `lib/screens/dashboard_overview_screen.dart` (inclusief de alleen daar gebruikte `_Metric`-helper), de bijbehorende test en de export-regel uit de barrel `overview_screens.dart` zijn verwijderd. De barrel zelf blijft bestaan (nog in gebruik door andere schermen en tests).
- Startscherm blijft Stories; er is geen vervangend overzicht en geen redirect.

**2. Audit-memory is nu een volledige pagina**
- 'Open memory' in het Audits-scherm opent geen popup meer maar een echte pagina (`AuditMemoryScreen`) met een AppBar, titel `Memory — <auditType>` en de standaard terug-knop.
- De pagina volgt exact het patroon van het bestaande rapport-detailscherm: links uitgelijnd met een maximale leesbreedte van 860px. Op de telefoon gebruiken de tips daarmee de volle breedte in plaats van de oude 480px-koker.
- De '+'-knop voor een nieuwe tip staat nu als actie in de AppBar; de losse sluitknop is vervallen (de AppBar heeft al een terug-knop). Bewerken en verwijderen blijven kleine dialoogjes.

### Gemaakte keuzes

- **Backend bewust ongemoeid gelaten.** `/api/v1/dashboard`, de bridge-operatie `dashboard.get`, `DashboardApi.dashboard()` en `DashboardQueryService.dashboard()` blijven staan, omdat al uitgerolde APK-versies die nog aanroepen. Opruimen hoort in een aparte, latere opschoningsstory.
- **Laad- en muteerlogica ongewijzigd overgenomen.** Het ophalen van de tips, het filter op (project, auditType), toevoegen/bijwerken/verwijderen, de foutmeldingen, de laad-spinner en de lege staat zijn regel-voor-regel gelijk gebleven aan de oude popup — puur een verpakkingswijziging, geen gedragswijziging.
- **Geen eigen route/deeplink** voor de memory-pagina; hij wordt geopend zoals het rapport-detailscherm.
- De memory-pagina is publiek gemaakt zodat de widget-test hem rechtstreeks kan bouwen.

### Wat is getest

- `flutter analyze`: geen enkele melding.
- `flutter test`: 111 tests groen, geen flakes.
- `flutter build web --release`: succesvol — bewijst dat het ook echt compileert buiten de testomgeving.
- Documentatie-audit: PASS. Volledige Maven-build vanaf repo-root: BUILD SUCCESS (voor de zekerheid, viel buiten scope).
- Twee nieuwe tests toegevoegd: één voor de navigatie (bottom-nav is exact Stories/My actions/Agents/Meer, geen Dashboard in de rail, 'Meer' → 'Audits' werkt nog) en één voor de memory-pagina (filtering, lege staat, verwijderen ververst de lijst, '+' opent het formulier, en er is aantoonbaar geen popup meer).
- Alle 7 acceptatiecriteria zijn expliciet nagelopen door reviewer en tester; beiden zonder bevindingen.

### Bewust niet gedaan

- **Geen documentatie bijgewerkt.** `docs/factory/ux/screens/dashboard.md` en `ux/screen-map.md` beschrijven het Dashboard-scherm nog en zijn nu tijdelijk inconsistent met de code. Dat is belegd bij de volgende subtaak SF-1680, die vóór de merge draait — aandachtspunt daar: naast de schermbeschrijving ook de nav-lijst en het diagram in `screen-map.md`.
- **Geen klikbare end-to-end-test met screenshots.** In de testomgeving was geen browser/preview-URL beschikbaar; de release-webbuild is als dichtstbijzijnde vervanging gedraaid.
- **Eén pre-existent opruimpuntje laten staan:** in het bewerk-dialoogje worden twee tekstvelden niet netjes opgeruimd (geen `dispose()`). Dat stond er al vóór deze story en is bewust buiten de diff gehouden; kandidaat voor een opruimstory.
