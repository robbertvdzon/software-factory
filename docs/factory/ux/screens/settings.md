# Settings

## Purpose

Show basic session information.

## Layout

- Page header: `Settings`.
- User card.
- Later: local config diagnostics.

## Data

- Logged-in username.
- Optional app version/build.
- Optional database mode summary without secrets.

## AI-uitvoering per agentrol (SF-2410)

`SettingsScreen` heeft een sectie "AI-uitvoering per agentrol" waarin een ingelogde gebruiker
per rol de standaarduitvoering of een projectoverschrijving kiest. Elke keuze bestaat uit de
Runtime-provider, het model en de mode en wordt per rol opgeslagen via
`POST /api/v1/settings/agent-execution`.

Boven de keuzen staan permanent, zonder uitklappen of andere aanvullende interactie, beide
gevolgen van een wijziging:

- "Wijzigingen gelden vanaf de eerstvolgende agentjob."
- "Reeds lopende agentjobs behouden hun huidige provider, model en mode."

Een instellingenwijziging herstart of wijzigt een reeds lopende agentjob dus niet.

## Audits per project (SF-350 vervangen door het audit-systeem)

`SettingsScreen` (Flutter dashboard-frontend) heeft een sectie "Audits per project":

- Master-switch "Audit-scheduler ingeschakeld": staat deze uit, dan draaien er geen
  automatische (geplande) audits meer — "Nu draaien" in het Audits-scherm blijft altijd werken.
- Per project een rij met starttijd (`HH:MM`) en aantal audits per nacht.
- Opslaan via `POST /api/v1/audits/settings`; na opslaan verschijnt een bevestiging/foutmelding
  (`Audit-instellingen opgeslagen.` / een validatiemelding bij een ongeldig aantal).
- Zie ook `.factory/nightly/README.md` en het Audits-scherm (navigatie-item "Audits",
  `AuditScreen`) voor het volledige audit-gedrag.

## Weergave — Grote letters (SF-846)

`SettingsScreen` (Flutter dashboard-frontend) heeft een `SwitchListTile` "Grote letters"
naast de audit-instellingen:

- Direct bij toggelen (geen aparte opslaanknop) wordt de voorkeur toegepast én lokaal
  opgeslagen via `shared_preferences` (`TextScalePreference`, `lib/text_scale_preference.dart`).
- De schaal is app-breed: `SoftwareFactoryDashboard` (`lib/main.dart`) past 'm toe via de
  `builder`-parameter van `MaterialApp` (`MediaQuery`/`TextScaler.linear`, vaste factor 1.2×
  wanneer aan), dus ook het login-scherm schaalt mee.
- Puur lokale, per-device UI-voorkeur; geen backend-opslag/synchronisatie tussen devices.

## GitHub Actions-link (SF-868)

Naast de "Versie"-sectie (na `Factory gestart: ...`) staat een knop **GitHub Actions**
(`FilledButton.tonalIcon`, icoon `open_in_new`) die de GitHub Actions-pagina van deze repo
opent:

- Statische URL `https://github.com/robbertvdzon/software-factory/actions`, niet
  configureerbaar en niet afkomstig uit `/api/v1/settings`.
- Opent extern via `launchUrl(..., mode: LaunchMode.externalApplication)` (`url_launcher`),
  niet in een in-app webview.

## Actions

- AI-uitvoering per agentrol globaal of per project opslaan.
- Audit-instellingen per project opslaan.
- Grote letters aan/uit (direct toegepast + lokaal bewaard).
- GitHub Actions-pagina openen (extern).

## States

- Session expired.

## Notes

Do not show tokens, database passwords or full secret values. Diagnostics must
use redacted config summaries.
