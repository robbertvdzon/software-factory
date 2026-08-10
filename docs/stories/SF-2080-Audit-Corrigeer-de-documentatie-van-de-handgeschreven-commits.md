# SF-2080 - [Audit] Corrigeer de documentatie van de handgeschreven commits van 7-9 augustus (route-TLS en Product Factory-API)

## Story

[Audit] Corrigeer de documentatie van de handgeschreven commits van 7-9 augustus (route-TLS en Product Factory-API)

<!-- refined-by-factory -->

## Samenvatting

Vier commits van 7 tot 9 augustus zijn buiten de factory-pipeline om geschreven en hebben geen documenter-stap gehad. Daardoor staan er nu onjuistheden in documentatie die wel gebruikt wordt.

Het belangrijkste: de technische spec beschrijft de verkeerde beveiligingsinstelling voor het dashboard. Wie dat leest en de instelling "corrigeert" naar wat er staat, legt het dashboard plat. Daarnaast beschrijven twee documenten een koppeling met Product Factory onvolledig, ontbreken er drie velden in de API-beschrijving, en is die hele koppeling nergens terug te vinden buiten één endpointdocument.

Deze story wijzigt alleen tekst: geen code, geen configuratie, geen migratie.

## Scope

Uitsluitend documentatiewijzigingen in de volgende bestanden. Geen wijziging aan code, YAML, migraties of tests.

1. `docs/factory/technical-spec.md:45-48` — de zin over de OpenShift-route beschrijft `insecureEdgeTerminationPolicy: Redirect`, terwijl de feitelijke en bewust gekozen waarde `Allow` is (`deploy/base/softwarefactory-dashboard-frontend-route.yaml:14-18`). Herschrijf naar `Allow` met dezelfde onderbouwing als `deploy/README.md:61-78`: Cloudflare termineert publieke https en benadert de origin via plain http, dus `Redirect` stuurt de client terug naar dezelfde publieke url — een lus die het dashboard én de `/bridge`-websocket breekt; publieke http→https-afdwinging hoort bij Cloudflare. Het HSTS-deel van de zin (`Strict-Transport-Security: max-age=31536000`, bewust zonder `includeSubDomains` en `preload`) en de verwijzing naar `deploy/README.md` §HTTPS enforcement blijven ongewijzigd.
2. `runbook.md:254` — verwijder het weesnummer `(2)` aan het begin van de zin over de `Strict-Transport-Security`-header; de bijbehorende `(1)` bestaat niet meer in die bullet. Verder niets aan die bullet wijzigen.
3. `docs/technical/endpoints.md:84-85` — de bullet over `POST /api/integrations/v1/stories/{storyKey}/answers` zegt "uitsluitend de bekende vraag-naar-antwoordfaseovergangen". Vul aan dat de route voor een subtaak ook `manual-action-done` accepteert: het als gedaan afvinken van een `manual`-subtaak die op `awaiting-human` staat. Dekking in code: `SUBTASK_ANSWER_PHASES` in `ProductFactoryIntegrationApi.kt:198-201`, fase-definitie in `SubtaskPhase.kt`.
4. `docs/adr/0003-product-factory-integratietoken.md:21` — dezelfde aanvulling op de regel `POST /stories/{storyKey}/answers` — "antwoord op een agentvraag voor story of subtaak". Alleen die opsommingsregel aanvullen; Context/Decision/Consequences verder ongemoeid laten.
5. `docs/technical/endpoints.md:80-82` — vul de bullet over `POST /api/integrations/v1/stories` aan met de drie optionele requestvelden uit `ProductFactoryStoryRequest` (`ProductFactoryIntegrationApi.kt:30-32`) en hun terugval (`:109-115`): `notificationEvents` (terugval `DEPLOYED`, `QUESTION`, `MANUAL_ACTION_REQUIRED`, `ERROR`), `questionsAllowed` (terugval `true`) en `approvalMode` (terugval `automatisch`). Maak expliciet dat een oudere Product Factory-versie die deze velden niet meestuurt blijft werken.
6. `docs/technical/external-systems.md` — voeg één alinea toe die de Product Factory-koppeling vindbaar maakt buiten `endpoints.md`, in de stijl van de zes bestaande groepen (als zevende groep, met de bestaande kopjesnummering doorgezet). Inhoud: wie Product Factory is (tweede schrijfpad de factory in, machine-tot-machine), het token `SF_PRODUCT_FACTORY_TOKEN` via `Authorization: Bearer`, de vier routes onder `/api/integrations/v1` (`GET /status`, `POST /stories`, `GET /stories/{storyKey}`, `POST /stories/{storyKey}/answers`), en een verwijzing naar `docs/technical/endpoints.md` §Product Factory-integratie en `docs/adr/0003-product-factory-integratietoken.md`.

Buiten scope: herschrijven van `functional-spec.md`, `technical-spec.md`, `overview.md` of `runbook.md` om de Product Factory-koppeling breder te beschrijven; wijzigingen aan `deploy/README.md` en de rest van de HTTPS-bullet in `runbook.md` (die zijn al correct); wijzigingen aan de route-yaml of `nginx.conf`.

## Acceptance criteria

1. `docs/factory/technical-spec.md` noemt bij de OpenShift-route `insecureEdgeTerminationPolicy: Allow` en nergens meer `Redirect` als de geldende waarde, met een onderbouwing die overeenkomt met `deploy/README.md` §HTTPS enforcement (Cloudflare termineert publieke https en benadert de origin via http; `Redirect` geeft een lus die dashboard en `/bridge` breekt).
2. Het HSTS-deel van diezelfde passage in `technical-spec.md` is inhoudelijk ongewijzigd: `max-age=31536000`, bewust zonder `includeSubDomains` en zonder `preload`, met verwijzing naar `deploy/README.md`.
3. `runbook.md` bevat in de HTTPS-bullet geen losstaand `(2)` meer; de rest van die bullet is onveranderd.
4. Zowel `docs/technical/endpoints.md` als `docs/adr/0003-product-factory-integratietoken.md` maakt bij de answers-route duidelijk dat Product Factory naast agentvragen ook een `manual`-subtaak op `awaiting-human` als gedaan mag afvinken (`manual-action-done`).
5. De bullet over `POST /api/integrations/v1/stories` in `endpoints.md` noemt `notificationEvents`, `questionsAllowed` en `approvalMode` als optioneel, met hun terugvalwaarden, en maakt duidelijk dat een client die ze weglaat blijft werken.
6. `docs/technical/external-systems.md` bevat één alinea/groep over Product Factory met: rol van het systeem, `SF_PRODUCT_FACTORY_TOKEN`, de vier routes onder `/api/integrations/v1`, en verwijzingen naar `endpoints.md` §Product Factory-integratie en ADR-0003. `grep -l "Product Factory" docs/technical/external-systems.md` geeft een treffer.
7. `git diff --stat` raakt uitsluitend de bestanden uit Scope (`docs/factory/technical-spec.md`, `runbook.md`, `docs/technical/endpoints.md`, `docs/adr/0003-product-factory-integratietoken.md`, `docs/technical/external-systems.md`) — geen `.kt`, `.yaml`, `.sql`, `.conf` of testbestanden.
8. Alle documentatie is in het Nederlands, conform de repo-conventie in `runbook.md` §Conventies.

## Aannames

- De feitelijke waarde `Allow` in de route-yaml blijft ongewijzigd; deze story past uitsluitend de beschrijving daarvan aan. Er wordt geen poging gedaan om `Redirect` alsnog werkend te maken.
- Bij punt 5 worden de vier default-eventnamen letterlijk genoemd zodat de documentatie zelfstandig leesbaar is, in plaats van te verwijzen naar de constante `NotificationEvent.DEFAULT`. Het nuanceverschil in de code — `notificationEvents` en `approvalMode` vallen terug bij ontbreken én bij een lege waarde, `questionsAllowed` alleen bij ontbreken — wordt in de tekst niet uitgesplitst; "ontbreken of leeg" volstaat voor deze doelgroep.
- Voor punt 6 wordt de bestaande genummerde structuur van `external-systems.md` gevolgd met een zevende groep in dezelfde vorm (regels voor code/aanroepwijze/configuratie plus een korte gebruik-toelichting), omdat een losse alinea zonder kop in dat document niet vindbaar zou zijn.
- De ADR-status blijft `Accepted` en de datum `2026-08-08` blijft staan: de aanvulling herstelt een omissie in de beschrijving en verandert het genomen besluit niet.
- Er is geen geautomatiseerde test op de inhoud van deze documenten; verificatie loopt via lezen en `grep`. Voor deze story hoeven geen tests toegevoegd te worden.

## Eindsamenvatting

# Eindsamenvatting SF-2080 — [Audit] Corrigeer de documentatie van de handgeschreven commits van 7–9 augustus

## Wat er is gebouwd
Een pure documentatiecorrectie: de vier commits van 7–9 augustus die buiten de factory-pipeline om zijn geschreven, hadden geen documenter-stap gehad en lieten onjuistheden achter in documentatie die actief gebruikt wordt. Alle zes scope-punten zijn afgehandeld:

1. **`docs/factory/technical-spec.md`** — de route-passage beschreef `insecureEdgeTerminationPolicy: Redirect`, terwijl de feitelijke en bewust gekozen waarde `Allow` is. Dit was het risicovolste punt: wie de spec volgde en de instelling "corrigeerde", zou het dashboard én de `/bridge`-websocket platleggen. De tekst noemt nu `Allow` met dezelfde onderbouwing als `deploy/README.md` §HTTPS enforcement (Cloudflare termineert de publieke https en benadert de origin via plain http). `Redirect` staat er alleen nog als expliciet afgewezen alternatief. Het HSTS-deel is inhoudelijk ongemoeid gelaten.
2. **`runbook.md`** — het weesnummer `(2)` voor de `Strict-Transport-Security`-zin is verwijderd; de bijbehorende `(1)` bestond niet meer. Rest van de bullet onaangeroerd.
3. **`docs/technical/endpoints.md`** (answers-route) — vermeldt nu dat een subtaak ook via `manual-action-done` als gedaan afgevinkt kan worden (een `manual`-subtaak op `awaiting-human`).
4. **`docs/adr/0003-product-factory-integratietoken.md`** — dezelfde aanvulling op de betreffende opsommingsregel; status `Accepted`, datum en Context/Decision/Consequences ongewijzigd.
5. **`docs/technical/endpoints.md`** (create-route) — de drie ontbrekende optionele velden zijn toegevoegd met hun terugvalwaarden: `notificationEvents` (→ `DEPLOYED`, `QUESTION`, `MANUAL_ACTION_REQUIRED`, `ERROR`), `questionsAllowed` (→ `true`) en `approvalMode` (→ `automatisch`), plus de expliciete zin dat een oudere Product Factory-versie zonder die velden blijft werken.
6. **`docs/technical/external-systems.md`** — nieuwe zevende groep `## 7. Product Factory` in de stijl van de zes bestaande groepen (Code/Aanroepwijze/Configuratie + Gebruik), zodat de koppeling ook buiten `endpoints.md` vindbaar is.

## Gemaakte keuzes
- De feitelijke configuratie is **niet** aangepast: de story corrigeert alleen de beschrijving van `Allow`. Er is geen poging gedaan `Redirect` alsnog werkend te maken.
- De vier default-eventnamen zijn letterlijk uitgeschreven in plaats van te verwijzen naar de code-constante, zodat de documentatie zelfstandig leesbaar is.
- De terugvalconditie is als "ontbreken of leeg" geschreven. In code valt `questionsAllowed` strikt genomen alleen bij ontbreken terug; dat nuanceverschil is bewust niet uitgesplitst — het is voor deze doelgroep irrelevant en zou de tekst onnodig zwaar maken.
- Voor punt 6 is gekozen voor een volwaardige genummerde groep in plaats van een losse alinea, omdat een koploze alinea in dat document niet vindbaar zou zijn.
- **Bijvangst:** `docs/technical/README.md` noemde al 7 hoofdgroepen terwijl `external-systems.md` op 6 stond. Die bestaande inconsistentie is met deze wijziging meteen opgelost (telzin nu op 7).

## Wat is getest
- Alle acht acceptatiecriteria zijn één op één tegen code en config geverifieerd — geen bevindingen. Feitchecks: de route-yaml staat inderdaad op `Allow`; `SUBTASK_ANSWER_PHASES` bevat `manual-action-done` en die fase is alleen via `targetType=subtask` bereikbaar en gekoppeld aan `awaiting-human`; de drie optionele velden en hun terugval zijn in de API-code terug te vinden.
- `tools/audit-documentation` → `documentation-audit/v1: PASS` (exitcode 0), zowel in de review- als in de testsandbox.
- `mvn -B clean verify` vanaf de repo-root → BUILD SUCCESS, 0 failures / 0 errors.
- `git diff --stat main...HEAD` raakt uitsluitend de vijf scope-bestanden plus het worklog — geen `.kt`, `.yaml`, `.sql`, `.conf` of testbestanden.

## Bewust niet gedaan
- Geen code-, YAML-, migratie- of configuratiewijziging; deze story wijzigt alleen tekst.
- Geen nieuwe geautomatiseerde tests: er bestaat geen testharnas op documentinhoud, verificatie liep via lezen, `grep` en de documentatie-audit.
- De Product Factory-koppeling is niet breder beschreven in `functional-spec.md`, `technical-spec.md`, `overview.md` of `runbook.md` — dat viel buiten scope.
- `deploy/README.md`, de route-yaml, `nginx.conf` en de rest van de HTTPS-bullet in `runbook.md` zijn niet aangeraakt; die waren al correct.
- Geen screenshots of preview-URL: bij een docs-only wijziging is er geen UI-doel om te tonen.

<!-- deploy-summary:start -->
De documentatie over de beveiligde toegang tot het dashboard was op één plek verkeerd opgeschreven; dat is nu rechtgezet, zodat niemand het dashboard per ongeluk onbruikbaar maakt door de handleiding te volgen. Ook is de beschrijving van de koppeling met Product Factory aangevuld en beter terug te vinden. Aan de werking van het systeem zelf is niets veranderd.
<!-- deploy-summary:end -->
