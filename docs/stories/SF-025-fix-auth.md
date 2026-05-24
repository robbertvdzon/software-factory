# SF-025 - Persistent Dashboard Login

## Story

Als gebruiker wil ik na een restart van de lokale Software Factory service niet
opnieuw hoeven inloggen op het dashboard, zolang ik niet expliciet uitlog.

## Stappenplan

[x]: create story document
[x]: add signed persistent login cookie
[x]: restore session from cookie after restart
[x]: clear cookie on logout
[x]: document dashboard auth settings
[x]: add unit coverage
[x]: run tests
[x]: rename story file to a descriptive filename

## Uitvoering

- Story-log aangemaakt omdat dashboard-login nu alleen op `HttpSession` leunt.
  Die sessie leeft in het Spring-proces en verdwijnt bij service-restart.
- `FactoryDashboardAuth` maakt nu bij succesvolle login een ondertekende
  HttpOnly remember-cookie met HMAC-SHA256 en vervaldatum.
- Dashboard-routes herstellen een nieuwe `HttpSession` uit die cookie als de
  service opnieuw gestart is.
- `/login` redirect direct door als de remember-cookie al geldig is.
- Logout en mislukte login wissen zowel de server-side sessie als de
  remember-cookie.
- De signing key is standaard afgeleid van dashboard user/password en kan met
  `SF_DASHBOARD_REMEMBER_SECRET` expliciet stabiel gezet worden.
- `SF_DASHBOARD_REMEMBER_DAYS` bepaalt de geldigheid; default is 30 dagen.
- Specs en login UX-docs bijgewerkt.
- Unit tests toegevoegd voor sessie-login, herstel uit cookie, tamper-detectie
  en cookie-clearing.
- `mvn -q -Dtest=FactoryDashboardAuthTest test` en `mvn -q test` draaien
  succesvol.
- Het story-bestand is hernoemd naar een echte korte omschrijving:
  `docs/stories/SF-025-fix-auth.md`.
