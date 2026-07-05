# SF-794 - Worklog

Story-context bij eerste pickup:
Google-SSO-login voor het bridge-dashboard (backend + frontend). Vervang de
username/password-login door inloggen met Google (OIDC) met een vaste
e-mail-allowlist. Alleen `dashboard-backend` + `dashboard-frontend`.

## SF-795 — Google-SSO-login voor bridge-dashboard (developing)

Vervang de username/password-login van het bridge-dashboard door Google-OIDC:
de backend ontvangt een Google ID-token, verifieert het (signature/audience/
issuer/expiry/email_verified), checkt het e-mailadres tegen een allowlist en geeft
het bestaande HMAC-sessie-token terug met het e-mailadres als identiteit. De
frontend toont een "Inloggen met Google"-knop en stuurt het ID-token naar de backend.

Stappenplan:
[x]: read issue and target docs
[x]: backend — GoogleIdTokenVerifier-seam (nimbus-jose-jwt) toevoegen
[x]: backend — nieuw endpoint POST /api/v1/auth/google + AuthService.loginWithGoogle
[x]: backend — allowlist + email-identiteit in requireAuthorization
[x]: backend — SF_-config (SF_GOOGLE_CLIENT_ID, SF_ALLOWED_EMAILS, SF_DASHBOARD_REMEMBER_SECRET) via DashboardSecretsLoader
[x]: backend — oude username/password-login (endpoint, LoginRequest, config) verwijderen
[x]: backend — tests (AuthServiceTest met eigen RSA-keyset, BridgeApiControllerTest, DashboardSecretsLoaderTest)
[x]: backend — `mvn test` groen (37 tests) zonder netwerkcall naar Google
[x]: frontend — "Inloggen met Google"-knop + google_sign_in-dependency
[x]: frontend — api_client.loginWithGoogle + build-time GOOGLE_CLIENT_ID
[x]: docs — secrets-local.md, technical-spec.md, ux/screens/login.md bijwerken

### Wat is er gedaan en waarom

**Backend (`dashboard-backend`):**
- Nieuwe seam `GoogleIdTokenVerifier` (`fun interface`) + `NimbusGoogleIdTokenVerifier`
  in `api/GoogleIdTokenVerifier.kt`. Productie verifieert de RS256-signature via Google's
  JWKS (`JWKSourceBuilder`), audience == `SF_GOOGLE_CLIENT_ID`, issuer
  `accounts.google.com`/`https://accounts.google.com`, expiry en haalt `email` +
  `email_verified` op. De `JWKSource` is een constructor-parameter zodat tests een eigen
  in-memory keyset injecteren — geen live Google-call. Dependency `nimbus-jose-jwt` (9.48)
  toegevoegd aan `pom.xml` (BOM beheert de versie niet, dus expliciet gepind).
- `AuthService.loginWithGoogle(idToken)` verifieert via de seam, eist `email_verified`,
  checkt de allowlist (401 bij ongeldig token, 403 bij niet-allowlisted) en geeft het
  bestaande `LoginResponse{token, username=email}` terug via het ongewijzigde HMAC-`token()`.
  `requireAuthorization` accepteert nu tokens waarvan de identiteit een allowlisted
  e-mailadres is en **geeft dat e-mailadres terug** (identiteit).
- Nieuw endpoint `POST /api/v1/auth/google` (`AuthController`), body `GoogleLoginRequest{idToken}`.
  Oude `POST /api/v1/auth/login`, `LoginRequest` en `AuthService.login(username,password)`
  verwijderd.
- `DashboardSecrets` afgeslankt: `rememberSecret` (verplicht, losgekoppeld van username/
  password), `googleClientId` (verplicht) en `allowedEmails` (comma-separated, genormaliseerd
  naar lowercase, default `robbert@vdzon.com`); `dashboardUsername`/`dashboardPassword`
  verwijderd. Ontbrekende verplichte config faalt met een duidelijke `error()`.
- `BridgeApiController` gebruikt voor `settings.get` nu het geauthenticeerde e-mailadres
  i.p.v. `secrets.dashboardUsername`; de ongebruikte `secrets`-constructorparam is verwijderd.
- Tests: `AuthServiceTest` gebruikt een eigen RSA-keypair + test-JWKS (`TestGoogleTokens`)
  om zelf ID-tokens te ondertekenen — dekt happy-path (allowlisted), niet-op-allowlist (403),
  onbevestigd e-mail, verkeerde audience/issuer, verlopen en met onvertrouwde sleutel
  getekend token, plus de sessie-token-round-trip en een niet-allowlisted identiteit.
  `DashboardSecretsLoaderTest` en `BridgeApiControllerTest`/`BridgeHubTest` meegegroeid met
  de nieuwe config-vorm. `mvn test` = 37 tests groen, zonder netwerk naar Google.

**Frontend (`dashboard-frontend`, Flutter):**
- `google_sign_in: ^6.2.1` toegevoegd; `main.dart` `_loginView` toont nu enkel een
  "Inloggen met Google"-knop (username/password-velden weg). `_loginWithGoogle` haalt via
  `GoogleSignIn` het ID-token op en roept `api.loginWithGoogle` aan.
- `api_client.dart`: `login` vervangen door `loginWithGoogle(idToken)` → `POST /api/v1/auth/google`;
  sessie-token identiek bewaard/hergebruikt (`shared_preferences`, `Bearer`-header),
  `storedUsername` = e-mailadres, 401/403 → geen toegang, 401-logout behouden.
- Web-client-ID via build-time `--dart-define=GOOGLE_CLIENT_ID` (Dockerfile + docker-compose,
  gevoed door `SF_GOOGLE_CLIENT_ID`). Flutter kan in deze runomgeving niet gedraaid worden;
  de frontend valt buiten `mvn verify` en wordt door CI gebouwd. Android-APK is best-effort.

### Aangepaste specs
- `docs/factory/secrets-local.md`: dashboard-login-sectie herschreven naar Google-SSO
  (`SF_GOOGLE_CLIENT_ID`, `SF_ALLOWED_EMAILS`, verplichte `SF_DASHBOARD_REMEMBER_SECRET`).
- `docs/factory/technical-spec.md`: dashboard-backend-auth beschrijving toegevoegd.
- `docs/factory/ux/screens/login.md`: notitie over de Google-SSO-variant van het bridge-dashboard.

## Review (SF-795, reviewer)

Volledige story-diff (`main...HEAD`) beoordeeld. Akkoord.

- **Correctheid t.o.v. story:** alle 8 acceptatiecriteria gedekt. Verificatie
  (signature/audience/issuer/expiry/`email_verified`) zit correct achter de
  `GoogleIdTokenVerifier`-seam; allowlist-check in `AuthService`; sessie-token blijft het
  bestaande HMAC-mechanisme met het e-mailadres als identiteit. Oude
  username/password-weg (endpoint, `LoginRequest`, `AuthService.login`,
  `SF_DASHBOARD_USERNAME/PASSWORD`) volledig verwijderd — geen leftover-referenties.
- **Config/secrets:** `SF_GOOGLE_CLIENT_ID` + `SF_DASHBOARD_REMEMBER_SECRET` verplicht met
  duidelijke foutmelding; `rememberSecret` losgekoppeld van de oude password-fallback;
  allowlist genormaliseerd (lowercase/trim, default `robbert@vdzon.com`). Geen secrets in
  logs. `requireAuthorization` blijft constant-time HMAC-vergelijk.
- **Tests:** netwerkloos test-dubbel (eigen RSA-keyset + in-memory JWKS) dekt happy-path,
  niet-allowlisted (403), onbevestigd e-mail, verkeerde audience/issuer, verlopen,
  untrusted key, garbage, tampered signature en round-trip. `DashboardSecretsLoaderTest`
  en bridge-tests meegegroeid.
- **Specs consistent:** `secrets-local.md`, `technical-spec.md`, `ux/screens/login.md`
  bijgewerkt en kloppend met de diff.
- **[info]** `google_sign_in ^6.2.1` op Flutter-web: `signIn().authentication.idToken` kan
  op web-platform leeg zijn afhankelijk van pakketversie/flow; de code vangt `idToken==null`
  netjes af. Buiten `mvn verify`, best-effort per story, CI bouwt de frontend — geen blocker.
- **[info]** `deploy/base/sealed-secret-dashboard.yaml` nog niet her-sealed (kubeseal-cert
  niet beschikbaar in deze omgeving); bronbestanden (`seal-secrets.sh`,
  `secrets-cluster.env.example`) wél bijgewerkt. Ops-stap vóór deploy — buiten codewijziging.

## Test (SF-796, tester)

Story-brede verificatie op branch `ai/SF-794`. **Resultaat: geslaagd.**

- **Build/tests:** `mvn -pl dashboard-backend -am test` (reactor, bouwt `factory-common`
  mee) → BUILD SUCCESS. dashboard-backend 37 tests, Failures 0, Errors 0; factory-common
  39 en softwarefactory-contracttests groen. Geen netwerkcall naar Google (AC7): de
  `AuthServiceTest` signeert zelf ID-tokens via een eigen RSA-keyset + in-memory JWKS
  (`TestGoogleTokens`). Omgeving heeft geen Docker en geen preview (`SF_PREVIEW_URL` leeg),
  dus geen browser-/e2e-test uitgevoerd; dashboard-backend heeft geen Docker-e2e-module.
- **AC1/AC2 (frontend):** `main.dart` `_loginView` toont enkel een "Inloggen met Google"-knop
  (username/password-velden weg); `api_client.loginWithGoogle` post het ID-token naar
  `POST /api/v1/auth/google` en bewaart/hergebruikt het sessie-token identiek
  (`shared_preferences`, `Bearer`-header, `storedUsername`=e-mail). Flutter niet
  bouwbaar in deze omgeving (buiten `mvn verify`, best-effort per story) — statisch geverifieerd.
- **AC3 (afwijzingen):** gedekt door `AuthServiceTest` — verkeerde audience/issuer, verlopen,
  untrusted key, garbage/geen-JWT, `email_verified=false` (401) en niet-allowlisted (403).
- **AC4 (round-trip):** allowlisted geverifieerd e-mail → sessie-token wordt door
  `requireAuthorization` geaccepteerd en geeft de e-mailidentiteit terug; niet-allowlisted
  identiteit wordt zelfs met geldige HMAC geweigerd.
- **AC5:** geen leftover-referenties naar `auth/login`, `LoginRequest`,
  `AuthService.login`, `dashboardUsername/Password` of `SF_DASHBOARD_USERNAME/PASSWORD`
  (grep over backend + frontend).
- **AC6:** `AuthServiceTest`, `BridgeApiControllerTest`, `BridgeHubTest`,
  `DashboardSecretsLoaderTest` meegegroeid met de nieuwe auth/config-vorm.
- **AC8:** `SF_`-config via `DashboardSecretsLoader`; ontbrekende verplichte
  `SF_GOOGLE_CLIENT_ID`/`SF_DASHBOARD_REMEMBER_SECRET` faalt fail-fast met duidelijke,
  key-noemende foutmelding (getest). `BridgeApiController.settings` gebruikt het
  geauthenticeerde e-mailadres als identiteit. Geen secrets in output.
