# SF-794 - Google-SSO-login voor Flutter-dashboard (i.p.v. username/password)

## Story

Google-SSO-login voor Flutter-dashboard (i.p.v. username/password)

<!-- refined-by-factory -->

## Scope

Vervang de username/password-login van het bridge-dashboard door inloggen met Google (OIDC), met een vaste e-mail-allowlist. Alleen het bridge-dashboard (`dashboard-backend` + `dashboard-frontend`); personal-feed en andere apps volgen later apart.

**Backend (`dashboard-backend`):**
- Nieuw login-endpoint dat een Google **ID-token** ontvangt (i.p.v. `username`/`password`), bv. `POST /api/v1/auth/google` met body `{ "idToken": "..." }`, teruggevend dezelfde `LoginResponse` (`{ token, username }`).
- Verifieer het ID-token: geldige Google-signature, issuer `accounts.google.com`/`https://accounts.google.com`, audience == geconfigureerde OAuth-client-ID, niet verlopen, en `email_verified == true`.
- Controleer het `email` uit het token tegen een vaste allowlist uit config/env. Niet op de lijst → geweigerd (HTTP 401/403), ongeacht geldig Google-token.
- Bij toegestaan e-mailadres: geef een eigen sessie-token af via het **bestaande** mechanisme (HMAC-getekend token, `requireAuthorization`). De token-identiteit wordt het e-mailadres i.p.v. de vaste `dashboardUsername`; `requireAuthorization` accepteert tokens die voor een allowlisted e-mailadres zijn uitgegeven.
- De username/password-loginweg (`AuthService.login(username, password)` + `POST /api/v1/auth/login` + `LoginRequest`) wordt verwijderd/vervangen; `SF_DASHBOARD_USERNAME`/`SF_DASHBOARD_PASSWORD`-login vervalt. `rememberSecret`/HMAC-signing van het sessie-token blijft bestaan.
- Nieuwe configuratie met `SF_`-prefix (conventie): OAuth-client-ID (audience) en de e-mail-allowlist (comma-separated), geladen via `DashboardSecretsLoader`.
- De Google-ID-token-verificatie zit achter een seam (interface/injecteerbare verifier of configureerbare JWKS/keyset) zodat tests zelf een geldig test-ID-token kunnen signeren zónder netwerkcall naar Google.

**Frontend (`dashboard-frontend`, Flutter):**
- Loginscherm toont een **"Inloggen met Google"**-knop i.p.v. het username/password-formulier (`main.dart` `_loginView`).
- Na Google-login stuurt de app het Google ID-token naar het nieuwe backend-endpoint; het teruggekregen sessie-token wordt zoals nu bewaard (`ApiClient.token`, `shared_preferences`) en gebruikt in de `Authorization: Bearer`-header. `storedUsername` wordt het e-mailadres.
- Google Sign-In-dependency toevoegen (Flutter-web als primair doel; Android-config voor de APK optioneel/best-effort).

**Niet in scope:** aparte gebruikersdatabase of user-management-UI; "vraag toegang aan"/approval-flow; andere identity-providers dan Google; personal-feed en overige apps; het opzetten van de OAuth-client in Google Cloud Console (externe handmatige stap, alleen de client-ID wordt via config verwacht).

## Acceptance criteria

1. De Flutter-loginweergave toont een "Inloggen met Google"-knop; geen username/password-velden meer.
2. Na succesvolle Google-login stuurt de frontend het Google ID-token naar de backend en bewaart/gebruikt het teruggekregen sessie-token identiek aan de huidige flow (persistente sessie, Bearer-header).
3. De backend weigert (HTTP 401/403) bij: ongeldige/verlopen/tampered ID-token, verkeerde audience/issuer, `email_verified != true`, of een e-mailadres dat niet op de allowlist staat — ook bij een verder geldig Google-token.
4. Bij een geldig ID-token met een allowlisted, geverifieerd e-mailadres geeft de backend een sessie-token af dat door het bestaande `requireAuthorization` wordt geaccepteerd (round-trip).
5. De oude username/password-login (endpoint + `AuthService.login`) bestaat niet meer.
6. `AuthServiceTest` en overige tests die op de oude auth leunden (o.a. `BridgeApiControllerTest`) zijn meegegroeid; nieuwe tests dekken via een test-dubbel (zelf gesigneerd test-ID-token): happy-path (allowlisted), afwijzing (niet op allowlist), en ongeldig/verlopen/tampered token.
7. `mvn verify` (en `mvn test`) is groen **zonder** netwerktoegang tot Google (geen live Google-call in tests).
8. Nieuwe configuratie gebruikt de `SF_`-prefix en wordt via de bestaande secrets-loader ingelezen; ontbrekende verplichte config faalt met een duidelijke foutmelding. Secrets worden niet (onged­igeerd) gelogd.

## Aannames

- Login-endpoint wordt `POST /api/v1/auth/google` met body `{ "idToken": "..." }`; response blijft `LoginResponse { token, username }` met `username` = e-mailadres. (Exacte pad-/veldnaam mag de developer bijstellen zolang gedrag gelijk blijft.)
- Config-namen (developer mag exacte namen kiezen, `SF_`-prefix verplicht): bv. `SF_GOOGLE_CLIENT_ID` (audience) en `SF_ALLOWED_EMAILS` (comma-separated allowlist, standaard bevat `robbert@vdzon.com`).
- Het bestaande sessie-/HMAC-tokenmechanisme (`rememberSecret`, `token()`, `requireAuthorization`) blijft ongewijzigd behalve dat de identiteit een allowlisted e-mailadres is i.p.v. de vaste `dashboardUsername`.
- De frontend gebruikt een standaard Google Sign-In-oplossing voor Flutter-web; de web-OAuth-client-ID komt via een build-time/config-waarde. Android-APK-ondersteuning is best-effort en niet vereist voor groen resultaat.
- Het aanmaken van de OAuth-client in Google Cloud Console is een handmatige, externe stap buiten deze codewijziging; de code werkt tegen een configureerbare client-ID.
- Overige backend-referenties naar `dashboardUsername` (bv. `BridgeApiController` `settings.get`) blijven functioneel werken; waar identiteit relevant is wordt dat het e-mailadres.
- De Flutter-frontend valt buiten de Maven-`verify`; de harde testgroen-eis geldt voor `dashboard-backend` (mvn).

## Eindsamenvatting

Ik heb alle context: `.task.md`, het worklog (met developer-, reviewer- en tester-notities) en de diffstat. Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-794: Google-SSO-login voor het bridge-dashboard

**Doel:** de username/password-login van het bridge-dashboard vervangen door inloggen met Google (OIDC), met een vaste e-mail-allowlist. Scope beperkt tot `dashboard-backend` + `dashboard-frontend`.

### Wat is gebouwd

**Backend (`dashboard-backend`, Kotlin):**
- Nieuw endpoint `POST /api/v1/auth/google` dat een Google **ID-token** ontvangt (`{ idToken }`) en dezelfde `LoginResponse { token, username }` teruggeeft, met het e-mailadres als `username`.
- Nieuwe verificatie-seam `GoogleIdTokenVerifier` (interface + `NimbusGoogleIdTokenVerifier` op basis van `nimbus-jose-jwt`): controleert RS256-signature via Google's JWKS, audience (`SF_GOOGLE_CLIENT_ID`), issuer, expiry en `email_verified`. De JWKS-bron is injecteerbaar zodat tests netwerkloos draaien.
- `AuthService.loginWithGoogle` checkt de allowlist: geldig maar niet-verified token → 401, geldig maar niet op de allowlist → 403. Bij toegang wordt het **bestaande** HMAC-sessie-token uitgegeven, nu met het e-mailadres als identiteit. `requireAuthorization` accepteert alleen tokens voor allowlisted e-mailadressen.
- De oude username/password-weg (endpoint, `LoginRequest`, `AuthService.login`, `SF_DASHBOARD_USERNAME/PASSWORD`) is volledig verwijderd.
- Nieuwe `SF_`-config via de bestaande secrets-loader: `SF_GOOGLE_CLIENT_ID` (verplicht), `SF_ALLOWED_EMAILS` (comma-separated, lowercase-genormaliseerd, default `robbert@vdzon.com`) en `SF_DASHBOARD_REMEMBER_SECRET` (verplicht). Ontbrekende verplichte config faalt fail-fast met een duidelijke, key-noemende foutmelding.

**Frontend (`dashboard-frontend`, Flutter):**
- Loginscherm toont enkel nog een **"Inloggen met Google"**-knop; de username/password-velden zijn weg (`google_sign_in`-dependency toegevoegd).
- Na Google-login stuurt de app het ID-token naar het nieuwe endpoint; het sessie-token wordt identiek bewaard/hergebruikt (`shared_preferences`, `Bearer`-header), `storedUsername` = e-mailadres.
- Web-client-ID komt via build-time `--dart-define=GOOGLE_CLIENT_ID` (Dockerfile + docker-compose, gevoed door `SF_GOOGLE_CLIENT_ID`).

### Belangrijkste keuzes
- Verificatie achter een injecteerbare seam → tests signeren zelf ID-tokens met een eigen RSA-keyset/in-memory JWKS, zonder live Google-call.
- Het bestaande HMAC-sessie-tokenmechanisme blijft ongewijzigd; alleen de identiteit is nu een e-mailadres i.p.v. de vaste `dashboardUsername`.
- Onderscheid 401 (ongeldig token) vs. 403 (niet op allowlist).

### Getest
- `mvn -pl dashboard-backend -am test` → **BUILD SUCCESS**, 37 backend-tests groen (0 failures/errors), zonder netwerktoegang tot Google.
- Testdekking: happy-path (allowlisted), afwijzingen (verkeerde audience/issuer, verlopen, untrusted key, garbage, tampered, `email_verified=false` → 401, niet-allowlisted → 403), sessie-token round-trip, en meegegroeide `BridgeApiControllerTest`/`BridgeHubTest`/`DashboardSecretsLoaderTest`.
- Alle 8 acceptatiecriteria gedekt; door reviewer en tester akkoord bevonden.

### Bewust niet gedaan / aandachtspunten voor deploy
- **Frontend niet gebouwd/e2e-getest** in deze omgeving (geen Flutter/Docker/preview); statisch geverifieerd. CI bouwt de frontend. Android-APK is best-effort, niet vereist.
- **OAuth-client in Google Cloud Console** aanmaken is een handmatige, externe stap; de code werkt tegen een configureerbare client-ID.
- **`deploy/base/sealed-secret-dashboard.yaml` nog niet her-sealed** (kubeseal-cert niet beschikbaar hier); bronbestanden (`seal-secrets.sh`, `secrets-cluster.env.example`) zijn wél bijgewerkt. Ops-stap vóór deploy.
- **Buiten scope:** aparte gebruikersdatabase/user-management-UI, approval-flow, andere identity-providers, personal-feed en overige apps.

---
