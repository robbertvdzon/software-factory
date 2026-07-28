# 0002 - Google-SSO (OIDC) authenticatie voor dashboard-backend

- Status: Accepted
- Datum: 2026-07-28

## Context

`dashboard-backend` is een dunne bridge-service (lokaal op poort `9090`) die de
agent-/story-data van de factory ontsluit voor de Flutter-dashboard-frontend, zonder
eigen tracker- of database-toegang. Deze service moet zelf gebruikers authenticeren
voordat de bridge-endpoints benaderd mogen worden.

`POST /api/v1/auth/google` (`AuthController.kt`) ontvangt een Google **ID-token** en
geeft dat door aan `AuthService.loginWithGoogle`. Die methode verifieert het token via
de injecteerbare `GoogleIdTokenVerifier`-seam (`GoogleIdTokenVerifier.kt`); de
productie-implementatie `NimbusGoogleIdTokenVerifier` controleert:

- de RS256-signature via Google's JWKS (`https://www.googleapis.com/oauth2/v3/certs`);
- de audience, die moet overeenkomen met `SF_GOOGLE_CLIENT_ID`;
- de issuer, die `accounts.google.com` of `https://accounts.google.com` moet zijn;
- de expiry van het token;
- en dat de `email_verified`-claim `true` is.

Na een geldig token wordt het e-mailadres uit het token gecheckt tegen de
`SF_ALLOWED_EMAILS`-allowlist (`AuthService.loginWithGoogle`); staat het e-mailadres
er niet op, dan wordt de login geweigerd. Bij een geslaagde login geeft
`AuthService` een eigen, HMAC-getekend sessietoken terug (HMAC-SHA256 met
`SF_DASHBOARD_REMEMBER_SECRET`), met het e-mailadres als identiteit. Dit sessietoken
wordt vervolgens door `AuthService.requireAuthorization` geaccepteerd op de
`Bearer`-header, zoals gebruikt door alle bridge-endpoints in
`BridgeApiController.kt`.

Dit Google-SSO-mechanisme vervangt de oudere username/password-login van
`dashboard-backend`, sinds SF-794/SF-795 (zie ook `docs/factory/technical-spec.md`,
regels 30-38).

## Decision

We authenticeren gebruikers van `dashboard-backend` via Google-SSO (OIDC) in plaats
van een eigen username/password-systeem. Google fungeert als identity-provider; de
toegang zelf wordt centraal beheerd via de `SF_ALLOWED_EMAILS`-allowlist. Dit
voorkomt dat de service zelf wachtwoorden moet opslaan en beheren.

## Consequences

- `dashboard-backend` is afhankelijk van Google als externe identity-provider voor
  authenticatie.
- Er moeten drie env-vars/secrets beheerd worden: `SF_ALLOWED_EMAILS` (de
  toegangsallowlist), `SF_GOOGLE_CLIENT_ID` (de verwachte audience van het
  ID-token) en `SF_DASHBOARD_REMEMBER_SECRET` (het HMAC-signing-secret voor
  sessietokens).
- De `GoogleIdTokenVerifier`-seam is injecteerbaar: tests kunnen een eigen
  RSA-keyset (`nimbus-jose-jwt`) gebruiken om zelf geldige test-ID-tokens te
  ondertekenen, wat netwerkloze tests van het login-pad mogelijk maakt. Dit is een
  positief gevolg voor testbaarheid.
