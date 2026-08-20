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
- de expiry van het token.

De `email_verified`-claim wordt door de verifier alleen uitgelezen en als veld in
`GoogleIdentity` doorgegeven; de weigering zelf gebeurt in `AuthService.loginWithGoogle`.
Die methode weigert de login als de claim niet `true` is, en checkt daarnaast het
e-mailadres uit het token tegen de `SF_ALLOWED_EMAILS`-allowlist; staat het e-mailadres
er niet op, dan wordt de login eveneens geweigerd. Bij een geslaagde login geeft
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
  authenticatie van menselijke gebruikers.
- Er moeten drie env-vars/secrets beheerd worden voor dit Google-SSO-loginpad:
  `SF_ALLOWED_EMAILS` (de toegangsallowlist), `SF_GOOGLE_CLIENT_ID` (de verwachte
  audience van het ID-token) en `SF_DASHBOARD_REMEMBER_SECRET` (het
  HMAC-signing-secret voor sessietokens). Deze drie beschrijven het loginpad voor
  mensen en dus niet het volledige authenticatie-oppervlak van de service.
- `dashboard-backend` kent daarnaast een apart machine-tot-machine-pad voor Product
  Factory onder `/api/integrations/v1`, met een eigen token en eigen secret; zie
  ADR-0003 (`docs/adr/0003-product-factory-integratietoken.md`). Dat pad staat los van
  de hier beschreven Google-SSO-login en wordt niet door `SF_ALLOWED_EMAILS` beperkt.
- `dashboard-backend` kent verder nog een derde pad: de websocketverbinding op `/bridge`
  waarover de factory-orchestrator zijn data levert, met een eigen gedeeld token op het
  hello-frame en een eigen secret; zie ADR-0004
  (`docs/adr/0004-bridge-websocket-token.md`). Ook dat pad staat los van de hier beschreven
  Google-SSO-login en wordt niet door `SF_ALLOWED_EMAILS` beperkt.
- De `GoogleIdTokenVerifier`-seam is injecteerbaar: tests kunnen een eigen
  RSA-keyset (`nimbus-jose-jwt`) gebruiken om zelf geldige test-ID-tokens te
  ondertekenen, wat netwerkloze tests van het login-pad mogelijk maakt. Dit is een
  positief gevolg voor testbaarheid.
