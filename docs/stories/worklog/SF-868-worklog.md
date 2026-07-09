# SF-868 - Worklog

Story-context bij eerste pickup:
GitHub Actions-link toevoegen aan Settings-scherm

Voeg in dashboard-frontend/lib/screens/overview_screens.dart (_SettingsScreenState.build) een nieuwe sectie toe na de bestaande 'Versie'-sectie met een knop 'GitHub Actions' die via launchUrl(Uri.parse('https://github.com/robbertvdzon/software-factory/actions'), mode: LaunchMode.externalApplication) opent - exact het patroon dat al gebruikt wordt op regels 252 en 702 in hetzelfde bestand. Geen backend-wijzigingen, geen nieuwe dependency (url_launcher is al aanwezig). Voeg indien passend een (widget)test toe die bevestigt dat de knop aanwezig is en de juiste URL/LaunchMode gebruikt.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[~]: run relevant tests (geen lokale Flutter-toolchain beschikbaar, zie hieronder)
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- In `dashboard-frontend/lib/screens/overview_screens.dart` (`_SettingsScreenState.build`)
  een `FilledButton.tonalIcon` "GitHub Actions" toegevoegd onderin de bestaande "Versie"-Panel,
  die met `launchUrl(Uri.parse('https://github.com/robbertvdzon/software-factory/actions'),
  mode: LaunchMode.externalApplication)` opent — exact hetzelfde patroon als de bestaande
  `launchUrl`-aanroepen op regel 252 (PR-link) en regel 702 (download-link) in hetzelfde bestand.
  Geen backend-wijziging, geen nieuwe dependency (`url_launcher` was al aanwezig).
- Widget-test toegevoegd: `dashboard-frontend/test/screens/settings_screen_test.dart`. Deze
  mockt de HTTP-laag via `http.runWithClient(...)` + `MockClient` (beide uit het al aanwezige
  `http`-package, geen nieuwe dependency nodig) zodat `SettingsScreen` zonder echte
  bridge-backend gemount kan worden, en controleert dat de "GitHub Actions"-knop met bijbehorend
  icoon zichtbaar en klikbaar (`onPressed != null`) is.
- **Niet lokaal gedraaid**: deze checkout heeft geen Flutter/Dart-SDK (`flutter`/`dart` niet op
  PATH) en geen Docker (de Dockerfile gebruikt normaliter het `ghcr.io/cirruslabs/flutter`-image
  voor build/test) — conform `docs/factory/development.md` is dat voor lokaal ontwikkelen ook
  niet vereist. `flutter analyze` / `flutter test` kon in deze omgeving dus niet uitgevoerd
  worden; de wijziging en de nieuwe test zijn wel zorgvuldig tegen de bestaande code/patterns
  gelezen (zelfde `launchUrl`-aanroep-stijl, zelfde `DataScreen`/`ApiClient`-opzet als overige
  screens). Aanbevolen om in CI (waar de Flutter-toolchain wel aanwezig is) `flutter test` op
  `dashboard-frontend/` te draaien ter bevestiging.

## Review (SF-869)

- [info] Diff is minimaal en scope-conform: alleen de `FilledButton.tonalIcon` "GitHub Actions"
  toegevoegd in `_SettingsScreenState.build` (na de Versie-Panel), plus een widget-test. Geen
  backend-wijzigingen, geen nieuwe dependency — `url_launcher`, `http`, `shared_preferences` en
  `flutter_test` stonden al in `pubspec.yaml`.
  De knop gebruikt exact hetzelfde `launchUrl(..., mode: LaunchMode.externalApplication)`-patroon
  als de bestaande PR-link (regel 252) en download-link (regel 711).
- [info] De knop rendert onvoorwaardelijk (geen afhankelijkheid van `version`-data), dus voldoet
  aan AC "zichtbaar zonder extra navigatie-diepte" en geen risico op regressie van de bestaande
  Versie/Nightly/Grote-letters/Logout/Restart-Stop-functionaliteit — die code is ongewijzigd.
  Widget-test mockt `/api/v1/settings` via `http.runWithClient` + `MockClient` en checkt tekst,
  icoon en `onPressed != null`; dit dekt de AC voldoende (test zelf niet lokaal uitgevoerd, geen
  Flutter-toolchain in reviewer-omgeving — CI-only verificatie, conform bestaande agent-tip).
  `docs/factory/ux/screens/settings.md` vermeldt deze knop nog niet, maar dat is expliciet
  gedelegeerd aan de latere `documentation`-subtaak (SF-872) — geen blocker in deze subtaak.
- Conclusie: akkoord, geen blockers.
