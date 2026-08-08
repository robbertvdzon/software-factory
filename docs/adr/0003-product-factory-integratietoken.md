# 0003 - Apart integratietoken voor de Product Factory-machine-API

- Status: Accepted
- Datum: 2026-08-08

## Context

`dashboard-backend` kende tot voor kort één authenticatiepad: de menselijke
Google-SSO-login uit ADR-0002 (`docs/adr/0002-google-sso-authenticatie.md`), waarbij een
geslaagde login een HMAC-getekend sessietoken oplevert dat de bridge-endpoints accepteren.

Sinds commit `7d5e0a6` ("Add Product Factory story integration API", PR #408, 2026-08-07)
staat daar een tweede, niet-menselijk pad naast. De klasse
`ProductFactoryIntegrationApi` (`dashboard-backend`, package
`nl.vdzon.softwarefactory.dashboard.bridge`) mapt op `/api/integrations/v1` en biedt vier
routes voor Product Factory:

- `GET /status` — bridgeverbinding en factoryversie;
- `POST /stories` — maakt een story aan (met verplichte `Idempotency-Key`);
- `GET /stories/{storyKey}` — status, subtaken en agentvragen van één story;
- `POST /stories/{storyKey}/answers` — antwoord op een agentvraag voor story of subtaak.

Authenticatie op alle vier de routes gebeurt met de header
`Authorization: Bearer <SF_PRODUCT_FACTORY_TOKEN>`, afgehandeld door de private
`authorize()`-helper in diezelfde klasse. Die helper vergelijkt het aangeboden token met de
geconfigureerde waarde in constante tijd (`MessageDigest.isEqual`), zodat de vergelijking geen
timinginformatie over het verwachte token lekt. Het token komt via `DashboardSecrets` uit de
env-var `SF_PRODUCT_FACTORY_TOKEN`.

Het gedrag van dit pad is al beschreven in `docs/technical/endpoints.md`
(§ Product Factory-integratie) en de bijbehorende configuratie in
`docs/factory/secrets-local.md`. Wat ontbrak, was de vastlegging van de *keuze* zelf: wie
alleen `docs/adr/` las, zag uitsluitend het Google-SSO-pad en kreeg daarmee een onvolledig
beeld van het authenticatie-oppervlak van de service.

## Decision

Machine-tot-machine-verkeer van Product Factory naar `dashboard-backend` wordt bewust
gescheiden gehouden van de menselijke dashboardsessie. Dat verkeer authenticeert met een
apart, minimaal gescopeerd token (`SF_PRODUCT_FACTORY_TOKEN`) in plaats van met een gedeelde
sessie of met een gebruikersaccount dat in de allowlist wordt opgenomen.

De scope van dat token is expliciet:

- het geldt uitsluitend voor de routes onder `/api/integrations/v1`;
- het levert geen dashboardsessie op — er wordt geen HMAC-getekend sessietoken uitgegeven en
  de overige bridge-endpoints blijven onbereikbaar met dit token;
- het loopt niet via `SF_ALLOWED_EMAILS`: de allowlist voor menselijke gebruikers heeft geen
  invloed op dit pad, en omgekeerd geeft dit token geen toegang tot het menselijke pad.

ADR-0002 blijft onverkort gelden voor het menselijke loginpad; deze ADR vervangt die niet,
maar vult die aan met het tweede pad.

## Consequences

- Er is een vierde secret te beheren naast de drie SSO-secrets uit ADR-0002:
  `SF_PRODUCT_FACTORY_TOKEN`. Dat secret is daadwerkelijk uitgerold en staat in
  `secrets.env.example`, `deploy/secrets-cluster.env.example`, `deploy/seal-secrets.sh` en als
  sealed secret in `deploy/base/sealed-secret-dashboard.yaml`. Roteren raakt dus zowel de
  lokale als de clusterconfiguratie.
- Het pad is fail-closed: is het token niet geconfigureerd (blanco waarde), dan faalt
  `authorize()` onvoorwaardelijk met `401` — ook bij een verder correct verzoek. Een vergeten
  of leeg secret toont zich daardoor als een storing van de koppeling en nooit als een open
  deur.
- Het token draagt geen menselijke identiteit. Handelingen via `/api/integrations/v1` zijn
  daarom niet naar een persoon te herleiden, alleen naar "Product Factory". Wie audit op
  persoonsniveau wil, moet dat aan de Product Factory-kant oplossen.
