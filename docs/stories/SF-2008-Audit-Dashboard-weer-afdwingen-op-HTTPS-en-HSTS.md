# SF-2008 - [Audit] Dashboard weer afdwingen op HTTPS en HSTS toevoegen

## Story

[Audit] Dashboard weer afdwingen op HTTPS en HSTS toevoegen

<!-- refined-by-factory -->

## Samenvatting

Het dashboard is op dit moment ook gewoon over onbeveiligd http te gebruiken. Wie zo binnenkomt, stuurt zijn inlogtoken onversleuteld over het netwerk mee, en dat token blijft 30 dagen geldig en kan niet ingetrokken worden. Ook is er geen signaal waarmee een browser zichzelf naar https dwingt.

We zetten daarom twee dingen recht: het http-verkeer wordt doorgestuurd naar https, en de site vertelt de browser voortaan expliciet dat hij alleen nog via https mag terugkomen. Blijkt het doorsturen niet te kunnen omdat de gedeelde ingress ervoor plain http nodig heeft, dan leggen we die keuze vast in de deploy-documentatie zodat een volgende audit dit niet opnieuw als fout aanmerkt.

## Scope

Twee wijzigingen, plus documentatie bij de uitzondering.

**1. Route weer op Redirect**

- `deploy/base/softwarefactory-dashboard-frontend-route.yaml:16`: `insecureEdgeTerminationPolicy: Allow` → `Redirect`. Dit was de waarde tot commit `cbd31ec` (2026-08-07).
- De `host: dashboard.vdzonsoftware.nl`-regel die diezelfde commit toevoegde blijft ongewijzigd; die was het doel van die commit en staat los van dit probleem.
- Verder niets in dat bestand of in `deploy/base/kustomization.yaml` aanpassen.

**2. HSTS-header in `dashboard-frontend/nginx.conf`**

- Header: `add_header Strict-Transport-Security "max-age=31536000" always;`
- Bewust **zonder** `includeSubDomains` en **zonder** `preload`: dat zijn moeilijk terug te draaien toezeggingen over het hele domein.
- Let op de nginx-headersemantiek — het letterlijke voorstel uit de issue (alleen in `location /`, regel 55-58) levert de header niet op de belangrijkste respons:
  - `location /` doet `try_files $uri $uri/ /index.html`; die fallback-URI is een interne redirect en draait opnieuw location-matching, dus een request op `/` wordt uiteindelijk beantwoord vanuit `location = /index.html`.
  - `add_header` erft alleen van het omsluitende blok en wordt volledig gemaskeerd zodra de gekozen location zelf een `add_header` heeft. De vijf locations met een eigen `Cache-Control` (`= /index.html`, `= /flutter_bootstrap.js`, `= /flutter_service_worker.js`, `= /main.dart.js`, `/`) zien een header op server-niveau dus niet.
  - Kies daarom een opzet die aantoonbaar op álle responses uitkomt: de header op server-niveau **en** herhaald in elk location-blok dat al een eigen `add_header` heeft. `location /api/`, `location = /bridge` en de rest hebben geen eigen `add_header` en erven de server-variant.
- Geen andere nginx-instellingen wijzigen (cache-headers, proxy-config, `/healthz`).

**3. Alleen bij de uitzondering: documentatie**

Blijkt punt 1 niet te kunnen omdat de gedeelde ingress/CDN plain http op de origin nodig heeft (bijvoorbeeld een redirect-loop of een kapotte site na uitrol): zet regel 16 terug op `Allow`, voer alleen punt 2 uit, en leg in `deploy/README.md` vast:

- dat `Allow` daar bewust staat en waarom,
- dat de https-afdwinging in dat geval in de gedeelde ingress / Cloudflare hoort (bijvoorbeeld "Always Use HTTPS"),
- de datum en dit story-nummer, zodat een volgende audit dit niet opnieuw als regressie aanmerkt.

**Buiten scope**

- Cloudflare- of ingress-instellingen buiten deze repo aanpassen (die zitten niet in deze checkout).
- Alles rond de levensduur, het formaat of het intrekken van het sessietoken (`AuthService.kt`), en `ApiClient.baseUrl` in de frontend. Dat is de reden dát dit zwaar weegt, niet het werk zelf.
- `includeSubDomains`, `preload`, of aanmelding bij de HSTS-preloadlijst.
- Overige security-headers (CSP, X-Frame-Options, enzovoort).

## Acceptance criteria

1. `deploy/base/softwarefactory-dashboard-frontend-route.yaml` heeft `insecureEdgeTerminationPolicy: Redirect`, met ongewijzigde `host:`-regel — óf staat op `Allow` mét de onderbouwing uit scope-punt 3 in `deploy/README.md`.
2. `dashboard-frontend/nginx.conf` levert `Strict-Transport-Security: max-age=31536000` op de respons van `/` én van `/index.html`, en op de responses van de overige location-blokken. De header bevat geen `includeSubDomains` en geen `preload`.
3. Dat AC-2 klopt is aangetoond tegen een draaiende nginx met deze config (bijvoorbeeld het lokaal gebouwde frontend-image), niet alleen beredeneerd uit de configtekst — juist vanwege de `try_files`/interne-redirect-valkuil hierboven.
4. `curl -sSI https://dashboard.vdzonsoftware.nl/` geeft na uitrol nog steeds `200` (geen redirect-loop, geen kapotte site).
5. Bij de Redirect-route: `curl -sSI http://dashboard.vdzonsoftware.nl/` geeft een `301` of `302` naar de https-variant. Blijft dit een `200` doordat de CDN/ingress zelf http afhandelt en niet doorstuurt, dan is dat geen implementatiefout maar valt de story terug op scope-punt 3: de bevinding en de plek waar de afdwinging wél moet gebeuren, worden expliciet in `deploy/README.md` én in de story-afronding gemeld.
6. Bij de alternatieve route (regel 16 blijft `Allow`): `curl -sSI https://dashboard.vdzonsoftware.nl/` bevat de `Strict-Transport-Security`-header.
7. Geen andere functionele wijzigingen; bestaande builds/tests blijven groen.

## Aannames

- De verificatie met `curl` gebeurt na uitrol; de route-wijziging wordt door ArgoCD opgepakt zodra dit op `main` staat.
- De `nginx.conf`-wijziging zit in het frontend-image en wordt pas op de omgeving actief ná de image-build en de daaropvolgende tag-bump op `deploy/base/kustomization.yaml`. AC-2/AC-3 zijn daarom lokaal te verifiëren; AC-6 pas na die bump. Dit is geen reden om de story open te houden.
- Het externe verkeer loopt via Cloudflare (`docs/ontwerp-bridge-dashboard.md:40`). Of een externe http-request een redirect ziet, hangt dus mede af van hoe die CDN http afhandelt — vandaar de fallback in AC-5.
- `deploy/sno-local` erft `deploy/base` ongewijzigd en patcht alleen `imagePullPolicy`; de route-wijziging werkt daar automatisch door en vergt geen aparte aanpassing.
- Er is geen automatische test op de inhoud van `nginx.conf` of van de route-manifests in deze repo; verificatie is handmatig/observationeel zoals in de AC's beschreven.

## Eindsamenvatting

## Eindsamenvatting SF-2008 — Dashboard weer afdwingen op HTTPS en HSTS toevoegen

### Wat is gebouwd

**1. HTTP-verkeer wordt weer doorgestuurd naar HTTPS**
`deploy/base/softwarefactory-dashboard-frontend-route.yaml` staat weer op `insecureEdgeTerminationPolicy: Redirect` (was per commit `cbd31ec` op `Allow` gezet als neveneffect van het routeren via de gedeelde ingress — dat was een regressie, geen besluit). De `host:`-regel is ongewijzigd; `kustomization.yaml` en de `sno-local`-overlay zijn niet aangeraakt.

**2. HSTS-header op alle responses**
`dashboard-frontend/nginx.conf` stuurt `Strict-Transport-Security: max-age=31536000` mee, op server-niveau én herhaald in de zes location-blokken die zelf al een `add_header` hebben. Dat is geen dubbelop: nginx maskeert de server-variant volledig zodra een location een eigen `add_header` declareert, en een request op `/` wordt via `try_files` uiteindelijk beantwoord vanuit `location = /index.html`. Zonder die herhaling zou de header juist op de belangrijkste responses ontbreken.

**3. Documentatie**
Nieuwe sectie "HTTPS enforcement (SF-2008, 2026-08-07)" in `deploy/README.md`: waarom beide instellingen bij elkaar horen, dat `Allow` een regressie was, en wat te doen als Cloudflare http zelf blijkt af te handelen of als `Redirect` de site zou breken.

**4. Regressietest**
Nieuw: `dashboard-frontend/test/nginx_conf_test.dart` bewaakt dat de header op server-niveau staat, in elk location-blok met eigen `add_header` herhaald wordt, en nergens `includeSubDomains`/`preload` bevat.

### Gemaakte keuzes

- **Bewust zonder `includeSubDomains` en `preload`.** Dat zijn toezeggingen over het hele domein die nauwelijks terug te draaien zijn.
- **De uitzonderingsroute (terugvallen op `Allow`) is niet gekozen.** Er is geen aanwijzing dat de gedeelde ingress plain http op de origin nodig heeft; dat is pas na uitrol waarneembaar.
- **De README-sectie is toch geschreven, ook zonder uitzondering.** De story eiste dit alleen bij de terugval, maar juist het vastleggen van de huidige keuze voorkomt dat een volgende ingress-wijziging dit weer stilletjes terugzet.
- **Een geautomatiseerde test toegevoegd bovenop het handmatige bewijs.** Het draaiende bewijs is eenmalig; de valkuil komt terug zodra iemand een nieuw location-blok met eigen `Cache-Control` toevoegt.

### Wat is getest

Developer én tester hebben onafhankelijk van elkaar gemeten tegen een **draaiende nginx**, niet beredeneerd uit de configtekst. De tester gebruikte het echte gepubliceerde frontend-image met de branch-config: elf paden gecontroleerd (`/`, `/index.html`, SPA-deeplink, `/main.dart.js`, de bootstrap- en service-worker-scripts, `/healthz`, `/api/`, `/bridge`, fallback-paden) — **alle elf leveren de HSTS-header**, met de bestaande `Cache-Control`-headers intact.

Er is ook een **negatieve controle** gedaan: met alleen de server-level header verdwijnt de HSTS-header op `/`, `/index.html`, `/main.dart.js` en `/healthz`. De valkuil is dus echt, en de gekozen opzet is aantoonbaar nodig.

Verder groen: `mvn clean verify` (alle modules), `flutter analyze` (geen issues), `flutter test` (148 tests), `tools/audit-documentation` (PASS), de vier contracttests en de module-dependency-check.

### Bewust niet gedaan

- **Geen Cloudflare- of ingress-instellingen aangepast** — die zitten niet in deze checkout.
- **Niets aan de sessietoken-levensduur** (30 dagen, niet intrekbaar). Dat is de reden dát deze audit-bevinding zwaar weegt, maar het viel expliciet buiten scope en blijft dus openstaan als apart risico.
- **Geen overige security-headers** (CSP, X-Frame-Options).

### Openstaand tot na uitrol

Drie acceptatiecriteria zijn per definitie pas na deploy vast te stellen: dat de site over https nog gewoon `200` geeft, dat http een `301`/`302` teruggeeft, en dat de HSTS-header op de omgeving zichtbaar is (dat laatste pas ná de image-build en de tag-bump op `kustomization.yaml`).

**Let op bij AC-5.** De tester heeft nu al gemeten dat `http://dashboard.vdzonsoftware.nl/` een `200` van Cloudflare teruggeeft. Blijft dat na uitrol zo, dan handelt de CDN http zelf af en bereikt het verzoek de OpenShift-route niet. Dat is dan **geen implementatiefout**, maar de expliciet voorziene terugval: de https-afdwinging hoort in dat geval in Cloudflare ("Always Use HTTPS") geconfigureerd te worden. Dat is werk buiten deze repo en vergt een actie van de PO/beheerder. De terugvaltekst staat al in `deploy/README.md`.

<!-- deploy-summary:start -->
Het dashboard is nu alleen nog via een beveiligde verbinding te gebruiken: wie per ongeluk via een onbeveiligde link binnenkomt, wordt automatisch doorgestuurd. Je browser onthoudt dat vanaf nu ook zelf, zodat je inloggegevens niet meer per ongeluk onbeveiligd over het netwerk kunnen gaan. Voor het gebruik verandert er verder niets.
<!-- deploy-summary:end -->
