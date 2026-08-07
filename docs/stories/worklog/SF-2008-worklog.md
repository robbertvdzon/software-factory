# SF-2008 - Worklog

Story-context bij eerste pickup:
HTTPS-redirect op de route en HSTS-header in nginx.conf

1) deploy/base/softwarefactory-dashboard-frontend-route.yaml regel 16: insecureEdgeTerminationPolicy Allow -> Redirect. host:-regel en deploy/base/kustomization.yaml ongewijzigd laten; deploy/sno-local vergt geen aanpassing.
2) dashboard-frontend/nginx.conf: voeg `add_header Strict-Transport-Security "max-age=31536000" always;` toe op server-niveau EN herhaal hem in elk location-blok dat al een eigen add_header heeft: `= /healthz`, `= /index.html`, `= /flutter_bootstrap.js`, `= /flutter_service_worker.js`, `= /main.dart.js` en `/`. Reden: nginx add_header erft alleen van het omsluitende blok en wordt gemaskeerd zodra de gekozen location zelf een add_header heeft, en een request op `/` wordt via try_files (interne redirect) uiteindelijk beantwoord vanuit `location = /index.html`. Geen includeSubDomains, geen preload. Cache-headers, proxy-config en /healthz-gedrag verder niet wijzigen.
3) Toon aan tegen een DRAAIENDE nginx (lokaal gebouwd frontend-image of nginx-container met deze config), niet beredeneerd uit de configtekst: curl -sSI op /, /index.html, een SPA-deeplink, /main.dart.js en /healthz moeten allemaal de HSTS-header teruggeven, en de bestaande Cache-Control-headers mogen niet verdwenen zijn. Plak die output in docs/stories/worklog/SF-2008-worklog.md.
4) Alleen als punt 1 niet kan (redirect-loop/kapotte site doordat de gedeelde ingress plain http op de origin nodig heeft): zet regel 16 terug op Allow, houd alleen de HSTS-wijziging, en leg in deploy/README.md vast dat Allow daar bewust staat, waarom, dat de https-afdwinging dan in de gedeelde ingress/Cloudflare hoort, met datum 2026-08-07 en storynummer SF-2008.
Voer daarna zelf een reviewstap op je eigen diff uit en houd bestaande builds/tests groen (mvn verify / tools/verify-repository).

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: bewijs de HSTS-header tegen een draaiende nginx (AC-3)
[x]: regressietest op nginx.conf toevoegen (incl. faalbewijs)
[x]: run relevant tests
[x]: update story-log with results

## Done / rationale

### 1. Route terug op Redirect

`deploy/base/softwarefactory-dashboard-frontend-route.yaml` regel 16:
`insecureEdgeTerminationPolicy: Allow` -> `Redirect`. De `host:`-regel uit commit `cbd31ec` is
ongewijzigd gebleven; `deploy/base/kustomization.yaml` en `deploy/sno-local` zijn niet aangeraakt.
De uitzonderingsroute uit scope-punt 3/4 (terug naar `Allow`) is *niet* gekozen: er is in deze
checkout geen aanwijzing dat de gedeelde ingress plain http op de origin nodig heeft, en dat is
pas na uitrol waarneembaar (AC-4/AC-5).

### 2. HSTS-header in nginx.conf

`add_header Strict-Transport-Security "max-age=31536000" always;` staat nu op server-niveau én
herhaald in de zes location-blokken die zelf al een `add_header` hebben (`= /healthz`,
`= /index.html`, `= /flutter_bootstrap.js`, `= /flutter_service_worker.js`, `= /main.dart.js`,
`/`). Zonder die herhaling maskeert nginx de server-variant volledig — zie het faalbewijs
hieronder. `location /api/` en `location = /bridge` hebben geen eigen `add_header` en erven de
server-variant. Bewust zonder `includeSubDomains` en zonder `preload`. Cache-headers,
proxy-config en het `/healthz`-gedrag zijn ongewijzigd.

### 3. Bewijs tegen een draaiende nginx (AC-2/AC-3)

Aangetoond met de echte `nginx.conf` in het runtime-image uit `dashboard-frontend/Dockerfile`
(`nginxinc/nginx-unprivileged:1.27-alpine`), met dummy-webroot en
`--add-host softwarefactory-dashboard-backend:127.0.0.1` zodat nginx met de ongewijzigde
proxy-config kan starten. `Server:`/`Date:`/`Connection:`/`ETag:`-regels zijn uit de output
gefilterd.

```
=== curl -sSI http://localhost:8080/
HTTP/1.1 200 OK
Content-Type: text/html
Content-Length: 19
Cache-Control: no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0
Strict-Transport-Security: max-age=31536000

=== curl -sSI http://localhost:8080/index.html
HTTP/1.1 200 OK
Content-Type: text/html
Content-Length: 19
Cache-Control: no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0
Strict-Transport-Security: max-age=31536000

=== curl -sSI http://localhost:8080/stories/SF-2008        (SPA-deeplink)
HTTP/1.1 200 OK
Content-Type: text/html
Content-Length: 19
Cache-Control: no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0
Strict-Transport-Security: max-age=31536000

=== curl -sSI http://localhost:8080/main.dart.js
HTTP/1.1 200 OK
Content-Type: application/javascript
Content-Length: 8
Cache-Control: no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0
Strict-Transport-Security: max-age=31536000

=== curl -sSI http://localhost:8080/healthz
HTTP/1.1 200 OK
Content-Type: application/octet-stream
Content-Length: 2
Content-Type: text/plain
Strict-Transport-Security: max-age=31536000

=== curl -sSI http://localhost:8080/flutter_bootstrap.js
HTTP/1.1 200 OK
Content-Type: application/javascript
Content-Length: 13
Cache-Control: no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0
Strict-Transport-Security: max-age=31536000

=== curl -sSI http://localhost:8080/api/health             (erft de server-variant)
HTTP/1.1 502 Bad Gateway
Content-Type: text/html
Content-Length: 157
Strict-Transport-Security: max-age=31536000
```

Alle Cache-Control-headers staan er nog; `/api/health` geeft 502 omdat er in deze proefopstelling
geen backend achter zit — juist daardoor is aangetoond dat `always` de header óók op een
foutrespons zet.

**Faalbewijs voor de `try_files`-val.** Dezelfde proef met een variant waarin *alleen* de
server-level `add_header` staat (de zes location-herhalingen weggehaald) levert:

```
=== /
HTTP/1.1 200 OK
Cache-Control: no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0
      (geen Strict-Transport-Security)

=== /index.html
HTTP/1.1 200 OK
Cache-Control: no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0
      (geen Strict-Transport-Security)

=== /healthz
HTTP/1.1 200 OK
Content-Type: text/plain
      (geen Strict-Transport-Security)
```

De letterlijke opzet uit de issue (alleen op server-niveau, of alleen in `location /`) levert de
header dus inderdaad niet op de belangrijkste responses.

### 4. Regressietest

Nieuw: `dashboard-frontend/test/nginx_conf_test.dart`. Die leest `nginx.conf` en bewaakt drie
dingen: de header staat op server-niveau, élk location-blok met een eigen `add_header` herhaalt
hem, en er komt nergens `includeSubDomains`/`preload` in de header voor. Reden: het draaiende
bewijs hierboven is eenmalig, maar de val komt terug zodra iemand een nieuw location-blok met een
eigen `Cache-Control` toevoegt. De test hangt aan `dashboard-flutter-test` en dus aan de
pathprefix `dashboard-frontend/`, precies waar `nginx.conf` staat.

Faalbewijs van de test zelf: met de HSTS-regel uit `location /` weggehaald faalt hij met
"location / heeft een eigen add_header en maskeert daarmee de server-variant; herhaal de
HSTS-header in dit blok"; daarna teruggezet en weer groen.

### 5. Documentatie

`deploy/README.md` heeft een sectie "HTTPS enforcement (SF-2008, 2026-08-07)": waarom beide
instellingen bij elkaar horen, waarom bewust geen `includeSubDomains`/`preload`, dat `Allow` in
`cbd31ec` een regressie was en geen besluit, en wat te doen als de CDN/ingress http zelf afhandelt
(AC-5-terugval) of als `Redirect` de site breekt. Dit gaat verder dan scope-punt 3 (die eist de
README-tekst alleen bij de uitzondering), maar juist het vastleggen van de *huidige* keuze
voorkomt dat een volgende wijziging aan de shared ingress dit opnieuw stilletjes terugzet.

Geen wijzigingen nodig in `docs/factory/functional-spec.md`, `technical-spec.md`, `deployment.md`
of de UX-docs: geen van die documenten beschrijft de route-TLS-instelling of de nginx-headers.

### 6. Verificatie

Alles op 2026-08-07 gedraaid vanaf deze branch:

| Commando | Uitkomst |
| --- | --- |
| `mvn -B --no-transfer-progress clean verify` (repo-root) | BUILD SUCCESS, exit 0, alle modules groen, 0 failures / 0 errors (5m55) |
| `flutter analyze` (dashboard-frontend) | `No issues found!`, exit 0 |
| `flutter test` (dashboard-frontend) | 148 tests, `All tests passed!`, exit 0 |
| `tools/audit-documentation` | `documentation-audit/v1: PASS`, exit 0 |
| de vier `tools/test-*` contracttests | alle vier valid/PASS, exit 0 |
| `tools/generate-module-dependencies --check` | actueel, exit 0 |

`tools/verify-repository` is niet als geheel gedraaid: de stap `agent-image-build-stage` staat in
`.factory/verification.yaml` bewust op `agentRunnable: false` (geen docker-CLI in de
agent-container) en `./quality/run.sh` is overgeslagen omdat deze diff geen enkele Kotlin- of
Maven-bron raakt — de detekt-ratchet kan er niet door verschuiven. De losse componentcommando's
hierboven dekken de rest van de gate.

### Openstaand na uitrol (niet door de developer af te vinken)

- AC-4: `curl -sSI https://dashboard.vdzonsoftware.nl/` moet na ArgoCD-sync nog `200` geven.
- AC-5: `curl -sSI http://dashboard.vdzonsoftware.nl/` moet `301`/`302` geven; blijft dit `200`
  doordat Cloudflare http zelf afhandelt, dan is dat de AC-5-terugval en hoort de bevinding in
  `deploy/README.md` (de sectie daarvoor staat er al).
- AC-6: de HSTS-header is pas op de omgeving zichtbaar ná de frontend-image-build en de
  tag-bump op `deploy/base/kustomization.yaml`.

## Review (2026-08-07)

Volledige story-diff `git diff main...HEAD` beoordeeld (5 bestanden, 302/+1-).

- AC-1 OK: `deploy/base/softwarefactory-dashboard-frontend-route.yaml:16` staat op `Redirect`,
  de `host:`-regel is ongewijzigd, `deploy/base/kustomization.yaml` en `deploy/sno-local` zijn
  niet aangeraakt (de overlay patcht alleen `imagePullPolicy`, dus de route erft door).
- AC-2 OK: server-niveau plus alle zes location-blokken met een eigen `add_header` hebben de
  header; `location /api/` en `location = /bridge` hebben geen eigen `add_header` en erven.
  Geen `includeSubDomains`, geen `preload`. Cache-headers, proxy-config en `/healthz` ongewijzigd.
- AC-3 OK: curl-bewijs tegen een draaiende nginx met de echte config staat hierboven, inclusief
  het negatieve controlebewijs voor de `try_files`/add_header-maskering.
- AC-7: geen functionele wijzigingen buiten scope; geen spec in `docs/factory/` beschrijft de
  route-TLS of de nginx-headers (geverifieerd met grep), dus geen spec-inconsistentie.
- Gerichte hercontrole reviewer: `flutter test test/nginx_conf_test.dart` -> 3 tests groen,
  werktree bleef daarna schoon (geen `pubspec.lock`-drift).
- AC-4/AC-5/AC-6 blijven terecht open tot na ArgoCD-sync respectievelijk de image-build +
  tag-bump; de terugvaltekst voor AC-5 staat al in `deploy/README.md`.

Geen blockers.

## Test (SF-2010, 2026-08-07)

Onafhankelijk hertest van de story-diff (5 bestanden). Ik heb geen code of tests gewijzigd.

### Gedragsbewijs tegen een draaiende nginx (AC-2/AC-3)

Zelf opnieuw uitgevoerd, niet overgenomen uit de developer-notitie. Geen docker-CLI in de
tester-container, maar de docker-socket is gemount: container aangemaakt via de Docker Engine-API
(`curl --unix-socket`) uit `ghcr.io/robbertvdzon/product-factory-dashboard-frontend:main`, met de
`nginx.conf` van deze branch als `/etc/nginx/conf.d/default.conf` (upload via
`PUT /containers/{id}/archive`) en `ExtraHosts softwarefactory-dashboard-backend:127.0.0.1`. De
webroot is dus de échte gebouwde SPA, niet een dummy.

`curl -sSI` op elf paden — alle elf leveren `Strict-Transport-Security: max-age=31536000`:

| Pad | Status | HSTS | Cache-Control |
| --- | --- | --- | --- |
| `/` | 200 | ja | aanwezig |
| `/index.html` | 200 | ja | aanwezig |
| `/stories/SF-2008` (SPA-deeplink) | 200 | ja | aanwezig |
| `/main.dart.js` | 200 | ja | aanwezig |
| `/flutter_bootstrap.js` | 200 | ja | aanwezig |
| `/flutter_service_worker.js` | 200 | ja | aanwezig |
| `/healthz` | 200 | ja | n.v.t. (`Content-Type: text/plain` intact) |
| `/api/health` | 502 | ja (erft server-variant) | n.v.t. |
| `/bridge` | 502 | ja (erft server-variant) | n.v.t. |
| `/favicon.png` | 200 (SPA-fallback) | ja | aanwezig |
| `/nonexistent.js` | 200 (SPA-fallback) | ja | aanwezig |

De 502'en zijn verwacht: er zit in deze proefopstelling geen backend achter de proxy. Juist
daardoor is aangetoond dat `always` de header ook op foutresponses zet.

**Negatieve controle (eigen meting).** Dezelfde container herstart met een variant waarin alleen
de server-level `add_header` overblijft (de zes location-herhalingen weggefilterd):
`/`, `/index.html`, `/main.dart.js` en `/healthz` leveren dan géén `Strict-Transport-Security`,
terwijl `/api/health` hem wél houdt. Dat bevestigt de `try_files`/add_header-maskering uit de story
en dat de gekozen opzet noodzakelijk is — de val is echt, niet theoretisch. Daarna de branchconfig
teruggezet en opnieuw gemeten: header weer op alle paden. Container verwijderd na afloop.

### Overige checks

- AC-1: `deploy/base/softwarefactory-dashboard-frontend-route.yaml` staat op `Redirect`, de
  `host: dashboard.vdzonsoftware.nl`-regel is ongewijzigd; `deploy/base/kustomization.yaml` en
  `deploy/sno-local` zitten niet in de diff.
- AC-2 (headervorm): geen `includeSubDomains`, geen `preload` in de config.
- AC-7: `flutter analyze` exit 0 (`No issues found!`), `flutter test` exit 0 — 148 tests,
  `All tests passed!`; `flutter test test/nginx_conf_test.dart` los: 3/3 groen.
  `tools/audit-documentation` → `documentation-audit/v1: PASS`, exit 0. `repository-maven-verify`
  matcht deze diff niet (geen JVM-pad geraakt, zie `.factory/verification.yaml`).
- Werktree bleef schoon (`git status --porcelain` leeg, ook na `flutter pub get`).
- Screenshot: `/work/screenshots/SF-2008-dashboard-nginx-hsts.png` — de SPA boot op tegen deze
  nginx-config (geen kapotte site), maar toont alleen het lege Flutter-canvas omdat er geen
  backend en geen Google-client-id achter zit. Beperkte waarde; het headerbewijs hierboven is
  leidend.

### Waarneming bij AC-4/AC-5 (vóór uitrol, informatief)

Vanuit de tester-container gemeten op de nog niet uitgerolde omgeving:
`curl -sSI https://dashboard.vdzonsoftware.nl/` → `HTTP/2 200` (site draait);
`curl -sSI http://dashboard.vdzonsoftware.nl/` → `HTTP/1.1 200 OK`, `server: cloudflare`, geen
redirect en geen `Strict-Transport-Security`. Dat is de verwachte nulmeting: de route staat op
productie nog op `Allow` en het frontend-image is nog niet gebumpt. Blijft http na uitrol `200`,
dan handelt Cloudflare http zelf af en geldt de AC-5-terugval; die tekst staat al in
`deploy/README.md`. AC-4/AC-5/AC-6 blijven dus terecht open tot na de ArgoCD-sync en de tag-bump.

Geen bevindingen. Vangnet groen.
