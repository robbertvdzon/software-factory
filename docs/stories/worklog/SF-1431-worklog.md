# SF-1431 - Worklog

Story-context bij eerste pickup:
ADR-0002 Google-SSO authenticatie schrijven

Maak `docs/adr/0002-google-sso-authenticatie.md` volgens `docs/adr/template.md` (analoog aan `docs/adr/0001-kotlin-spring-backend-flutter-frontend.md`). Status: Accepted, datum van vandaag. Context: beschrijf `POST /api/v1/auth/google` (AuthController.kt) -> AuthService.loginWithGoogle -> GoogleIdTokenVerifier-seam (NimbusGoogleIdTokenVerifier: RS256 via Google JWKS, audience SF_GOOGLE_CLIENT_ID, issuer accounts.google.com, expiry, email_verified) -> allowlist-check tegen SF_ALLOWED_EMAILS -> HMAC-sessietoken (SF_DASHBOARD_REMEMBER_SECRET) geaccepteerd door AuthService.requireAuthorization op de Bearer-header (BridgeApiController.kt); vermeld dat dit de oude username/password-login vervangt (sinds SF-794/SF-795). Decision: motiveer kort de keuze voor Google-SSO (geen eigen wachtwoordbeheer, centrale allowlist-toegang). Consequences: afhankelijkheid van Google als identity-provider, beheer van SF_ALLOWED_EMAILS/SF_GOOGLE_CLIENT_ID/SF_DASHBOARD_REMEMBER_SECRET, en de injecteerbare verifier-seam als voordeel voor netwerkloze tests. Wijzig geen ander bestand.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-1447 (dev-subtaak van deze story): `docs/adr/0002-google-sso-authenticatie.md`
  aangemaakt volgens `docs/adr/template.md` (Status/Datum-header, `## Context`,
  `## Decision`, `## Consequences`), analoog aan
  `docs/adr/0001-kotlin-spring-backend-flutter-frontend.md`. Status `Accepted`,
  datum 2026-07-28.
- Feiten in de Context-sectie geverifieerd tegen de broncode vóór het schrijven:
  `AuthController.kt` (`POST /api/v1/auth/google`), `AuthService.kt`
  (`loginWithGoogle`, allowlist-check, HMAC-sessietoken, `requireAuthorization`),
  `GoogleIdTokenVerifier.kt`/`NimbusGoogleIdTokenVerifier` (RS256 via Google-JWKS,
  audience/issuer/expiry/`email_verified`-checks), `DashboardConfig.kt` (env-vars
  `SF_GOOGLE_CLIENT_ID`, `SF_ALLOWED_EMAILS`, `SF_DASHBOARD_REMEMBER_SECRET`) en
  `BridgeApiController.kt` (gebruik van `requireAuthorization` op de
  `Bearer`-header). Komt overeen met `docs/factory/technical-spec.md` regels 30-38.
- Puur documentatie: geen code-, config- of gedragswijziging. Geen andere
  bestanden gewijzigd dan deze twee (ADR + worklog); `docs/factory/technical-spec.md`
  beschreef deze situatie al correct, dus geen aanpassing nodig.
- Geen unit-tests van toepassing (documentatie-only wijziging). `mvn verify`
  vanaf de repo-root uitgevoerd ter controle dat het vangnet ongewijzigd groen
  blijft: BUILD SUCCESS, alle modules, 0 failures / 0 errors.
