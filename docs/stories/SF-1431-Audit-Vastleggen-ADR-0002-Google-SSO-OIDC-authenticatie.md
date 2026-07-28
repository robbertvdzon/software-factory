# SF-1431 - [Audit] Vastleggen ADR-0002: Google-SSO (OIDC) authenticatie voor dashboard-backend

## Story

[Audit] Vastleggen ADR-0002: Google-SSO (OIDC) authenticatie voor dashboard-backend

<!-- refined-by-factory -->

## Samenvatting
We leggen met een ADR (architecture decision record) vast waarom `dashboard-backend` inlogt via Google-SSO in plaats van een eigen gebruikersnaam/wachtwoord-systeem. Dit is puur documentatie: er verandert niets aan de werking van de applicatie, we schrijven alleen op wat al werkt en waarom dat destijds zo gekozen is.

## Scope
- Nieuw bestand: `docs/adr/0002-google-sso-authenticatie.md`, opgezet volgens het bestaande sjabloon `docs/adr/template.md` (secties Status, Context, Decision, Consequences — analoog aan `docs/adr/0001-kotlin-spring-backend-flutter-frontend.md`).
- Context-sectie beschrijft de huidige, reeds geïmplementeerde situatie in `dashboard-backend`:
  - `POST /api/v1/auth/google` (`AuthController.kt`) verifieert een Google ID-token via de `GoogleIdTokenVerifier`-seam (`GoogleIdTokenVerifier.kt`, productie-implementatie `NimbusGoogleIdTokenVerifier`): RS256-signature via Google's JWKS, audience `SF_GOOGLE_CLIENT_ID`, issuer `accounts.google.com`/`https://accounts.google.com`, expiry, en `email_verified`.
  - Het geverifieerde e-mailadres wordt gecheckt tegen de `SF_ALLOWED_EMAILS`-allowlist (`AuthService.loginWithGoogle`).
  - Bij toegang wordt een HMAC-getekend sessietoken uitgegeven (HMAC-SHA256 met `SF_DASHBOARD_REMEMBER_SECRET`), met het e-mailadres als identiteit; `AuthService.requireAuthorization` accepteert dit token op de `Bearer`-header (gebruikt in `BridgeApiController.kt`).
  - Dit vervangt de oude username/password-login, sinds SF-794/SF-795 (zie ook `docs/factory/technical-spec.md`, regels 30-38).
- Decision-sectie: motivatie waarom Google-SSO gekozen is boven de oude username/password-aanpak — geen eigen wachtwoordopslag/-beheer nodig, centraal beheerde toegang via de allowlist.
- Consequences-sectie: afhankelijkheid van Google als identity-provider; noodzaak om `SF_ALLOWED_EMAILS`, `SF_GOOGLE_CLIENT_ID` en `SF_DASHBOARD_REMEMBER_SECRET` te beheren; de injecteerbare `GoogleIdTokenVerifier`-seam maakt netwerkloze tests mogelijk (eigen RSA-keyset, `nimbus-jose-jwt`), wat een positief gevolg is voor testbaarheid.
- Status: `Accepted`, met de datum van deze wijziging.
- Geen andere bestanden wijzigen: geen code-, config- of gedragswijziging, geen wijziging aan `docs/factory/technical-spec.md` of andere documentatie.

## Acceptance criteria
- `docs/adr/0002-google-sso-authenticatie.md` bestaat en volgt exact de structuur van `docs/adr/template.md` (Status/Datum-header, `## Context`, `## Decision`, `## Consequences`).
- Status is `Accepted` met een datum.
- Context beschrijft: `POST /api/v1/auth/google`, de `GoogleIdTokenVerifier`-seam met RS256/Google-JWKS-verificatie, de gecontroleerde claims (audience `SF_GOOGLE_CLIENT_ID`, issuer `accounts.google.com`, expiry, `email_verified`), de `SF_ALLOWED_EMAILS`-allowlist-check, het HMAC-getekende sessietoken (`SF_DASHBOARD_REMEMBER_SECRET`) en `requireAuthorization` op de `Bearer`-header, en dat dit de oude username/password-login vervangt.
- Decision beschrijft kort waarom voor Google-SSO gekozen is boven de oude aanpak.
- Consequences beschrijven de afhankelijkheid van Google, het beheer van de drie genoemde env-vars/secrets, en de injecteerbare verifier-seam voor netwerkloze tests.
- Geen enkel ander bestand in de repo is gewijzigd; geen code- of gedragswijziging.

## Aannames
- De ADR is uitsluitend beschrijvend (retroactieve vastlegging van een al bestaande, geïmplementeerde keuze); er wordt niets aan gedrag of configuratie aangepast.
- Het ADR-nummer is `0002` (volgend op het enige bestaande ADR, `0001-kotlin-spring-backend-flutter-frontend.md`).
- De inhoudelijke feiten in de story (endpoint, verifier, allowlist, sessietoken, env-var-namen) zijn geverifieerd tegen de huidige broncode (`AuthController.kt`, `AuthService.kt`, `GoogleIdTokenVerifier.kt`, `DashboardConfig.kt`, `BridgeApiController.kt`) en tegen `docs/factory/technical-spec.md` (regels 30-38), en komen overeen.

## Eindsamenvatting

Ik heb voldoende context: het ADR-document, het worklog en de dev/tester-fasen. Ik schrijf nu de eindsamenvatting.

Alle informatie is verzameld — geen open vragen, alle acceptatiecriteria zijn gehaald en getest.

## Eindsamenvatting SF-1431: ADR-0002 Google-SSO authenticatie

**Gebouwd**
Een nieuw architecture decision record `docs/adr/0002-google-sso-authenticatie.md`, opgesteld volgens het bestaande sjabloon (`docs/adr/template.md`), analoog aan ADR-0001. Status `Accepted`, datum 2026-07-28.

De ADR legt vast waarom en hoe `dashboard-backend` inlogt via Google-SSO (OIDC) in plaats van een eigen gebruikersnaam/wachtwoord-systeem:
- **Context**: het login-pad `POST /api/v1/auth/google` (`AuthController.kt`) → `AuthService.loginWithGoogle`, dat het ID-token verifieert via de injecteerbare `GoogleIdTokenVerifier`-seam (productie: `NimbusGoogleIdTokenVerifier` — RS256-signature via Google's JWKS, audience `SF_GOOGLE_CLIENT_ID`, issuer `accounts.google.com`, expiry, `email_verified`); daarna een allowlist-check tegen `SF_ALLOWED_EMAILS`; bij succes een HMAC-getekend sessietoken (`SF_DASHBOARD_REMEMBER_SECRET`) dat door `requireAuthorization` op de `Bearer`-header wordt geaccepteerd (gebruikt in alle `BridgeApiController.kt`-endpoints). Ook vastgelegd dat dit de oude username/password-login vervangt (sinds SF-794/SF-795).
- **Decision**: Google als identity-provider, centraal beheerde toegang via de allowlist, geen eigen wachtwoordopslag nodig.
- **Consequences**: afhankelijkheid van Google, beheer van drie env-vars/secrets (`SF_ALLOWED_EMAILS`, `SF_GOOGLE_CLIENT_ID`, `SF_DASHBOARD_REMEMBER_SECRET`), en de injecteerbare verifier-seam als voordeel voor netwerkloze tests.

**Keuzes**
Puur documentatief: geen code-, config- of gedragswijziging. Alle feitelijke claims zijn vóór het schrijven expliciet tegen de broncode geverifieerd (`AuthController.kt`, `AuthService.kt`, `GoogleIdTokenVerifier.kt`/`NimbusGoogleIdTokenVerifier`, `DashboardConfig.kt`, `BridgeApiController.kt`) en komen overeen met `docs/factory/technical-spec.md` (regels 30-38) — geen aanpassing daar nodig.

**Getest**
Geen unit-tests van toepassing (documentatie-only). De tester heeft onafhankelijk elke feitelijke claim in de ADR nogmaals losstaand tegen de broncode geverifieerd (zelfde bestanden) en de ADR-structuur tegen het sjabloon gecontroleerd — geen afwijkingen. `mvn verify` is uitgevoerd als vangnetcontrole: BUILD SUCCESS, 0 failures/errors. `git diff --stat` bevestigt dat alleen de ADR en het worklog zijn gewijzigd.

**Bewust niet gedaan**
Geen wijzigingen aan `docs/factory/technical-spec.md` of andere bestanden — de story vroeg expliciet om alleen het nieuwe ADR-bestand toe te voegen.
