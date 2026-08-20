# SF-2235 - Worklog

Story-context bij eerste pickup:
ADR-0004 bridge-websocket-token toevoegen en ADR-0002 corrigeren

Documentatiewerk, uitsluitend in docs/adr/ (worklog uitgezonderd). Geen code wijzigen.

1. Maak docs/adr/0004-bridge-websocket-token.md volgens docs/adr/template.md en in de stijl/taal van ADR-0003 (Nederlands): '# 0004 - <titel>', '- Status: Accepted', '- Datum: 2026-08-20', daarna ## Context / ## Decision / ## Consequences.
   - Context: BridgeWebSocketConfig registreert BridgeHub op /bridge; de factory-orchestrator verbindt daar uitgaand naartoe (niet andersom). Eerste frame is een hello: {"type":"hello","token":"<SF_BRIDGE_TOKEN>","protocolVersion":1,"factoryVersion":"<git-sha>"}. BridgeHub.handleHello vergelijkt het token in constante tijd (MessageDigest.isEqual, zelfde patroon als AuthService en ProductFactoryIntegrationApi.authorize) en weigert bij blanco of onjuist token. Via deze ene verbinding loopt alle data die het dashboard toont. Bronnen: dashboard-backend/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/bridge/BridgeHub.kt (handleHello, authenticatedSessions, hello-timeout), BridgeWebSocketConfig.kt, docs/ontwerp-bridge-dashboard.md §5 (r154-169), runbook.md:101,263.
   - Decision: het transport orchestrator -> dashboard-backend authenticeert met een eigen gedeeld token (SF_BRIDGE_TOKEN) op de hello, los van de menselijke Google-sessie (ADR-0002) en het Product Factory-integratietoken (ADR-0003); SF_ALLOWED_EMAILS speelt geen rol en dit token geeft geen toegang tot de REST-endpoints.
   - Consequences: vijfde te beheren secret naast de drie SSO-secrets (ADR-0002) en SF_PRODUCT_FACTORY_TOKEN (ADR-0003): secrets.env.example:53, docker/docker-compose.yml:33, deploy/seal-secrets.sh:71, deploy/base/sealed-secret-dashboard.yaml:10 (staat NIET in deploy/secrets-cluster.env.example, dus die plek niet noemen); rotatie raakt laptop en cluster (docs/ontwerp-bridge-dashboard.md:351). Fail-closed: blanco token -> hello geweigerd. Sinds SF-2214: geen geldige hello binnen de time-out (10s, HELLO_TIMEOUT_SECONDS) -> socket dicht met POLICY_VIOLATION; response-/event-frames van een niet-geauthenticeerde sessie worden niet verwerkt en sluiten de socket. Het token draagt geen menselijke identiteit; handelingen over de bridge zijn niet naar een persoon te herleiden.

2. Voeg in de Consequences van docs/adr/0002-google-sso-authenticatie.md een bullet toe die naar ADR-0004 (docs/adr/0004-bridge-websocket-token.md) verwijst, in dezelfde vorm als de bestaande bullet naar ADR-0003, zodat de drie authenticatiepaden vanuit ADR-0002 vindbaar zijn.

3. Corrigeer in ADR-0002 de onjuiste toeschrijving van email_verified: haal de bullet 'en dat de email_verified-claim true is' uit het lijstje van NimbusGoogleIdTokenVerifier (die leest de claim alleen, GoogleIdTokenVerifier.kt:71-72, en geeft hem door in GoogleIdentity) en verwerk de weigering in de alinea over AuthService (AuthService.kt:35, naast de SF_ALLOWED_EMAILS-check). Puur tekstcorrectie; gedrag verandert niet. Let op: beide bestanden staan in .../dashboard/api/, niet in een auth/-map.

4. Optioneel: als 'de overige bridge-endpoints blijven onbereikbaar met dit token' in ADR-0003 verwarrend wordt naast ADR-0004, maak er 'bridge-endpoints (de REST-routes in BridgeApiController)' van. Geen acceptatiecriterium.

Buiten scope: elke Kotlin-wijziging (BridgeHub.kt, BridgeWebSocketConfig.kt e.a.), de SF_ALLOWED_EMAILS-default in docker/docker-compose.yml:29, ADR-0001's Consequences, en een ADR voor BearerTokenAuthorizer/SF_FACTORY_API_TOKEN. Schrijf geen unittests: er is geen gedrag om te testen.

Afronden: review je eigen diff; git diff --stat mag uitsluitend docs/adr/ (+ docs/stories/worklog/) tonen, en tools/verify-repository moet groen zijn.

Stappenplan:
[x]: read issue and target docs
[x]: bronnen verifiëren in de code (BridgeHub/BridgeWebSocketConfig/AuthService/GoogleIdTokenVerifier + secretplekken)
[x]: docs/adr/0004-bridge-websocket-token.md schrijven
[x]: ADR-0002 aanvullen met een ADR-0004-bullet en de email_verified-toeschrijving corrigeren
[x]: optionele verduidelijking in ADR-0003
[x]: run relevant tests (volledige vangnet)
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- **ADR-0004 (nieuw, `docs/adr/0004-bridge-websocket-token.md`)**: structuur van `template.md`,
  stijl/taal van ADR-0003, `Status: Accepted`, `Datum: 2026-08-20`. Alle feiten zijn eerst in de
  code nagelopen en niets is verzonnen:
  - `/bridge` + uitgaande richting: `BridgeWebSocketConfig.registerWebSocketHandlers` en de
    KDoc van `BridgeHub`.
  - hello-frame, constante-tijdvergelijking (`MessageDigest.isEqual` via `constantTimeEquals`),
    fail-closed bij blanco token: `BridgeHub.handleHello` (r159-166 — de blanco-check
    `secrets.bridgeToken.isBlank()` staat er letterlijk).
  - SF-2214-gedrag: `afterConnectionEstablished`/`closeIfStillUnauthenticated` +
    `rejectUnauthenticated` in `handleResponse`/`handleEvent`; time-out
    `HELLO_TIMEOUT_SECONDS = 10L` (BridgeHub.kt r232), overschrijfbaar via de
    constructor-parameter `helloTimeout`.
  - Secretplekken machinaal gecontroleerd met grep: `secrets.env.example:53`,
    `docker/docker-compose.yml:33`, `deploy/seal-secrets.sh:71`,
    `deploy/base/sealed-secret-dashboard.yaml:10`. `SF_BRIDGE_TOKEN` komt inderdaad **niet** voor
    in `deploy/secrets-cluster.env.example`, dus die plek staat er niet bij (anders dan in ADR-0003).
- **ADR-0002 punt 2**: bullet toegevoegd in de `Consequences`, in dezelfde vorm als de bestaande
  ADR-0003-bullet, zodat de drie authenticatiepaden vanuit ADR-0002 vindbaar zijn.
- **ADR-0002 punt 3 (`email_verified`)**: de bullet is uit het `NimbusGoogleIdTokenVerifier`-lijstje
  gehaald en de weigering is in de `AuthService`-alinea verwerkt, naast de `SF_ALLOWED_EMAILS`-check.
  Klopt met de code: `GoogleIdTokenVerifier.kt:71-72` leest de claim alleen
  (`claims.getBooleanClaim("email_verified") ?: false`) en geeft die door in `GoogleIdentity`;
  `AuthService.kt:35` gooit de 401 bij `!identity.emailVerified`. Puur tekst, geen gedragswijziging.
- **ADR-0003 punt 4 (optioneel, uitgevoerd)**: "de overige bridge-endpoints" is
  "de overige bridge-endpoints (de REST-routes in `BridgeApiController`)" geworden, omdat "bridge"
  naast ADR-0004 anders twee dingen kan betekenen (de websocket én de REST-routes).
- Geen tests geschreven: de wijziging is uitsluitend documentatie, er is geen gedrag om te testen
  (staat ook zo in de story). Geen `docs/factory/`-spec geraakt: het bridge-tokengedrag zelf
  verandert niet en stond al in `docs/ontwerp-bridge-dashboard.md` §5.

Bewijs vangnet (20-08-2026, `tools/verify-repository`, alle stappen los nagelopen):
- `repository-contract-tests`: exit 0.
- `repository-maven-verify` (`mvn clean verify` vanaf root): **BUILD SUCCESS**, exit 0, 0 failures /
  0 errors, alle vijf modules SUCCESS, totaal 05:22 min.
- `repository-quality-ratchet`: **exit 1** — `ok:false`, findingCount 777, drie `new`-bevindingen op
  `agentworker/.../AgentPromptContracts.kt` (TooManyFunctions),
  `dashboard-backend/.../ProductFactoryIntegrationApi.kt` (CyclomaticComplexMethod) en
  `softwarefactory/.../AgentRunCompletionService.kt` (LargeClass). Dit is **pre-existent op `main`**
  en niet door deze story veroorzaakt: `git diff --stat main` toont uitsluitend `docs/adr/`, deze
  branch bevat nul Kotlin-wijzigingen, dus detekt draait op exact dezelfde broncode als main. Het is
  drift ten opzichte van de verouderde `quality/baselines/plan-07-ratchet.json`. De ratchet zit
  bewust niet in `.factory/verification.yaml` en niet in de CI-verify-workflow, dus hij blokkeert de
  merge niet. Repareren zou Kotlin-refactors in drie ongerelateerde klassen vragen — expliciet buiten
  scope van deze docs-only story en niet klein/risicoloos — en de baseline oprekken is volgens
  `docs/verbeterplan-soepele-stories-2026-07.md` juist niet de bedoeling. Daarom gerapporteerd i.p.v.
  stilzwijgend opgelost.
- Omdat de gate na de ratchet afbreekt (`set -e`), zijn de resterende stappen handmatig gedraaid:
  `tools/generate-module-dependencies --check` (actueel), `flutter pub get` (exit 0),
  `flutter analyze` ("No issues found!"), `flutter test` (167 tests, all passed),
  `docker/test-prepare-mini-reactor.sh` (PASS), `tools/audit-documentation`
  (`documentation-audit/v1: PASS`). Alleen `agent-image-build-stage` is niet gedraaid: er is geen
  docker-CLI in de agent-container en dat commando staat in `.factory/verification.yaml` bewust op
  `agentRunnable: false`.
- `git status --short` bevat alleen `docs/adr/0002`, `docs/adr/0003`, `docs/adr/0004` (nieuw) en dit
  worklog — geen productie- of testcode aangeraakt.

## Testronde SF-2237 (tester, 2026-08-20)

Docs-only story; geen preview-deploy voor deze repo. Verificatie bestond uit (a) het
vangnet en (b) een bronnencheck van elke feitelijke claim in de nieuwe/gewijzigde ADR's
tegen de code en config waarnaar ze verwijzen.

**Scope-check (AC6)** — `git diff --name-status main...HEAD`: alleen
`docs/adr/0002-...md` (M), `docs/adr/0003-...md` (M), `docs/adr/0004-...md` (A) en dit
worklog (A). Nul productie-/testcode. OK.

**Bronnencheck (AC1-AC5)** — alle geverifieerd tegen de checkout:
- `BridgeWebSocketConfig.kt:15` registreert de handler op `/bridge`. OK.
- `BridgeHub.handleHello` (r159-167): weigert bij `secrets.bridgeToken.isBlank()` of
  ongelijk token, sluit met `CloseStatus.POLICY_VIOLATION`, zet de sessie pas daarna in
  `authenticatedSessions`. `constantTimeEquals` gebruikt `MessageDigest.isEqual` (r215).
  Fail-closed-claim en constante-tijdclaim kloppen.
- `HELLO_TIMEOUT_SECONDS = 10L` (r232) en de timeout is via een constructor-parameter
  (`helloTimeout`, r45) overschrijfbaar — precies zoals ADR-0004 stelt.
- `handleResponse`/`handleEvent` (r180-196) verwerken niets bij een niet-geauthenticeerde
  sessie en gaan via `rejectUnauthenticated` naar `POLICY_VIOLATION`. OK.
- Secret-vindplaatsen letterlijk gecontroleerd: `secrets.env.example:53`,
  `docker/docker-compose.yml:33`, `deploy/seal-secrets.sh:71`,
  `deploy/base/sealed-secret-dashboard.yaml:10`, `docs/ontwerp-bridge-dashboard.md:351`.
  Alle vijf raak.
- `email_verified`: `GoogleIdTokenVerifier.kt:71-72` leest de claim alleen en geeft hem
  door in `GoogleIdentity`; `AuthService.kt:35` weigert erop, r40 doet `SF_ALLOWED_EMAILS`.
  De ADR-0002-tekst schrijft dit nu correct toe en de bullet staat niet meer in het
  verifier-lijstje. OK.
- `BridgeApiController.kt` bestaat in `.../dashboard/bridge/`, dus de verduidelijking in
  ADR-0003 klopt. Structuur van ADR-0004 volgt `template.md` (Status/Datum/Context/
  Decision/Consequences, `Status: Accepted`, datum 2026-08-20). OK.

**Vangnet** — `tools/verify-repository` zelf gedraaid, tot het einde:
- `repository-contract-tests` exit 0
- `repository-maven-verify` (`mvn -B --no-transfer-progress clean verify`) exit 0,
  BUILD SUCCESS, 869 + 92 + 64 + 70 tests, 0 failures, 0 errors, 0 skipped
- `repository-quality-ratchet` (`./quality/run.sh`) **exit 1**, `ok: false`, 3 `new`
  detekt-findings; script breekt daar af (`set -e`)
- Daarna handmatig: `tools/generate-module-dependencies --check` exit 0 en
  `tools/audit-documentation` exit 0 (`documentation-audit/v1: PASS`)

**Ratchet-bevinding (AC7)** — de drie `new` findings zitten in
`AgentPromptContracts.kt` (TooManyFunctions), `ProductFactoryIntegrationApi.kt`
(CyclomaticComplexMethod) en `AgentRunCompletionService.kt` (LargeClass). Zelf
nagetrokken: alle drie zijn op `main` voor het laatst gewijzigd (18ce72a resp. e7031cb),
deze branch bevat nul Kotlin-wijzigingen, en de baseline
`quality/baselines/plan-07-ratchet.json` is sinds 984321d niet meer bijgewerkt. Het is dus
pre-existente baseline-drift op `main`, niet door deze story veroorzaakt — de
developerbevinding is bevestigd. De ratchet staat bovendien niet in
`.factory/verification.yaml` en draait sinds 2026-07-24 bewust niet meer in GitHub Actions
(zie de toelichting in `.github/workflows/verify.yml:67-73`), dus hij blokkeert de merge
niet. AC7 ("`tools/verify-repository` blijft groen") is daardoor letterlijk niet gehaald,
maar was al rood vóór deze story en is binnen een docs-only scope niet te halen. Daarom
teruggelegd als vraag aan de PO in plaats van als developer-bug.
