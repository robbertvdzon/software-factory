# 0004 - Gedeeld bridge-token voor de websocketverbinding van de factory

- Status: Accepted
- Datum: 2026-08-20

## Context

`dashboard-backend` haalt zelf geen data op: alles wat het dashboard toont komt binnen
over één websocketverbinding met de factory-orchestrator. `BridgeWebSocketConfig`
(`dashboard-backend`, package `nl.vdzon.softwarefactory.dashboard.bridge`) registreert
daarvoor `BridgeHub` op het pad `/bridge`. De richting van die verbinding is bewust
omgekeerd ten opzichte van de datastroom: de orchestrator verbindt uitgaand naar de
backend, niet andersom, zodat de factory zelf geen inkomend bereikbaar eindpunt hoeft te
zijn.

Het eerste frame dat de factory over die verbinding stuurt is een **hello**:

```json
{"type":"hello","token":"<SF_BRIDGE_TOKEN>","protocolVersion":1,"factoryVersion":"<git-sha>"}
```

`BridgeHub.handleHello` vergelijkt het aangeboden token met de geconfigureerde waarde uit
`DashboardSecrets` in constante tijd (`MessageDigest.isEqual`) — hetzelfde patroon als
`AuthService` en `ProductFactoryIntegrationApi.authorize` gebruiken — en weigert de hello
bij een blanco of onjuist token. Pas na een geslaagde hello staat de sessie in
`authenticatedSessions` en geldt ze als "de" factory-verbinding.

Dit transport was al beschreven in `docs/ontwerp-bridge-dashboard.md` §5 ("Transport &
authenticatie") en in de rotatie-instructies van `runbook.md`, maar niet als *besluit*
vastgelegd. Wie alleen `docs/adr/` las, zag het menselijke Google-SSO-pad (ADR-0002) en het
Product Factory-integratietoken (ADR-0003), en daarmee een onvolledig beeld van het
authenticatie-oppervlak van de service — terwijl juist over dit derde pad alle
dashboarddata loopt.

## Decision

Het transport tussen de factory-orchestrator en `dashboard-backend` authenticeert met een
eigen gedeeld token (`SF_BRIDGE_TOKEN`), aangeboden op het hello-frame van de
websocketverbinding. Dat pad staat los van de twee eerder vastgelegde paden:

- het is niet de menselijke Google-sessie uit ADR-0002
  (`docs/adr/0002-google-sso-authenticatie.md`): er komt geen ID-token en geen
  HMAC-getekend sessietoken aan te pas, en `SF_ALLOWED_EMAILS` speelt hier geen rol;
- het is niet het Product Factory-integratietoken uit ADR-0003
  (`docs/adr/0003-product-factory-integratietoken.md`): dat geldt voor de REST-routes
  onder `/api/integrations/v1`, dit token uitsluitend voor de websocket op `/bridge`;
- het bridge-token geeft geen toegang tot de REST-endpoints van de service, en omgekeerd
  geeft geen van de andere tokens toegang tot de bridge-websocket.

ADR-0002 en ADR-0003 blijven onverkort gelden; deze ADR vervangt die niet, maar beschrijft
het derde, laatste authenticatiepad van `dashboard-backend`.

## Consequences

- Er is een vijfde secret te beheren, naast de drie SSO-secrets uit ADR-0002 en
  `SF_PRODUCT_FACTORY_TOKEN` uit ADR-0003: `SF_BRIDGE_TOKEN`. Het staat in
  `secrets.env.example` (r53), in `docker/docker-compose.yml` (r33), in de
  hersegel-allowlist van `deploy/seal-secrets.sh` (r71) en als sealed secret in
  `deploy/base/sealed-secret-dashboard.yaml` (r10). Roteren raakt dus zowel de laptop als
  het cluster; dat risico stond al benoemd in `docs/ontwerp-bridge-dashboard.md` (r351).
- Het pad is fail-closed: is het token niet geconfigureerd (blanco waarde), dan wordt de
  hello onvoorwaardelijk geweigerd — ook als de factory een verder correct frame stuurt.
  Een vergeten of leeg secret toont zich daardoor als een verbroken bridge en nooit als een
  open deur.
- Sinds SF-2214 wordt de hello ook afgedwongen in plaats van alleen gecontroleerd wanneer
  hij komt. Blijft een geldige hello binnen de time-out uit (10s in productie,
  `HELLO_TIMEOUT_SECONDS`; voor tests overschrijfbaar via een constructor-parameter van
  `BridgeHub`), dan sluit de backend de socket met `POLICY_VIOLATION`. `response`- en
  `event`-frames van een sessie die zich niet (of met een fout token) geauthenticeerd
  heeft, worden niet verwerkt — geen `pending`-completion en geen event-luisteraars — en
  sluiten de verbinding eveneens met `POLICY_VIOLATION`.
- Het token draagt geen menselijke identiteit. Handelingen die over de bridge lopen zijn
  daarom niet naar een persoon te herleiden, alleen naar "de factory". Wie audit op
  persoonsniveau wil, moet dat aan de orchestratorkant oplossen.
