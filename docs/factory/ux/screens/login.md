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

Deze beschrijving geldt voor het ingebouwde factory-dashboard (softwarefactory
web, poort 8080), dat username/password + remember-cookie houdt. Het losse
**bridge-dashboard** (`dashboard-frontend`/`dashboard-backend`) logt sinds SF-794/SF-795
in met **Google-SSO**: het loginpaneel toont enkel een "Inloggen met Google"-knop
(geen username/password-velden). Na de Google-login stuurt de frontend het Google
ID-token naar `POST /api/v1/auth/google`; de backend verifieert het token, checkt het
e-mailadres tegen een allowlist (`SF_ALLOWED_EMAILS`) en geeft een HMAC-sessie-token
terug dat als `Bearer`-header wordt bewaard (`shared_preferences`). De sessie-identiteit
is het e-mailadres.
