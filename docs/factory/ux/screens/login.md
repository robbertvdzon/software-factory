# Login

## Purpose

Protect the local dashboard from casual access and make it clear which factory
instance the user is entering.

## Layout

- Centered login panel on a blank light background.
- Product mark at the top in a pale purple band.
- Title `Software Factory`.
- Subtitle `Login op het dashboard`.
- Username input.
- Password input with show/hide icon.
- Primary `Inloggen` button.

## Data

- Username.
- Password.
- Optional redirect URL after login.

## Actions

- Submit credentials.
- Toggle password visibility.

## States

- Invalid credentials: inline error below fields.
- Loading: disable button and show spinner/label.
- Logged in: redirect to `/dashboard` and store a signed HttpOnly
  remember-cookie so service restarts do not force a new login.
- Logged out: clear the remember-cookie.

## Notes

Avoid exposing secrets or runtime config on this page.
