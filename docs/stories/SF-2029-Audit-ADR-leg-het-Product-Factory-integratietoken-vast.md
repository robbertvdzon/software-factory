# SF-2029 - [Audit] ADR: leg het Product Factory-integratietoken vast als besluit (ADR-0003)

## Story

[Audit] ADR: leg het Product Factory-integratietoken vast als besluit (ADR-0003)

<!-- refined-by-factory -->

## Samenvatting

Het dashboard is op twee manieren te benaderen: mensen loggen in met hun Google-account, en Product Factory praat als machine met de factory via een eigen sleutel. In onze map met vastgelegde besluiten staat alleen die eerste manier beschreven. Wie daar leest, krijgt dus een onvolledig beeld.

Er is niets mis met hoe het werkt en er verandert niets aan de software. We schrijven alleen het tweede pad alsnog op als besluit, en maken het bestaande besluit over de Google-login zo helder dat het niet langer de indruk wekt de enige ingang te beschrijven.

## Scope

Uitsluitend documentatie in `docs/adr/`. Geen wijzigingen aan code, tests, configuratie, secrets of deploy-manifesten.

**1. Nieuw: `docs/adr/0003-product-factory-integratietoken.md`**

Volgt `docs/adr/template.md` (kopregel `# 0003 - <titel>`, daarna `- Status:` en `- Datum:`, en de secties `## Context`, `## Decision`, `## Consequences`). Status `Accepted`, datum de dag waarop de wijziging gemaakt wordt.

Inhoudelijk vast te leggen:

- *Context*: sinds commit `7d5e0a6` ("Add Product Factory story integration API", #408, 2026-08-07) biedt `dashboard-backend` naast het menselijke SSO-pad een machine-API. `ProductFactoryIntegrationApi.kt` mapt op `/api/integrations/v1` en bevat vier routes: `GET /status`, `POST /stories`, `GET /stories/{storyKey}`, `POST /stories/{storyKey}/answers`. Authenticatie gebeurt met `Authorization: Bearer <SF_PRODUCT_FACTORY_TOKEN>` via de private `authorize()`-helper, met een constante-tijdvergelijking (`MessageDigest.isEqual`). Verwijs naar de bestaande beschrijvingen in `docs/technical/endpoints.md` (§ Product Factory-integratie) en `docs/factory/secrets-local.md`.
- *Decision*: machine-tot-machine-verkeer van Product Factory wordt bewust gescheiden van de menselijke dashboardsessie, via een apart, minimaal gescopeerd token in plaats van een gedeelde sessie of een gebruikersaccount in de allowlist. Scope expliciet: geldt uitsluitend voor `/api/integrations/v1`, geeft geen dashboardsessie en loopt niet via `SF_ALLOWED_EMAILS`.
- *Consequences*: (a) er is een vierde secret te beheren naast de drie SSO-secrets — `SF_PRODUCT_FACTORY_TOKEN`, aanwezig in `secrets.env.example`, `deploy/secrets-cluster.env.example`, `deploy/seal-secrets.sh` en als sealed secret in `deploy/base/sealed-secret-dashboard.yaml`, dus daadwerkelijk uitgerold; (b) een niet-geconfigureerd (blanco) token houdt de koppeling dicht in plaats van open — elke aanroep krijgt dan onvoorwaardelijk `401`, wat betekent dat een vergeten secret zich als storing van de koppeling toont en niet als open deur; (c) het token geeft geen menselijke identiteit, dus handelingen via dit pad zijn niet naar een persoon te herleiden.

**2. Wijziging: `docs/adr/0002-google-sso-authenticatie.md`**

- In `## Consequences` verduidelijken dat de drie genoemde env-vars/secrets (`SF_ALLOWED_EMAILS`, `SF_GOOGLE_CLIENT_ID`, `SF_DASHBOARD_REMEMBER_SECRET`) het Google-SSO-loginpad voor menselijke gebruikers betreffen, en niet het volledige authenticatie-oppervlak van de service.
- In diezelfde sectie een verwijzing opnemen naar ADR-0003 voor het aparte machine-tot-machine-integratiepad.
- De sectie `## Decision` van ADR-0002 blijft inhoudelijk ongewijzigd; alleen als er een scope-verduidelijking nodig is dat het over *gebruikers* gaat, mag dat zonder de gemaakte keuze te wijzigen. Status en datum van ADR-0002 blijven staan.

**Buiten scope**

- Elke gedragswijziging aan de integratie-API of aan het Google-SSO-pad.
- Wijzigingen aan `ProductFactoryIntegrationApi.kt`, `AuthService`, tests, secrets-bestanden of deploy-manifesten.
- Bijwerken van `docs/technical/endpoints.md` of `docs/factory/secrets-local.md`: die beschrijven het pad al correct.
- Aanmaken van een index/README in `docs/adr/` (die bestaat nu niet en is niet nodig).

## Acceptance criteria

1. `docs/adr/0003-product-factory-integratietoken.md` bestaat en volgt de structuur van `docs/adr/template.md`: kopregel met nummer en titel, `- Status: Accepted`, `- Datum:` met de datum van de wijziging in `JJJJ-MM-DD`, en de secties `## Context`, `## Decision`, `## Consequences` in die volgorde.
2. De Context van ADR-0003 benoemt het pad `/api/integrations/v1`, alle vier de routes (`GET /status`, `POST /stories`, `GET /stories/{storyKey}`, `POST /stories/{storyKey}/answers`), de klasse `ProductFactoryIntegrationApi` en de herkomst-commit `7d5e0a6` / PR #408.
3. De Decision van ADR-0003 legt vast dat het machine-verkeer bewust van de menselijke dashboardsessie gescheiden is via een apart, minimaal gescopeerd token, en stelt expliciet dat dit pad geen dashboardsessie geeft en niet door `SF_ALLOWED_EMAILS` wordt beperkt.
4. De Consequences van ADR-0003 noemen zowel het beheer van `SF_PRODUCT_FACTORY_TOKEN` als vierde secret (met de plekken waar het staat) als het fail-closed-gedrag: zonder geconfigureerd token is de koppeling dicht (401), niet open.
5. ADR-0003 bevat geen bewering die op deze checkout onjuist is, en bevat geen tokenwaarden of andere geheimen — alleen namen van env-vars en bestandspaden.
6. In `docs/adr/0002-google-sso-authenticatie.md` maakt de sectie `## Consequences` duidelijk dat de drie genoemde secrets bij het Google-SSO-loginpad voor menselijke gebruikers horen, en verwijst die sectie naar ADR-0003 voor het aparte integratiepad.
7. De sectie `## Decision` van ADR-0002 is inhoudelijk ongewijzigd: er is geen keuze toegevoegd, verwijderd of omgedraaid.
8. `git diff` van de story raakt uitsluitend bestanden onder `docs/adr/` (plus het gebruikelijke story-worklog onder `docs/stories/`). Geen enkel bestand onder `dashboard-backend/`, `deploy/`, `tools/` of `secrets.env.example` is gewijzigd.
9. Een lezer die alleen `docs/adr/` leest, kan beide authenticatiepaden van `dashboard-backend` benoemen (Google-SSO voor mensen, integratietoken voor Product Factory) en weet welk pad welke secrets gebruikt.

## Aannames

- "Datum van de wijziging" is de dag waarop de ADR geschreven wordt; op deze checkout is dat 2026-08-08. Wijkt de uitvoerdatum af, dan die datum gebruiken.
- Taal en toon volgen de bestaande ADR's: Nederlands, met bestandsnamen en env-vars in backticks.
- ADR-0002 blijft `Status: Accepted` met datum `2026-07-28`; ADR-0003 vervangt of superseedt ADR-0002 niet, maar vult die aan — de twee ADR's beschrijven verschillende paden.
- Verificatie is puur documentair (lezen van de diff); er is geen build of test die deze bestanden dekt, dus een groene testrun is geen onderdeel van de acceptatie. Bestaande verify/quality-gates moeten wel groen blijven, wat vanzelf volgt uit het feit dat er geen code wijzigt.
- De naam `docs/adr/0003-product-factory-integratietoken.md` uit de issue wordt letterlijk aangehouden.

## Eindsamenvatting

Alles gecontroleerd: story-diff, beide ADR's, worklog en de feitelijke claims tegen de code. Hier de eindsamenvatting.

---

# SF-2029 — ADR-0003: Product Factory-integratietoken vastgelegd

## Wat is gebouwd

Documentatie-only story, uitsluitend onder `docs/adr/`. Geen code-, test-, config-, secrets- of deploy-wijzigingen.

**Nieuw — `docs/adr/0003-product-factory-integratietoken.md`** (Status `Accepted`, datum 2026-08-08), volgens `docs/adr/template.md`:
- *Context*: het tweede, niet-menselijke authenticatiepad van `dashboard-backend` sinds commit `7d5e0a6` / PR #408 — klasse `ProductFactoryIntegrationApi` op `/api/integrations/v1` met de vier routes `GET /status`, `POST /stories`, `GET /stories/{storyKey}`, `POST /stories/{storyKey}/answers`, geautoriseerd via een Bearer-token met constante-tijdvergelijking. Met verwijzingen naar de bestaande beschrijvingen in `docs/technical/endpoints.md` en `docs/factory/secrets-local.md`.
- *Decision*: machineverkeer is bewust gescheiden van de menselijke dashboardsessie via een apart, minimaal gescopeerd token; het geldt alleen voor `/api/integrations/v1`, geeft geen dashboardsessie en loopt niet via `SF_ALLOWED_EMAILS`.
- *Consequences*: vierde te beheren secret naast de drie SSO-secrets (met de vier vindplaatsen, incl. sealed secret); fail-closed gedrag (blanco token ⇒ onvoorwaardelijk `401`); geen menselijke identiteit, dus geen audit op persoonsniveau via dit pad.

**Gewijzigd — `docs/adr/0002-google-sso-authenticatie.md`**: alleen binnen `## Consequences`. De drie SSO-secrets zijn nu expliciet aan het menselijke loginpad gekoppeld ("niet het volledige authenticatie-oppervlak") en er staat een verwijzing naar ADR-0003. `## Decision`, status en datum zijn ongemoeid.

## Gemaakte keuzes

- ADR-0003 **superseedt** ADR-0002 niet maar **vult aan**: de twee beschrijven verschillende paden, dus ADR-0002 blijft `Accepted` met datum 2026-07-28.
- Alle feitelijke beweringen zijn eerst op deze checkout geverifieerd (routes, package, authorize-logica, herkomst-commit, de vier secret-vindplaatsen) vóórdat ze zijn vastgelegd.
- Uitsluitend env-var-namen en bestandspaden opgenomen; geen tokenwaarden of andere geheimen.
- Klasse-, pad- en bestandsnamen boven regelnummers, zodat de ADR niet verouderd raakt bij refactors.

## Wat is getest

- Volledige `mvn verify` vanaf repo-root als regressiebewijs: BUILD SUCCESS, alle vijf modules groen, 0 failures / 0 errors (~5:16 min).
- Documentatiegate `tools/audit-documentation`: `documentation-audit/v1: PASS` (exitcode 0) — dit is de enige gate die op een docs-only diff matcht.
- Alle negen acceptatiecriteria expliciet nagelopen door zowel reviewer als tester, inclusief hercontrole van de fail-closed-claim en de claim dat dit token geen dashboardsessie geeft (bevestigd: elk bridge-endpoint loopt via `AuthService.requireAuthorization`).
- Diff-afbakening machinaal gecontroleerd: alleen de twee ADR-bestanden plus het story-worklog. Niets onder `dashboard-backend/`, `deploy/`, `tools/` of `secrets.env.example`.

## Bewust niet gedaan

- Geen enkele gedragswijziging aan de integratie-API of het Google-SSO-pad — dit is een audit-story die vastlegt wat al bestaat.
- `docs/technical/endpoints.md` en `docs/factory/secrets-local.md` niet bijgewerkt: die beschrijven het pad al correct (expliciet buiten scope).
- Geen index/README in `docs/adr/` aangemaakt (bestaat niet, niet nodig).
- Geen nieuwe geautomatiseerde tests: er hangt geen runtime-gedrag onder deze markdown-bestanden; verificatie is documentair.
- Geen screenshots: geen preview-context en niet van toepassing op een documentatiewijziging.

## Aandachtspunt voor de PO

De ADR legt vast dat handelingen via het integratiepad niet naar een persoon te herleiden zijn. Dat is een bewuste, nu expliciete consequentie — wil je ooit audit op persoonsniveau, dan moet dat aan de Product Factory-kant worden opgelost.

<!-- deploy-summary:start -->
De vastgelegde besluiten over hoe er ingelogd wordt op het dashboard zijn nu compleet. Naast de manier waarop mensen inloggen staat nu ook beschreven hoe het andere systeem automatisch verbinding maakt, en bij welke van de twee welke instellingen horen. Aan de werking van het dashboard zelf verandert niets.
<!-- deploy-summary:end -->
