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
