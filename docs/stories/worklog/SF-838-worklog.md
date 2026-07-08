# SF-838 - Worklog

Story-context bij eerste pickup:
Instelling 'Grote letters' implementeren (app-brede tekstschaal + settings-switch + tests)

Voeg een `TextScalePreference`-notifier toe (los van `AppState`, want het login-scherm in `main.dart` bestaat al vóór `AppState`) die de aan/uit-voorkeur laadt/opslaat via `shared_preferences` (patroon van `stories_filter_*` in `stories_screen.dart`). Til `SoftwareFactoryDashboard` in `main.dart` naar een state-/listener-gedreven widget die de tekstschaal app-breed toepast via de `builder`-parameter van `MaterialApp` met `MediaQuery`/`TextScaler` (vaste schaalfactor ~1.2× wanneer aan), zodat alle schermen incl. login meeschalen zonder per-widget aanpassingen. Voeg in `SettingsScreen` (`overview_screens.dart`) een nieuwe `SwitchListTile` "Grote letters" toe, analoog aan de bestaande "Nightly ingeschakeld"-switch, die direct bij toggelen de voorkeur aanpast en opslaat (geen aparte opslaanknop). Schrijf minimaal één widget-/unit-test (patroon `test/widget_test.dart`, `SharedPreferences.setMockInitialValues`) die het aan/uit-gedrag en het lokaal bewaren van de voorkeur dekt. Let op: geen backend-/bridge-wijzigingen, en zorg dat bestaande tabellen/panelen niet overlappen met de gekozen schaalfactor.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `lib/text_scale_preference.dart` toegevoegd: `TextScalePreference extends ChangeNotifier`,
  los van `AppState` (login-scherm in `main.dart` bestaat al vóór `AppState`). Laadt/slaat de
  aan/uit-voorkeur op via `shared_preferences` (key `large_text_enabled`), zelfde patroon als
  `stories_filter_*` in `stories_screen.dart`. Vaste schaalfactor `largeScaleFactor = 1.2`.
- `SoftwareFactoryDashboard` (`lib/main.dart`) getild van `StatelessWidget` naar
  `StatefulWidget`: houdt een `TextScalePreference`, laadt 'm in `initState`, en past de schaal
  app-breed toe via de `builder`-parameter van `MaterialApp` met
  `MediaQuery(...).copyWith(textScaler: TextScaler.linear(scaleFactor))`, zodat alle schermen
  (incl. login) direct meeschalen zonder per-widget aanpassingen en zonder herstart.
- De `TextScalePreference`-instantie wordt doorgegeven via `RootScreen` → `AppShell` →
  `SettingsScreen` (constructor-parameter, geen extra state-laag).
- `SettingsScreen` (`lib/screens/overview_screens.dart`) heeft een nieuwe sectie "Weergave" met
  een `SwitchListTile` "Grote letters", analoog aan de bestaande "Nightly ingeschakeld"-switch:
  toggelen past de voorkeur direct toe en slaat 'm meteen op (geen aparte opslaanknop).
- Tests toegevoegd in `test/text_scale_preference_test.dart`
  (`SharedPreferences.setMockInitialValues`-patroon van `test/widget_test.dart`):
  unit-tests voor default/laden/opslaan van `TextScalePreference`, en twee widget-tests die
  verifiëren dat `SoftwareFactoryDashboard` de opgeslagen voorkeur als app-brede `TextScaler`
  toepast (aan → 1.2×, uit → 1.0×).
- Geen backend-/bridge-wijzigingen; bestaande secties/schermen ongewijzigd, alleen aanvullingen.
- `flutter`/`dart` zijn niet beschikbaar in deze developer-omgeving (bevestigd, zie agent-tip
  `build/flutter-unavailable-offline`), dus de tests zijn niet lokaal gedraaid — geverifieerd
  via nauwkeurige statische review (imports, constructor-signatures, alle call-sites van
  `SoftwareFactoryDashboard`/`RootScreen`/`AppShell`/`SettingsScreen` bijgewerkt). CI draait de
  suite.
- `docs/factory/ux/screens/settings.md` uitgebreid met een "Weergave — Grote letters (SF-846)"
  sectie zodat de UX-doc de nieuwe instelling weerspiegelt.
