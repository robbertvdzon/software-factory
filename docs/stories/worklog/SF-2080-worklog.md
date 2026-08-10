# SF-2080 - Worklog

Story-context bij eerste pickup:
Corrigeer TLS-passage en Product Factory-documentatie

Pure documentatiewijziging in vijf bestanden; geen code, YAML, migraties of tests.

1. docs/factory/technical-spec.md:45-48 - herschrijf de zin over de OpenShift-route naar `insecureEdgeTerminationPolicy: Allow` (feitelijke waarde in deploy/base/softwarefactory-dashboard-frontend-route.yaml:14-18), met dezelfde onderbouwing als deploy/README.md §HTTPS enforcement: Cloudflare termineert publieke https en benadert de origin via plain http, dus `Redirect` stuurt de client terug naar dezelfde publieke url - een lus die het dashboard én de /bridge-websocket breekt; publieke http→https-afdwinging hoort bij Cloudflare, niet bij de route. Laat het HSTS-deel (`Strict-Transport-Security: max-age=31536000`, bewust zonder `includeSubDomains` en `preload`) en de verwijzing naar deploy/README.md inhoudelijk ongewijzigd. `Redirect` mag nergens meer als geldende waarde staan.
2. runbook.md:254 - verwijder het losstaande weesnummer `(2)` aan het begin van de zin over de Strict-Transport-Security-header. Wijzig verder niets aan die bullet.
3. docs/technical/endpoints.md:84-85 - vul de bullet over `POST /api/integrations/v1/stories/{storyKey}/answers` aan: naast de vraag-naar-antwoordfaseovergangen accepteert de route voor een subtaak ook `manual-action-done`, het als gedaan afvinken van een `manual`-subtaak die op `awaiting-human` staat (dekking: SUBTASK_ANSWER_PHASES in ProductFactoryIntegrationApi.kt:197-201, SubtaskPhase.kt).
4. docs/adr/0003-product-factory-integratietoken.md:21 - dezelfde aanvulling op de opsommingsregel `POST /stories/{storyKey}/answers`. Alleen die regel aanvullen; status `Accepted`, datum 2026-08-08 en Context/Decision/Consequences blijven ongewijzigd.
5. docs/technical/endpoints.md:80-82 - vul de bullet over `POST /api/integrations/v1/stories` aan met de drie optionele requestvelden en hun terugval: `notificationEvents` (terugval DEPLOYED, QUESTION, MANUAL_ACTION_REQUIRED, ERROR), `questionsAllowed` (terugval true) en `approvalMode` (terugval automatisch). Maak expliciet dat een oudere Product Factory-versie die deze velden niet meestuurt blijft werken. Schrijf de terugvalconditie als 'ontbreken of leeg'; het codenuanceverschil (questionsAllowed valt alleen bij ontbreken terug) wordt bewust niet uitgesplitst.
6. docs/technical/external-systems.md - voeg een zevende genummerde groep '## 7. Product Factory' toe in de stijl van de zes bestaande groepen (bullets Code/Aanroepwijze/Configuratie plus een korte 'Gebruik:'-toelichting): Product Factory als tweede, machine-tot-machine schrijfpad de factory in; token `SF_PRODUCT_FACTORY_TOKEN` via `Authorization: Bearer`; de vier routes onder `/api/integrations/v1` (GET /status, POST /stories, GET /stories/{storyKey}, POST /stories/{storyKey}/answers); verwijzingen naar docs/technical/endpoints.md §Product Factory-integratie en docs/adr/0003-product-factory-integratietoken.md. Niet uitbreiden tot een bredere herschrijving.

Buiten scope: deploy/README.md, de route-yaml, nginx.conf, de rest van de HTTPS-bullet in runbook.md, en het breder beschrijven van Product Factory in functional-spec.md/technical-spec.md/overview.md.

Alle tekst in het Nederlands conform runbook.md §Conventies. Er zijn geen geautomatiseerde tests op documentinhoud; er hoeven geen tests toegevoegd te worden. Sluit af met een eigen review van de diff: `git diff --stat` mag uitsluitend docs/factory/technical-spec.md, runbook.md, docs/technical/endpoints.md, docs/adr/0003-product-factory-integratietoken.md, docs/technical/external-systems.md en het worklog raken - geen .kt, .yaml, .sql, .conf of testbestanden. Werk het worklog bij met wat er gedaan is.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `docs/factory/technical-spec.md` (§Modules, dashboard-frontend): de route-passage beschrijft nu
  `insecureEdgeTerminationPolicy: Allow` met de onderbouwing uit `deploy/README.md`
  §HTTPS enforcement (Cloudflare termineert publieke https en benadert de origin via plain http;
  `Redirect` geeft een lus naar dezelfde publieke url en breekt dashboard én `/bridge`). `Redirect`
  staat er alleen nog als expliciet afgewezen alternatief, niet als geldende waarde. Het HSTS-deel
  (`max-age=31536000`, bewust zonder `includeSubDomains`/`preload`) en de verwijzing naar
  `deploy/README.md` zijn inhoudelijk ongewijzigd gebleven.
- `runbook.md`: het weesnummer `(2)` voor de `Strict-Transport-Security`-zin in de SF-2008-bullet is
  verwijderd; de bijbehorende `(1)` bestond niet meer. Rest van de bullet onaangeroerd.
- `docs/technical/endpoints.md`: de bullet over `POST /api/integrations/v1/stories` noemt nu de drie
  optionele velden `notificationEvents`, `questionsAllowed` en `approvalMode` met hun terugval
  (`DEPLOYED`/`QUESTION`/`MANUAL_ACTION_REQUIRED`/`ERROR`, `true`, `automatisch`) en stelt expliciet
  dat een oudere Product Factory-versie zonder die velden blijft werken. De answers-bullet vermeldt
  dat een subtaak ook via `manual-action-done` als gedaan afgevinkt mag worden
  (`SUBTASK_ANSWER_PHASES` in `ProductFactoryIntegrationApi.kt`).
- `docs/adr/0003-product-factory-integratietoken.md`: dezelfde aanvulling op de opsommingsregel
  `POST /stories/{storyKey}/answers`; status `Accepted`, datum en Context/Decision/Consequences
  ongewijzigd.
- `docs/technical/external-systems.md`: zevende groep `## 7. Product Factory` toegevoegd in de stijl
  van de zes bestaande groepen (Code/Aanroepwijze/Configuratie + `Gebruik:`), met
  `SF_PRODUCT_FACTORY_TOKEN`, de vier routes onder `/api/integrations/v1` en verwijzingen naar
  `endpoints.md` §Product Factory-integratie en ADR-0003. De inleidende telzin staat nu op
  7 hoofdgroepen.

Aangepaste specs in `docs/factory/`: alleen `technical-spec.md` (de TLS-passage), omdat die de enige
plek in `docs/factory/` was met een feitelijk onjuiste beschrijving. De Product Factory-koppeling is
bewust niet breder in `functional-spec.md`/`technical-spec.md` beschreven — dat valt buiten scope.

Verificatie (10-08-2026):
- `tools/audit-documentation` → `documentation-audit/v1: PASS`, exitcode 0.
- `mvn -B --no-transfer-progress clean verify` vanaf de repo-root → BUILD SUCCESS, exitcode 0,
  0 failures / 0 errors (softwarefactory 4m31, totaal 5m06).
- `git diff --stat` raakt uitsluitend `docs/adr/0003-product-factory-integratietoken.md`,
  `docs/factory/technical-spec.md`, `docs/technical/endpoints.md`,
  `docs/technical/external-systems.md`, `runbook.md` en dit worklog — geen `.kt`, `.yaml`, `.sql`,
  `.conf` of testbestanden. Er zijn geen geautomatiseerde tests op documentinhoud, dus er zijn geen
  tests toegevoegd.

## Review SF-2081 (10-08-2026)

Akkoord. Gecontroleerd tegen de code: route-yaml staat feitelijk op
`insecureEdgeTerminationPolicy: Allow` (regel 18) met dezelfde onderbouwing als
`deploy/README.md:66`; `SUBTASK_ANSWER_PHASES` in `ProductFactoryIntegrationApi.kt:198-201`
bevat `manual-action-done` en `SubtaskPhase.kt:64-65` koppelt dat aan `awaiting-human`; de drie
optionele velden en hun terugval staan in `ProductFactoryIntegrationApi.kt:29-32` en `:108-115`.
Diff raakt uitsluitend de vijf scope-bestanden plus dit worklog; geen code/YAML/tests.
`tools/audit-documentation` in de reviewsandbox: `documentation-audit/v1: PASS` (exit 0).
Bijvangst: `docs/technical/README.md:11` noemde al 7 hoofdgroepen terwijl `external-systems.md`
op 6 stond — die bestaande inconsistentie is met deze wijziging opgelost.

## Test SF-2082 (10-08-2026)

Akkoord — alle acht acceptatiecriteria geverifieerd tegen code/config, geen bevindingen.

- AC1/AC2: `docs/factory/technical-spec.md:45-51` noemt `insecureEdgeTerminationPolicy: Allow` met
  de Cloudflare-onderbouwing; `Redirect` staat er alleen nog als afgewezen alternatief (regel 47),
  niet als geldende waarde. Feitcheck: `deploy/base/softwarefactory-dashboard-frontend-route.yaml:18`
  = `Allow`, onderbouwing identiek aan `deploy/README.md` §HTTPS enforcement. HSTS-deel inhoudelijk
  ongewijzigd (`max-age=31536000`, zonder `includeSubDomains`/`preload`, verwijzing naar
  `deploy/README.md`).
- AC3: `runbook.md:254` — het weesnummer `(2)` is weg, de rest van de bullet is per diff onveranderd.
- AC4: `docs/technical/endpoints.md:88-91` en `docs/adr/0003-...:21-23` noemen beide het afvinken van
  een `manual`-subtaak op `awaiting-human` via `manual-action-done`. Codecheck:
  `ProductFactoryIntegrationApi.kt` `SUBTASK_ANSWER_PHASES` bevat `manual-action-done` en die fase is
  alleen bereikbaar via `targetType=subtask` (`answer()`); `SubtaskPhase.kt:64` = `awaiting-human`.
- AC5: de create-bullet noemt de drie optionele velden met terugval en de compat-zin. Codecheck:
  `ProductFactoryStoryRequest` velden nullable met default `null`; `createStory()` valt terug op
  `true`, `"automatisch"` en de vier eventnamen `DEPLOYED`/`QUESTION`/`MANUAL_ACTION_REQUIRED`/`ERROR`.
  De tekst schrijft "ontbreken of leeg" — bewuste vereenvoudiging conform de story-aanname
  (`questionsAllowed` valt in code alleen bij ontbreken terug).
- AC6: `grep -l "Product Factory" docs/technical/external-systems.md` geeft een treffer; groep
  `## 7. Product Factory` volgt de stijl van de zes bestaande groepen en bevat rol,
  `SF_PRODUCT_FACTORY_TOKEN`, de vier routes en beide verwijzingen. Het bestandspad
  `dashboard/bridge/ProductFactoryIntegrationApi.kt` bestaat en de package klopt. De telzin staat nu
  op 7, consistent met `docs/technical/README.md:11`.
- AC7: `git diff --stat main...HEAD` raakt uitsluitend de vijf scope-bestanden plus dit worklog —
  geen `.kt`, `.yaml`, `.sql`, `.conf` of testbestanden.
- AC8: alle gewijzigde tekst is Nederlands.

Verificatie in de testsandbox: `tools/audit-documentation` → `documentation-audit/v1: PASS`,
exitcode 0. De diff is docs-only en raakt geen `pathPrefixes` van `repository-maven-verify` in
`.factory/verification.yaml`, dus dat commando is per definitie out-of-scope; het volledige vangnet
draait de harness revisiegebonden na deze run. Geen preview-URL/browserdoel voor een docs-only
wijziging, dus geen screenshots.
