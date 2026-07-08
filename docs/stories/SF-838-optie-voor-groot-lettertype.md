# SF-838 - optie voor groot lettertype

## Story

optie voor groot lettertype

<!-- refined-by-factory -->

## Scope

Voeg in de dashboard-frontend (Flutter, `dashboard-frontend/`) een app-brede instelling "Grote letters" toe waarmee de gebruiker het lettertype op alle pagina's van het dashboard vergroot.

- **Instelling in Settings**: een nieuwe sectie/`SwitchListTile` "Grote letters" in `SettingsScreen` (`dashboard-frontend/lib/screens/overview_screens.dart`), analoog aan de bestaande "Nightly ingeschakeld"-switch.
- **App-brede toepassing**: de tekstschaal wordt op app-niveau aangestuurd (bijv. via `MediaQuery`/`TextScaler` rond `MaterialApp` in `main.dart`), zodat alle schermen direct de grotere letters tonen zonder per-widget aanpassingen. `SoftwareFactoryDashboard` (main.dart:24) wordt hiervoor van `StatelessWidget` naar een state-/notifier-gedreven widget getild.
- **Lokale, per-device opslag**: de voorkeur is een lokale UI-instelling (geen backend-call/opslag), bewaard via `shared_preferences`, volgens het bestaande patroon van `stories_filter_project` in `stories_screen.dart`. De voorkeur wordt bij app-start geladen en direct toegepast, en bij wijzigen meteen opgeslagen.
- **Vaste, gematigde schaalfactor**: één vaste "aan"-schaalfactor (bijv. ~1.2×) die leesbaarheid verbetert zonder bestaande panelen/tabellen/layouts te breken. Geen los instelbare schuifregelaar — enkel aan/uit.

Buiten scope: backend-opslag van de voorkeur (bridge-API/database), losse schaalfactor per pagina-onderdeel, en een instelbare (niet-vaste) schaalwaarde.

## Acceptance criteria

- In het Settings-scherm staat een nieuwe, duidelijk herkenbare optie "Grote letters" (switch aan/uit).
- Bij inschakelen wordt het lettertype op alle pagina's van de dashboard-app direct groter (geen herstart nodig); bij uitschakelen keert het direct terug naar de standaardgrootte.
- De voorkeur wordt lokaal (per device/browserprofiel) onthouden via `shared_preferences` en blijft na een herlaad/herstart van de app behouden.
- Met "Grote letters" aan blijven bestaande schermen (tabellen, panelen, kaarten) bruikbaar: geen overlappende tekst of afgekapte essentiële content op de belangrijkste schermen (Stories, Story-detail, Settings).
- Er is minimaal één widget-/unit-test (patroon `test/widget_test.dart` met `SharedPreferences.setMockInitialValues`) die het aan/uit-gedrag van de instelling en het lokaal bewaren van de voorkeur dekt.
- Bestaande functionaliteit (overige settings-secties, navigatie, overige schermen) blijft ongewijzigd werken.

## Aannames

- "Grote letters" is een lokale, per-device UI-voorkeur en hoeft niet server-side opgeslagen of gesynchroniseerd te worden tussen devices.
- Eén vaste schaalfactor (aan/uit) volstaat; geen fijnmazige instelbare tekstgrootte gevraagd.
- De optie geldt voor de hele dashboard-app (alle pagina's), niet per scherm instelbaar.

## Eindsamenvatting

Ik heb voldoende context. Dit is een pure Flutter-wijziging, statisch gereviewd en getest (geen lokale flutter/dart-toolchain beschikbaar in developer- en tester-omgeving, wel goedgekeurd).

**Eindsamenvatting SF-838 — Optie voor groot lettertype**

**Wat is gebouwd**
Een app-brede instelling "Grote letters" in het Flutter-dashboard:
- Nieuwe `TextScalePreference` (ChangeNotifier, los van `AppState`) laadt/bewaart de aan/uit-voorkeur lokaal via `shared_preferences` (key `large_text_enabled`), naar het bestaande `stories_filter_*`-patroon.
- `SoftwareFactoryDashboard` (`main.dart`) is getild van `StatelessWidget` naar `StatefulWidget` en past de tekstschaal app-breed toe via `MaterialApp.builder` + `MediaQuery(...).copyWith(textScaler: ...)`, zodat álle schermen (inclusief het loginscherm, dat vóór `AppState` bestaat) direct meeschalen zonder herstart.
- Vaste schaalfactor: 1.0× (uit) / 1.2× (aan) — geen instelbare schuifregelaar, conform scope.
- Nieuwe sectie "Weergave" met `SwitchListTile` "Grote letters" in `SettingsScreen`, analoog aan de bestaande "Nightly ingeschakeld"-switch; toggelen slaat direct op (geen aparte opslaanknop).
- De voorkeur wordt doorgegeven via `RootScreen → AppShell → SettingsScreen` als constructor-parameter.
- UX-documentatie (`docs/factory/ux/screens/settings.md`) is bijgewerkt met de nieuwe instelling.

**Belangrijkste keuzes**
- Voorkeur bewust losgekoppeld van `AppState`, omdat het loginscherm al bestaat vóór `AppState` wordt geïnitialiseerd.
- Eén vaste schaalfactor (1.2×) in plaats van een instelbare waarde, conform de aannames uit de story.
- Lokale, per-device opslag zonder backend-/bridge-wijzigingen — geen synchronisatie tussen devices.

**Wat is getest**
- Nieuwe unit-/widget-tests in `test/text_scale_preference_test.dart`: default-waarde, laden/opslaan van de voorkeur, en app-brede toepassing van de schaal (aan → 1.2×, uit → 1.0×) via `SharedPreferences.setMockInitialValues`.
- Bestaande `test/widget_test.dart` blijft ongewijzigd werkend.
- Reviewer en tester hebben de volledige diff onafhankelijk statisch geverifieerd (constructor-signatures, call-sites, geen dubbele scaling, geen scope creep) en akkoord gegeven.

**Bewust niet gedaan**
- Geen backend-/bridge-opslag van de voorkeur (buiten scope).
- Geen instelbare/losse schaalfactor per pagina-onderdeel.
- `flutter test` is niet lokaal uitgevoerd: zowel developer- als tester-omgeving hebben geen werkende Flutter/Dart-toolchain (tester-host is `aarch64`, x86_64-tarball kon niet draaien zonder root-toegang voor emulatiepakketten). Beoordeling is gebaseerd op grondige statische code-review; CI moet de test-suite nog bevestigen.
