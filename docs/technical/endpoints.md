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
| POST | `/agent-run/complete` | Compatibility endpoint om outcome, usage en events te verwerken. De Docker-agent gebruikt primair `/work/agent-result.json`. |

## Agent knowledge endpoints (`web/controllers/AgentKnowledgeController.kt`)

| Methode | Pad | Doel |
| --- | --- | --- |
| GET | `/agent-knowledge` | Kennis ophalen voor `target_repo` en `role`; bedoeld voor interne tooling/UI, niet voor de agentworker-container. |
| POST | `/agent-knowledge/update` | Kennis upserten voor een repo/rol/categorie/key; runtime verwerkt agent-updates vanuit `agent-result.json`. |

## Authenticatie

De completion- en knowledge-endpoints zijn interne endpoints zonder auth; `GET /api/version` is
bewust publiek.

Het Bearer-token van het `POST /api/restart`-endpoint (`FactoryApiController`, geconfigureerd via
`SF_FACTORY_API_TOKEN`) wordt in constante tijd vergeleken via `MessageDigest.isEqual` om
timing-side-channels te voorkomen; een ontbrekend/fout token geeft `401`.

De `dashboard-backend` gebruikt Google-SSO (OIDC) voor authenticatie en de `AuthService`
vergelijkt de HMAC-signature van sessie-tokens ook in constante tijd. Zie de dashboard-backend
voor details over de `SF_GOOGLE_CLIENT_ID`, `SF_ALLOWED_EMAILS` en `SF_DASHBOARD_REMEMBER_SECRET`
configuratie.
