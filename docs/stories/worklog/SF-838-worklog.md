# SF-838 - Worklog

Story-context bij eerste pickup:
Grote-letters-optie implementeren (app-brede tekstschaal)

Voeg een app-brede 'Grote letters'-instelling toe aan dashboard-frontend: notifier/state in main.dart om de MaterialApp-tekstschaal (via MediaQuery/textScaler) app-breed aan te sturen; laden/opslaan van de voorkeur via shared_preferences volgens het patroon van stories_filter_project in stories_screen.dart; nieuwe sectie met SwitchListTile in SettingsScreen (overview_screens.dart), analoog aan de bestaande 'Nightly ingeschakeld'-switch. Kies een gematigde vaste schaalfactor zodat bestaande panelen/tabellen niet breken. Voeg minimaal een widget-/unit-test toe (patroon test/widget_test.dart met SharedPreferences.setMockInitialValues) die het aan/uit-gedrag en het lokaal bewaren van de voorkeur dekt.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-839 geïmplementeerd in `dashboard-frontend`:
  - `lib/main.dart`: nieuwe `TextScalePreference` (statische `ValueNotifier<bool>` +
    `load()`/`setEnabled()`) die de voorkeur laadt/bewaart via `shared_preferences`
    (sleutel `large_text_enabled`), zelfde patroon als `stories_filter_project` in
    `stories_screen.dart`. `SoftwareFactoryDashboard` is StatefulWidget geworden zodat de
    voorkeur bij app-start geladen wordt; de `MaterialApp.builder` past een
    `MediaQuery`/`TextScaler.linear`-override toe rond de hele child-tree, dus alle
    schermen schalen mee. Vaste, gematigde schaalfactor `largeTextScale = 1.15` (niet
    instelbaar) zodat bestaande panelen/tabellen niet breken.
  - `lib/screens/overview_screens.dart`: nieuwe sectie `Weergave` in `SettingsScreen`
    (boven `Nightly-instellingen`) met `_TextScalePanel` — een `SwitchListTile` `Grote
    letters`, analoog aan de bestaande `Nightly ingeschakeld`-switch, die direct
    `TextScalePreference.setEnabled` aanroept (geen aparte opslaan-knop nodig).
  - `test/widget_test.dart`: 2 nieuwe widget-tests met `SharedPreferences.setMockInitialValues`
    die (1) het aan/uit-gedrag verifiëren via de effectieve `MediaQuery.textScaler` vóór/na
    `TextScalePreference.setEnabled(true)`, en (2) het laden vanuit en opslaan naar
    `shared_preferences` (`large_text_enabled`) dekken.
  - Docs bijgewerkt: `docs/factory/functional-spec.md` (nieuwe sectie "Grote letters — app-brede
    tekstschaal") en `docs/factory/ux/screens/settings.md` (nieuwe sectie "Weergave"), zodat de
    specs de nieuwe instelling weerspiegelen.
- Kon `flutter analyze`/`flutter test` niet lokaal draaien (geen flutter-binary in deze
  devomgeving, bekende beperking, zie agent-tips `build / flutter-unavailable-offline`);
  wijzigingen zijn statisch nagelopen (imports, API-gebruik van `TextScaler.linear`,
  `ValueListenableBuilder`, `SwitchListTile`) en laat CI de test-suite draaien.
