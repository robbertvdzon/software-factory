# SF-868 - Link naar GitHub Actions-pipeline in dashboard-frontend

## Story

Link naar GitHub Actions-pipeline in dashboard-frontend

<!-- refined-by-factory -->

## Scope

Voeg in de Flutter `dashboard-frontend` een zichtbare, klikbare link/knop toe naar het GitHub Actions-overzicht van deze repo (`https://github.com/robbertvdzon/software-factory/actions`). De link opent extern (browser/nieuw tabblad), niet in-app.

Plaatsing:
- Primair op het **Settings-scherm** (`SettingsScreen` in `dashboard-frontend/lib/screens/overview_screens.dart`), als nieuwe sectie/knop naast de bestaande "Versie"-sectie (logisch verwant: build/versie-info hoort bij CI/CD-status).
- Gebruik het al aanwezige patroon `launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication)` (package `url_launcher`, al een dependency in `pubspec.yaml` en al gebruikt in `overview_screens.dart` en `story_detail_screen.dart`).

Geen wijzigingen aan backend/API nodig: de URL is statisch en hoeft niet uit `/api/v1/settings` te komen.

## Acceptance criteria

- Op het Settings-scherm is een duidelijk zichtbare knop/link "GitHub Actions" (of vergelijkbaar label) aanwezig.
- Klikken op de link opent `https://github.com/robbertvdzon/software-factory/actions` in de externe browser (`LaunchMode.externalApplication`), niet binnen de app-webview.
- De link is zichtbaar zonder extra navigatie-diepte: direct op het Settings-scherm (geen submenu nodig).
- Geen regressie op bestaande Settings-functionaliteit (nightly-instellingen, grote letters, logout, restart/stop-knoppen blijven werken).
- Geen wijzigingen nodig aan backend/API; de link is puur front-end/statisch.

## Aannames

- De URL is vast/statisch (`https://github.com/robbertvdzon/software-factory/actions`) en hoeft niet configureerbaar te zijn per project/omgeving.
- "Settings" is een voldoende logische globale plek (het issue noemt zelf "settings of de status-/bovenbalk" als beide acceptabel); een extra icoon in de top-/navigatiebalk is niet vereist bovenop de Settings-knop.
- Er is geen aparte backend-endpoint of configuratieveld nodig voor deze link; puur een front-end wijziging in `dashboard-frontend`.

## Eindsamenvatting

{"agent_tips_update":[]}
{"phase":"summarized"}
