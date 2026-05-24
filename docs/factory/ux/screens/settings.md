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

## Actions

- Logout.

## States

- Session expired.

## Notes

Do not show tokens, database passwords or full secret values. Diagnostics must
use redacted config summaries.
