# SF-2214 - Worklog

Story-context bij eerste pickup:
Dwing hello-authenticatie af in BridgeHub

In dashboard-backend/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/bridge/BridgeHub.kt: (1) houd een thread-safe verzameling geauthenticeerde sessies bij (ConcurrentHashMap.newKeySet<WebSocketSession>()), gevuld in handleHello pas nadat de bestaande constant-time tokencheck slaagt (check zelf ongewijzigd); (2) plan in afterConnectionEstablished per sessie een timeout-taak op een gedeelde ScheduledExecutorService met daemon-threads (huispatroon: heartbeat-scheduler in softwarefactory/.../bridge/clients/BridgeClient.kt) die de sessie bij niet-authenticatie op warn logt en sluit met CloseStatus.POLICY_VIOLATION in runCatching; de duur is een benoemde constante met toelichting (10s) en overschrijfbaar via een DERDE constructor-parameter met default in de bestaande Duration(amount, unit)-vorm, zodat BridgeHubTest.kt (2 positionele args), BridgeApiControllerTest.kt en ProductFactoryIntegrationApiTest.kt (1 arg) blijven compileren; sluit de scheduler af bij bean-destroy; (3) geef handleTextMessage de sessie door aan handleResponse en handleEvent en verwerk frames van een niet-geauthenticeerde sessie niet (geen pending-completion, geen eventListeners-aanroep), log op warn zonder frame-inhoud of tokenwaarde en sluit met POLICY_VIOLATION; hello blijft vóór authenticatie toegestaan; (4) verwijder sessie + timer in afterConnectionClosed en op de vervangroute in handleHello, waarbij de bestaande identiteitscheck (session == webSocketSession) leidend blijft zodat het sluiten van een niet-geauthenticeerde sessie de actieve factory-state niet raakt. Schrijf zelf de tests in dashboard-backend/src/test/kotlin/nl/vdzon/softwarefactory/dashboard/bridge/BridgeHubTest.kt op basis van startHub(...) en FakeFactory: (a) verbinden zonder hello wordt na de timeout gesloten en isConnected() blijft false (FakeFactory.connect stuurt altijd meteen een hello - voeg een helper toe die alleen de socket opent), (b) een event-frame van een niet-geauthenticeerde sessie bereikt de eventListeners niet; draai met een korte hello-timeout via HubTestConfig, niet met de productiewaarde. Werk docs/ontwerp-bridge-dashboard.md §5 'Transport & authenticatie' (r157-163) bij met het nieuwe timeout- en weiger-gedrag. Raak MAX_TEXT_MESSAGE_BUFFER_BYTES (8 MB) niet aan; setAllowedOrigins/HandshakeInterceptor, nginx limit_conn en heap-/resource-limits blijven buiten scope. Voer zelf een review-stap uit en zorg dat mvn -q -pl dashboard-backend test groen is.

In eigen woorden: een websocket-verbinding op `/bridge` mocht tot nu toe onbeperkt open blijven
zonder zich te authenticeren, en `response`/`event`-frames werden verwerkt zonder te controleren
of die sessie ooit een geldige hello had gestuurd. Deze story dwingt de hello af: geen hello binnen
de time-out → socket dicht, en frames van een niet-geauthenticeerde sessie worden niet verwerkt
maar geweigerd.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `BridgeHub`: `authenticatedSessions` (`ConcurrentHashMap.newKeySet`) wordt pas in `handleHello`
  gevuld nádat de bestaande constant-time tokencheck slaagt; die check zelf is ongewijzigd.
- `afterConnectionEstablished` plant per sessie een hello-timeout op een eigen
  `ScheduledExecutorService` met daemon-threads (huispatroon: heartbeat-scheduler in
  `BridgeClient`). Bij het verlopen zonder authenticatie: `warn` + `close(POLICY_VIOLATION)` in
  `runCatching`. Duur staat als companion-constante `HELLO_TIMEOUT_SECONDS` (10s) en is
  overschrijfbaar via een derde constructor-parameter met default in de bestaande
  `Duration(amount, unit)`-vorm, zodat alle bestaande constructie-plekken blijven compileren.
  Bewust een companion-constante i.p.v. een literal: detekt's MagicNumber slaat companion-properties
  over, dus de quality-ratchet loopt niet op.
- `handleTextMessage` geeft de sessie door aan `handleResponse`/`handleEvent`; zit ze niet in
  `authenticatedSessions`, dan wordt het frame niet verwerkt (geen `pending`-completion, geen
  `eventListeners`-aanroep), wordt alleen het frame-type op `warn` gelogd (geen frame-inhoud, geen
  token) en gaat de socket dicht met `POLICY_VIOLATION`. `hello` blijft vóór authenticatie toegestaan.
- Opruimen via één `forget(session)`-helper (verzameling + timer annuleren), aangeroepen in
  `afterConnectionClosed`, op de vervangroute in `handleHello` en bij een geweigerd token. De
  bestaande identiteitscheck `session == webSocketSession` blijft leidend, zodat het sluiten van een
  niet-geauthenticeerde sessie de actieve factory-state niet raakt. `@PreDestroy shutdown()` sluit
  de scheduler bij bean-destroy.
- Nieuwe tests in `BridgeHubTest`: (a) `een verbinding zonder hello wordt na de hello-time-out
  gesloten` (300ms testtimeout) en (b) `een event van een niet-geauthenticeerde sessie bereikt de
  luisteraars niet`. `FakeFactory` kreeg daarvoor `connectWithoutHello(port)` en `sendRaw(payload)`;
  `connect(...)` hergebruikt die helper. De hello-timeout is per test instelbaar via
  `HubTestConfig.helloTimeoutMsOverride` (default 30s in tests, dus de bestaande tests zijn
  gedragsneutraal).
- Documentatie: `docs/ontwerp-bridge-dashboard.md` §5 "Transport & authenticatie" beschrijft nu ook
  het timeout- en weiger-gedrag. `docs/factory/*` is niet geraakt: development.md/technical-spec.md
  beschrijven build/conventies, niet dit protocoldetail, en die zijn ongewijzigd geldig.
- Buiten scope gelaten zoals afgesproken: `MAX_TEXT_MESSAGE_BUFFER_BYTES` (8 MB) ongewijzigd,
  geen `setAllowedOrigins`/`HandshakeInterceptor`, geen nginx `limit_conn`, geen heap-/resource-limits.

Bewijs:
- `mvn -q -pl dashboard-backend -am test`: BridgeHubTest 10 tests groen (was 8), module totaal
  70 tests, 0 failures/0 errors.
- `mvn verify` vanaf de repo-root: BUILD SUCCESS, exitcode 0, alle modules groen (5m21).
- Quality-ratchet: `mvn -Pquality -pl dashboard-backend detekt:check` geeft 40 findings, precies
  gelijk aan een schone HEAD-worktree (40) — geen nieuwe blocking findings.
