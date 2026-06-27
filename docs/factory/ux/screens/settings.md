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

## Actions

- Logout.
- Nightly-settings opslaan.

## States

- Session expired.

## Notes

Do not show tokens, database passwords or full secret values. Diagnostics must
use redacted config summaries.
