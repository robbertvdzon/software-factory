# SF-2029 - Worklog

Story-context bij eerste pickup:
ADR-0003 schrijven en ADR-0002 verduidelijken

Documentatie-only, uitsluitend onder docs/adr/. 1) Maak docs/adr/0003-product-factory-integratietoken.md volgens docs/adr/template.md: kopregel '# 0003 - <titel>', '- Status: Accepted', '- Datum: <uitvoerdatum JJJJ-MM-DD>', daarna ## Context, ## Decision, ## Consequences in die volgorde. Context: herkomst commit 7d5e0a6 / PR #408 (2026-08-07), klasse ProductFactoryIntegrationApi, pad /api/integrations/v1 met de vier routes GET /status, POST /stories, GET /stories/{storyKey}, POST /stories/{storyKey}/answers, authenticatie via 'Authorization: Bearer <SF_PRODUCT_FACTORY_TOKEN>' in de private authorize()-helper met constante-tijdvergelijking (MessageDigest.isEqual), en verwijzingen naar docs/technical/endpoints.md (Product Factory-integratie) en docs/factory/secrets-local.md. Decision: machine-tot-machine-verkeer van Product Factory is bewust gescheiden van de menselijke dashboardsessie via een apart, minimaal gescopeerd token in plaats van een gedeelde sessie of een gebruikersaccount in de allowlist; scope expliciet beperkt tot /api/integrations/v1, geeft geen dashboardsessie en loopt niet via SF_ALLOWED_EMAILS. Consequences: (a) vierde te beheren secret naast de drie SSO-secrets, met vindplaatsen secrets.env.example, deploy/secrets-cluster.env.example, deploy/seal-secrets.sh en deploy/base/sealed-secret-dashboard.yaml; (b) fail-closed: een blanco/niet-geconfigureerd token geeft onvoorwaardelijk 401, dus een vergeten secret sluit de koppeling in plaats van hem open te zetten; (c) het token draagt geen menselijke identiteit, handelingen zijn niet naar een persoon te herleiden. 2) Pas in docs/adr/0002-google-sso-authenticatie.md alleen de sectie ## Consequences aan: maak duidelijk dat SF_ALLOWED_EMAILS, SF_GOOGLE_CLIENT_ID en SF_DASHBOARD_REMEMBER_SECRET het Google-SSO-loginpad voor menselijke gebruikers betreffen en niet het volledige authenticatie-oppervlak, en verwijs naar ADR-0003 voor het aparte integratiepad. Laat ## Decision inhoudelijk ongewijzigd en laat status Accepted / datum 2026-07-28 staan; ADR-0003 superseedt ADR-0002 niet maar vult die aan. Taal en toon volgen de bestaande ADR's (Nederlands, bestandsnamen en env-vars in backticks). Noem geen tokenwaarden of andere geheimen, alleen env-var-namen en bestandspaden; verkies klasse-/pad-/bestandsnamen boven regelnummers. Wijzig niets onder dashboard-backend/, deploy/, tools/ of secrets.env.example en werk endpoints.md, secrets-local.md of een ADR-index niet bij. Sluit af met een zelfreview: loop de negen acceptatiecriteria langs en controleer via git diff --name-only dat alleen docs/adr/ (plus het story-worklog onder docs/stories/) geraakt is.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Feiten eerst geverifieerd op deze checkout voordat ik ze in de ADR vastlegde:
  `ProductFactoryIntegrationApi.kt` (package `...dashboard.bridge`) mapt op
  `/api/integrations/v1` en heeft precies de vier routes `GET /status`, `POST /stories`,
  `GET /stories/{storyKey}`, `POST /stories/{storyKey}/answers`; de private `authorize()`
  leest `secrets.productFactoryToken` (uit `SF_PRODUCT_FACTORY_TOKEN`, zie `DashboardConfig.kt`)
  en vergelijkt met `MessageDigest.isEqual`, met `expected.isBlank()` als eerste tak — dus
  onvoorwaardelijk `401` zonder geconfigureerd token (fail-closed). `git log` bevestigt
  commit `7d5e0a6` "Add Product Factory story integration API (#408)". De vier vindplaatsen
  van het secret zijn met grep bevestigd: `secrets.env.example`,
  `deploy/secrets-cluster.env.example`, `deploy/seal-secrets.sh`,
  `deploy/base/sealed-secret-dashboard.yaml`.
- Nieuw: `docs/adr/0003-product-factory-integratietoken.md`, exact volgens
  `docs/adr/template.md` (kopregel, `- Status: Accepted`, `- Datum: 2026-08-08`, daarna
  `## Context`, `## Decision`, `## Consequences`). Alleen env-var-namen en bestandspaden,
  geen tokenwaarden.
- Gewijzigd: `docs/adr/0002-google-sso-authenticatie.md`, uitsluitend in `## Consequences`.
  De drie secrets zijn nu expliciet aan het menselijke Google-SSO-loginpad gekoppeld en er
  staat een verwijzing naar ADR-0003 voor het aparte integratiepad. `## Decision`, status en
  datum zijn ongemoeid gelaten; ADR-0003 superseedt ADR-0002 niet maar vult die aan.
- Geen tests toegevoegd: deze story wijzigt uitsluitend markdown onder `docs/adr/`, waar geen
  runtime-gedrag onder hangt. Het volledige vangnet is wel gedraaid als regressiebewijs
  (zie hieronder).
- Specs in `docs/factory/` niet aangepast: die beschrijven het integratiepad al correct
  (`docs/technical/endpoints.md` § Product Factory-integratie en
  `docs/factory/secrets-local.md`), en bijwerken daarvan valt expliciet buiten scope.

Zelfreview tegen de negen acceptatiecriteria: 1-4 afgedekt door de nieuwe ADR; 5 geverifieerd
tegen de code hierboven en bevat geen geheimen; 6-7 afgedekt door de ADR-0002-edit (alleen
`## Consequences` geraakt); 8 gecontroleerd met `git status --porcelain`; 9 klopt: `docs/adr/`
beschrijft nu beide paden en welke secrets bij welk pad horen.

Bewijs vangnet:
- `mvn verify` vanaf de repo-root: BUILD SUCCESS, exitcode 0, alle vijf modules groen
  (softwarefactory incl. e2e/Testcontainers), 0 failures / 0 errors. Totale looptijd 5:16 min,
  afgerond 2026-08-08T14:16:22Z.
- `git status --porcelain` na afloop: alleen `docs/adr/0002-google-sso-authenticatie.md` (M),
  `docs/adr/0003-product-factory-integratietoken.md` (nieuw) en dit worklog. Niets onder
  `dashboard-backend/`, `deploy/`, `tools/` of `secrets.env.example`.

Review (SF-2030, 2026-08-08):
- Volledige story-diff (`git diff main...HEAD`) raakt alleen `docs/adr/0002…`, `docs/adr/0003…`
  en dit worklog — AC8 groen.
- Alle feitelijke claims in ADR-0003 hercontroleerd op deze checkout: package
  `nl.vdzon.softwarefactory.dashboard.bridge`, `@RequestMapping("/api/integrations/v1")`,
  de vier routes, `authorize()` met `expected.isBlank() || !MessageDigest.isEqual(...)` →
  `401 UNAUTHORIZED`, token via `DashboardConfig.optional("SF_PRODUCT_FACTORY_TOKEN")`,
  commit `7d5e0a6` (#408), de vier secret-vindplaatsen, en de secties
  `docs/technical/endpoints.md` § Product Factory-integratie / `docs/factory/secrets-local.md`.
  De claim "geeft geen dashboardsessie / overige bridge-endpoints onbereikbaar" klopt:
  `BridgeApiController` gaat volledig via `authService.requireAuthorization`.
- Geen tokenwaarden of geheimen in de diff; ADR-0002 `## Decision`, status en datum ongewijzigd.
- Gate voor deze docs-only diff: `bash tools/audit-documentation` → `documentation-audit/v1: PASS`.
- Besluit: akkoord, geen blockers.

