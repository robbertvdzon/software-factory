# HTTP endpoints

Er zijn 6 HTTP endpoints in de `softwarefactory`-module (geteld op de mapping-annotaties in
`web/controllers/`). De aparte `dashboard-backend`-module heeft haar eigen JSON-API en valt buiten
deze lijst. Het voormalige HTML-dashboard (33 endpoints in `FactoryDashboardController`) is
verwijderd (SF-825); de Flutter-frontend verzorgt nu de UI.

## Publieke API (`web/controllers/FactoryApiController.kt`, prefix `/api`)

| Methode | Pad | Doel |
| --- | --- | --- |
| GET | `/api/version` | Versie-info (commit, branch, starttijd); publiek, geen auth. |
| POST | `/api/restart` | Factory-herstart; vereist Bearer-token `SF_FACTORY_API_TOKEN`. |

## Agent completion endpoint (`web/controllers/AgentRunCompletionController.kt`)

| Methode | Pad | Doel |
| --- | --- | --- |
| POST | `/agent-run/complete` | Compatibility endpoint om outcome, usage, events en optionele rate-limitinformatie te verwerken. De Docker-agent gebruikt primair `/work/agent-result.json`. |

Het completioncontract accepteert additief `rateLimit: {status, resetsAt, overageResetsAt}`;
timestamps zijn Unix-seconden. Alleen bij een mislukte run is een blokkerende status een
quotasignaal; quota-specifieke outcome-/samenvattingstekst kan dezelfde wachtstand activeren.
Oudere callers mogen `rateLimit` weglaten.

## Agent knowledge endpoints (`web/controllers/AgentKnowledgeController.kt`)

| Methode | Pad | Doel |
| --- | --- | --- |
| GET | `/agent-knowledge` | Kennis ophalen voor `target_repo` en `role`; bedoeld voor interne tooling/UI, niet voor de agentworker-container. |
| POST | `/agent-knowledge/update` | Kennis upserten voor een repo/rol/categorie/key; runtime verwerkt agent-updates vanuit `agent-result.json`. |

## Authenticatie

De completion- en knowledge-endpoints zijn interne endpoints zonder auth; `GET /api/version` is
bewust publiek.

Het Bearer-token-patroon tegen `SF_FACTORY_API_TOKEN` is sinds SF-1415/1416 gebundeld in één
gedeelde helper, `config.BearerTokenAuthorizer.isAuthorized()`: token ophalen via
`ConfigApi.resolvedValues()`, `Authorization`-header lezen en `Bearer `-prefix strippen, en
constante-tijd vergelijken via `MessageDigest.isEqual` om timing-side-channels te voorkomen. Een
ontbrekend/leeg `SF_FACTORY_API_TOKEN` of een ontbrekend/fout token geeft `401`. Deze helper wordt
gebruikt door `POST /api/restart` (`FactoryApiController`) en, buiten deze tabel om, door de
tracker-API (`TrackerStoryApiController`, prefix `/api/tracker`) en het completions-requeue-endpoint
(`CompletionOperationsController`).

`GET /api/tracker/stories/{key}` retourneert sinds SF-1775 bovendien nullable `retryAfter`. Voor
een story met een eigen quotawacht benoemt `whyNotPickedUp` het absolute hervattijdstip. Een
wachtende subtaak houdt zijn `retryAfter` uitsluitend op de eigen key; dashboardaggregatie naar de
parent is read-only en verandert dit machine-tot-machine antwoord niet.

`POST /api/tracker/stories` maakt stories voor onder meer `tools/sf-story` en de
Telegram-assistent. Het request bevat `notificationEvents` als concrete stringset (default
`DEPLOYED`, `QUESTION`, `MANUAL_ACTION_REQUIRED`, `ERROR`) en geen preset- of combinatie-enum.
Alleen de exacte acht `NotificationEvent`-namen zijn geldig; een onbekende naam geeft HTTP 400
zonder story-write.
Sinds SF-1959 kent het request ook het optionele boolean `hotfix`
(default `false`, `sf-story create --hotfix`): staat het aan, dan slaat de story refine, plan,
review, test, summary en documentatie over en krijgt hij alleen de subtaken `hotfix`, `merge` en
`deploy`. De vlag is alleen bij het aanmaken te zetten — er is geen endpoint om hem daarna te
wijzigen.

`POST /api/tracker/stories/{key}` accepteert `notificationEvents` als optioneel veld van een
partial update. Iedere combinatie van de acht exacte namen is geldig, inclusief een lege set.
Deze write is story-only: een subtaak-key of onbekende eventnaam geeft HTTP 400. Het volledige
eventcontract en de story-check worden vóór de eerste partial write gevalideerd, zodat bij een
afwijzing ook eventuele andere velden uit hetzelfde request ongewijzigd blijven.

De `dashboard-backend` gebruikt Google-SSO (OIDC) voor authenticatie en de `AuthService`
vergelijkt de HMAC-signature van sessie-tokens ook in constante tijd. Zie de dashboard-backend
voor details over de `SF_GOOGLE_CLIENT_ID`, `SF_ALLOWED_EMAILS` en `SF_DASHBOARD_REMEMBER_SECRET`
configuratie.
## Product Factory-integratie

De dashboard-backend biedt onder `/api/integrations/v1` een beperkte machine-API voor Product
Factory. Deze gebruikt `Authorization: Bearer <SF_PRODUCT_FACTORY_TOKEN>` en dus niet de
Google-dashboardsessie of het algemene factory-token.

- `GET /api/integrations/v1/status` — bridgeverbinding en factoryversie;
- `POST /api/integrations/v1/stories` — maakt een story; vereist `Idempotency-Key` en ondersteunt
  alleen `draft` of `start-next`. Product-, workspace-, commit- en artefactreferenties worden
  duurzaam in de storyomschrijving vastgelegd;
- `GET /api/integrations/v1/stories/{storyKey}` — volledige status, subtaken en agentvragen;
- `POST /api/integrations/v1/stories/{storyKey}/answers` — uitsluitend de bekende
  vraag-naar-antwoordfaseovergangen voor story of subtaak.

Bij een retry zoekt de backend eerst naar de idempotency-marker in bestaande stories. Daarmee kan
ook een client-time-out na een geslaagde create geen dubbele story veroorzaken. De API geeft 503
zolang de lokale Software Factory niet met de uitgaande websocket-bridge verbonden is.

### Statuscodes

De statuscode is voor deze machine-API onderdeel van het contract: hij vertelt de client of een
retry zin heeft.

| Code | Wanneer |
| --- | --- |
| 401 | Ontbrekend, verkeerd of (aan de backendkant) leeg `SF_PRODUCT_FACTORY_TOKEN`. Fail-closed: een blanco token op de backend weigert elk verzoek. |
| 400 | Ongeldige invoer, gecontroleerd vóór elke bridge-dispatch — ontbrekende of niet-passende `Idempotency-Key`, ongeldige productslug, lege titel/omschrijving/repo, ongeldige workspace-commit-SHA, een andere `deliveryMode` dan `draft`/`start-next`; op de answers-route een leeg antwoord, een andere `targetType` dan `story`/`subtask`, een `targetKey` die niet bij het pad hoort of een fase buiten de toegestane antwoordfasen. Ook een `INVALID_PARAMS` dat via de bridge terugkomt geeft 400. |
| 404 | De factory antwoordt met `NOT_FOUND` (bijvoorbeeld een onbekende storykey). |
| 502 | De factory antwoordt met een andere fout, of de bridge levert geen bruikbaar antwoord. |
| 503 | `FACTORY_OFFLINE`: de lokale Software Factory is niet met de bridge verbonden. |

Sinds SF-2256 wordt invoervalidatie afgehandeld met `ResponseStatusException(BAD_REQUEST, ...)` in
plaats van `require(...)`. Er is namelijk geen `@ControllerAdvice` in `dashboard-backend`, dus een
`IllegalArgumentException` kwam als 500 terug — het signaal "probeer opnieuw", terwijl een verzoek
met bijvoorbeeld een verkeerde `deliveryMode` bij elke poging opnieuw faalt. De meldingstekst zit in
de exceptie; het exacte formaat van de foutbody bij een 400 ligt niet vast en is Spring-default. Een
500 uit deze API betekent nu dus altijd een echte serverfout.
