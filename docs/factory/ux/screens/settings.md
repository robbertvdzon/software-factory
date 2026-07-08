# Settings

## Purpose

Show basic session information and logout.

## Layout

- Page header: `Settings`.
- User card.
- Logout row/button.
- Later: local config diagnostics.

## Data

- Logged-in username.
- Optional app version/build.
- Optional database mode summary without secrets.

## Nightly scheduler

Schrijfbaar formulier voor de nachtelijke scheduler (SF-351):

- Master-switch (checkbox) om de scheduler aan/uit te zetten.
- Start-tijd en summary-tijd als `HH:MM` in lokale NL-tijd (Europe/Amsterdam).
- Opslaan via `POST /settings/nightly`; de waarden worden persistent bewaard
  (`nightly_settings`) en na herladen getoond. Een korte bevestiging/foutmelding
  verschijnt na opslaan (`?nightly=saved` / `?nightly=invalid`).

## Weergave — Grote letters (SF-846)

`SettingsScreen` (Flutter dashboard-frontend) heeft een `SwitchListTile` "Grote letters"
naast de Nightly-instellingen:

- Direct bij toggelen (geen aparte opslaanknop) wordt de voorkeur toegepast én lokaal
  opgeslagen via `shared_preferences` (`TextScalePreference`, `lib/text_scale_preference.dart`).
- De schaal is app-breed: `SoftwareFactoryDashboard` (`lib/main.dart`) past 'm toe via de
  `builder`-parameter van `MaterialApp` (`MediaQuery`/`TextScaler.linear`, vaste factor 1.2×
  wanneer aan), dus ook het login-scherm schaalt mee.
- Puur lokale, per-device UI-voorkeur; geen backend-opslag/synchronisatie tussen devices.

## Actions

- Logout.
- Nightly-settings opslaan.
- Grote letters aan/uit (direct toegepast + lokaal bewaard).

## States

- Session expired.

## Notes

Do not show tokens, database passwords or full secret values. Diagnostics must
use redacted config summaries.
