# SF-2214 - [Audit] Dwing de bridge-`hello` af: sluit websocket-sessies die zich niet authenticeren

## Story

[Audit] Dwing de bridge-`hello` af: sluit websocket-sessies die zich niet authenticeren

<!-- refined-by-factory -->

## Scope

Dwing in `dashboard-backend` af dat een websocket-verbinding op `/bridge` zich authenticeert vóórdat er iets met haar frames gebeurt. Alle productiewijzigingen zitten in `dashboard-backend/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/bridge/BridgeHub.kt`.

1. **Administratie van geauthenticeerde sessies.** Houd een thread-safe verzameling geauthenticeerde sessies bij (bv. `ConcurrentHashMap.newKeySet<WebSocketSession>()`). Een sessie komt erin in `handleHello` (`:110-125`) pas nádat de bestaande constant-time tokencheck slaagt. De tokencheck zelf blijft ongewijzigd.
2. **Hello-timeout.** Start in `afterConnectionEstablished` (`:96-98`) een timer. Is de sessie na afloop nog niet geauthenticeerd, sluit hem dan met `CloseStatus.POLICY_VIOLATION` en log op `warn`. De duur staat als benoemde constante met toelichting in de code (default 10 seconden) en is via een constructor-parameter met default overschrijfbaar, zodat tests niet echt tien seconden hoeven te wachten — zelfde vorm als het bestaande `requestTimeout: Duration`-patroon (`:40`, `BridgeHub.Duration`, `:156`).
3. **Frames van niet-geauthenticeerde sessies weigeren.** `handleTextMessage` (`:100-108`) geeft de sessie door aan `handleResponse` (`:127-130`) en `handleEvent` (`:132-135`). Zit de sessie niet in de verzameling, dan wordt het frame niet verwerkt (geen `pending`-completion, geen `eventListeners`-aanroep), wordt er op `warn` gelogd en wordt de verbinding gesloten met `CloseStatus.POLICY_VIOLATION`.
4. **Opruimen zonder lekken.** Verwijder de sessie uit de verzameling en annuleer/verwijder haar timer in `afterConnectionClosed` (`:137-145`) én op de vervangroute in `handleHello` (`:118-120`, waar een nieuwe verbinding de oude sluit). De timer-uitvoerder is een eigen `ScheduledExecutorService` met daemon-threads (huispatroon: de heartbeat-scheduler in `softwarefactory/.../bridge/clients/BridgeClient.kt`) en wordt bij bean-destroy afgesloten.
5. **Documentatie.** Werk `docs/ontwerp-bridge-dashboard.md` §5 "Transport & authenticatie" (`:157-163`) bij: naast "token fout → socket dicht" nu ook "geen hello binnen de timeout → socket dicht" en "frames van een niet-geauthenticeerde sessie worden genegeerd en de verbinding wordt gesloten".

Expliciet buiten scope: `MAX_TEXT_MESSAGE_BUFFER_BYTES` (`BridgeWebSocketConfig.kt:30`) blijft op 8 MB; `setAllowedOrigins`/`HandshakeInterceptor` op de handler-registratie; `limit_conn` of andere rate-limiting in `dashboard-frontend/nginx.conf`; heap-instellingen (`-Xmx`/`MaxRAMPercentage`) en resource-limits in `deploy/`.

## Acceptance criteria

1. Een client die verbindt met `/bridge` en géén `hello` stuurt, wordt na de hello-timeout door de backend gesloten met `CloseStatus.POLICY_VIOLATION`; `hub.isConnected()` blijft `false`.
2. Een `event`-frame van een sessie die zich niet (of met een fout token) heeft geauthenticeerd, bereikt de via `addEventListener` geregistreerde luisteraars niet, en de verbinding wordt gesloten.
3. Een `response`-frame van een niet-geauthenticeerde sessie voltooit geen enkele `pending`-future; een lopende `sendRequest` loopt gewoon door tot zijn eigen time-out.
4. Het normale pad is gedragsneutraal: de bestaande tests `accepteert een hello met het correcte token`, `sendRequest geeft een ok-response terug met de body van de factory`, `gelijktijdige sendRequest-aanroepen crashen niet op de gedeelde websocket-sessie` en `een nieuwe verbinding vervangt de oude` blijven ongewijzigd groen.
5. De bestaande test `accepteert een response die groter is dan de oude limiet van twee megabyte` blijft groen, inclusief de assertie `defaultMaxTextMessageBufferSize == 8 * 1024 * 1024`; `MAX_TEXT_MESSAGE_BUFFER_BYTES` is niet gewijzigd.
6. De verzameling geauthenticeerde sessies lekt niet: na het sluiten van een verbinding en na de vervangroute in `handleHello` zit de betreffende sessie er niet meer in. Sluiten van een niet-geauthenticeerde sessie raakt de actieve factory-state (`session`, `connectedSince`, `factoryVersion`, `pending`) niet — de bestaande identiteitscheck in `afterConnectionClosed` blijft leidend.
7. Nieuwe tests in `dashboard-backend/src/test/kotlin/nl/vdzon/softwarefactory/dashboard/bridge/BridgeHubTest.kt`, gebouwd op de bestaande `startHub(...)`-helper en `FakeFactory`: (a) verbinden zonder `hello` → gesloten na de timeout; (b) `event`-frame van een niet-geauthenticeerde sessie bereikt de `eventListeners` niet. De tests draaien met een korte hello-timeout, niet met de productiewaarde.
8. Bestaande constructie-plekken van `BridgeHub` blijven compileren zonder aanpassing: `BridgeHubTest.kt:209` (2 positionele args), `BridgeApiControllerTest.kt:587` en `ProductFactoryIntegrationApiTest.kt:176` (1 positioneel arg) — elke nieuwe constructor-parameter heeft dus een default.
9. `mvn -q -pl dashboard-backend test` is groen en de quality-ratchet loopt niet op (geen nieuwe blocking findings).

## Aannames

- De hello-timeout is 10 seconden in productie. Dat is ruim: `BridgeClient` stuurt de `hello` synchroon in `afterConnectionEstablished`, direct na het openen van de socket.
- Sluiten gebeurt met `CloseStatus.POLICY_VIOLATION`, gelijk aan het bestaande gedrag bij een fout token.
- Sluitpogingen worden defensief in `runCatching` gewikkeld, zoals de bestaande `close`-aanroepen in de klasse.
- De timer wordt per sessie aangemaakt en bij sluiten geannuleerd; de scheduler is één gedeelde `ScheduledExecutorService` met daemon-threads binnen `BridgeHub`.
- Frames van een niet-geauthenticeerde sessie worden op `warn` gelogd zonder frame-inhoud of tokenwaarden in de logregel.
- Ping/pong-frames worden door Tomcat zelf afgehandeld en vallen buiten `handleTextMessage`; het aanmelden-vereiste raakt ze niet.
- Het restrisico van niet-geauthenticeerde verbindingen binnen het timeout-venster (elk goed voor ~16 MiB heap-buffer) blijft bestaan en wordt door deze story alleen begrensd in tijd, niet in aantal. Connection-limiting op nginx-niveau en een expliciete heap-instelling voor de backend zijn aparte stories.

## Eindsamenvatting

## Eindsamenvatting SF-2214 — Bridge-`hello` afdwingen

**Wat er gebouwd is**

Een websocket-verbinding op `/bridge` van de dashboard-backend moest zich tot nu toe niet bewijzen: hij mocht onbeperkt open blijven zonder `hello`, en `response`/`event`-frames werden verwerkt zonder te controleren of die sessie ooit een geldig token had gestuurd. Dat is nu dichtgezet in `dashboard-backend/.../bridge/BridgeHub.kt`:

- **Administratie:** een thread-safe `authenticatedSessions`-verzameling die pas gevuld wordt nádat de bestaande constant-time tokencheck slaagt. Die check zelf is ongewijzigd.
- **Hello-time-out:** per verse verbinding wordt een taak gepland op een eigen daemon-scheduler (huispatroon: de heartbeat in `BridgeClient`). Geen geldige hello binnen de tijd → `warn`-log + `close(POLICY_VIOLATION)`. Duur staat als companion-constante `HELLO_TIMEOUT_SECONDS` (10s) en is via een derde constructor-parameter mét default te overschrijven, zodat tests niet echt tien seconden wachten.
- **Weigeren:** `response`- en `event`-frames van een niet-geauthenticeerde sessie worden niet verwerkt (geen `pending`-completion, geen event-luisteraars), alleen het frame-*type* wordt gelogd — geen frame-inhoud, geen token — en de socket gaat dicht.
- **Opruimen:** één `forget(session)`-helper (verzameling + timer annuleren) op alle uitgangen: `afterConnectionClosed`, de vervangroute in `handleHello` en het geweigerde token. `@PreDestroy` sluit de scheduler. De bestaande identiteitscheck blijft leidend, dus het sluiten van een vreemde sessie raakt de actieve factory-state niet.
- **Documentatie:** `docs/ontwerp-bridge-dashboard.md` §5 beschrijft nu ook het time-out- en weiger-gedrag.

**Keuzes onderweg**

- Companion-constante in plaats van een literal voor de 10 seconden — detekt's `MagicNumber` slaat companion-properties over, dus de quality-ratchet loopt niet op door deze story.
- Nieuwe constructor-parameter met default, zodat de drie bestaande constructie-plekken (`BridgeHubTest`, `BridgeApiControllerTest`, `ProductFactoryIntegrationApiTest`) ongewijzigd blijven compileren.
- `hello` zelf blijft vóór authenticatie toegestaan; sluiten gebeurt overal defensief in `runCatching`, gelijk aan de bestaande stijl.

**Wat er getest is**

- **Twee nieuwe unittests** in `BridgeHubTest` (verbinden zonder hello → gesloten; event van een niet-geauthenticeerde sessie bereikt de luisteraars niet), met een korte test-time-out. `BridgeHubTest` telt nu 10 groene tests (was 8).
- **Gedragsbewijs op de echt draaiende backend** met productie-defaults (10s) en een wegwerp-websocketclient: geen hello → close 1008 na 10,006 s; `event` zonder hello → close 1008 na 0,014 s; `response` zonder hello → close 1008 na 0,004 s; fout token → ongewijzigd 1008; geldige hello + 14 s stil → **geen** close (normale pad gedragsneutraal); een geauthenticeerde sessie naast een stille → alleen de stille gaat dicht.
- **Geen leaks:** heap-histogram na afloop toont 0 `WebSocketSession`-instanties. **Geen secrets in logs:** grep op de gebruikte tokens geeft 0 hits.
- **Vangnet:** `mvn clean verify` vanaf de repo-root → BUILD SUCCESS, 1166 tests, 0 failures/errors/skipped. `tools/audit-documentation` → PASS.

**Bewust niet gedaan**

- `MAX_TEXT_MESSAGE_BUFFER_BYTES` blijft op 8 MB; geen `setAllowedOrigins`/`HandshakeInterceptor`, geen nginx `limit_conn`, geen heap- of resource-limits. Die staan expliciet buiten scope en horen in aparte stories.
- Het aantal gelijktijdige niet-geauthenticeerde verbindingen wordt niet begrensd — deze story begrenst alleen hoe lang zo'n verbinding mag blijven hangen.

**Aandachtspunten (geen blockers)**

- Een *onbekend* frame-type van een niet-geauthenticeerde sessie sluit de socket niet direct; die valt terug op de hello-time-out.
- De quality-ratchet meldt 3 nieuwe findings (`AgentPromptContracts.kt`, `ProductFactoryIntegrationApi.kt`, `AgentRunCompletionService.kt`). Geen daarvan zit in een bestand uit deze story-diff — het is drift van `main`.

<!-- deploy-summary:start -->
Verbindingen met het dashboard moeten zich voortaan meteen aanmelden met een geldige sleutel. Meldt een verbinding zich niet op tijd, of stuurt hij berichten zonder zich te hebben aangemeld, dan wordt hij direct verbroken en worden die berichten genegeerd. Voor het normale gebruik verandert er niets.
<!-- deploy-summary:end -->
