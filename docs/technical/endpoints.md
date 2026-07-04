# HTTP endpoints

Er zijn 39 HTTP endpoints in de `softwarefactory`-module (geteld op de mapping-annotaties in
`web/controllers/`). Static resources uit `softwarefactory/src/main/resources/static` zijn niet
meegeteld; de aparte `dashboard-backend`-module heeft haar eigen JSON-API en valt buiten deze lijst.

## Dashboard endpoints (`web/controllers/FactoryDashboardController.kt`)

HTML-pagina's, redirects en form-POSTs; server-sent events voor live updates.

| Methode | Pad | Doel |
| --- | --- | --- |
| GET | `/events` | Server-sent events stream voor live dashboard-updates. |
| GET | `/login` | Loginpagina tonen. |
| POST | `/login` | Login verwerken en sessie/cookie zetten. |
| POST | `/logout` | Uitloggen en login-cookie wissen. |
| GET | `/` | Rootpagina; toont dashboard na authenticatie. |
| GET | `/dashboard` | Dashboard met samenvatting van stories/runs. |
| GET | `/stories` | Story-overzicht. |
| POST | `/stories/create` | Nieuwe story aanmaken in YouTrack (repo, supplier, fase, auto-approve). |
| GET | `/stories/{storyKey}` | Detailpagina voor een story. |
| GET | `/stories/{storyKey}/briefing` | Briefing/timeline voor een story. |
| GET | `/stories/{storyKey}/screenshots` | Tester-screenshots van een story. |
| POST | `/stories/{storyKey}/commands/{command}` | Handmatig `@factory`-command queueen (pause/resume/approve/reject/…). |
| POST | `/stories/{storyKey}/purge` | Story synchroon hard verwijderen (issue + subtaken + runs + workspace). |
| POST | `/stories/{storyKey}/story-phase` | `Story Phase` handmatig zetten. |
| POST | `/stories/{storyKey}/start-refining` | Story op fase `start` zetten (refinement starten). |
| POST | `/stories/{storyKey}/start-developing` | Eerste subtaak op `start` zetten (development starten). |
| POST | `/stories/{storyKey}/set-auto-approve/{state}` | `Auto-approve` aan/uit zetten. |
| POST | `/stories/{storyKey}/subtask-phase` | `Subtask Phase` van een subtaak handmatig zetten. |
| POST | `/stories/{storyKey}/open-workspace` | Story-workspace lokaal openen in IntelliJ. |
| GET | `/my-actions` | Openstaande menselijke acties (vragen/goedkeuringen). |
| GET | `/my-actions/count` | Aantal openstaande acties (voor de badge). |
| GET | `/projects` | Projectenoverzicht uit `projects.yaml`. |
| POST | `/projects/{projectName}/force-deploy` | Handmatige deploy van een project forceren. |
| GET | `/nightly` | Nightly-pagina: run-status + job-lijst. |
| POST | `/nightly/run-now` | Handmatige (`manual`) nightly run starten. |
| POST | `/nightly/stop` | Lopende nightly run onderbreken. |
| POST | `/nightly/create-story` | Story aanmaken vanuit één nightly job. |
| GET | `/agents` | Agent-runs en runtime status. |
| GET | `/merged` | Recent gemergde story runs / PRs. |
| GET | `/downloads` | Downloads-pagina. |
| GET | `/settings` | Settings en redacted configuratie. |
| POST | `/settings/nightly` | Nightly-scheduler-instellingen opslaan (enabled/start/summary). |
| POST | `/admin/restart` | Factory herstarten vanuit het dashboard. |
| POST | `/admin/stop` | Factory stoppen vanuit het dashboard. |

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

De dashboardpagina's gebruiken `FactoryDashboardAuth`, sinds de refactor afgedwongen via één
`HandlerInterceptor` (in plaats van per-endpoint checks). Niet-geauthenticeerde gebruikers krijgen
de login view of een redirect naar `/login`. De completion- en knowledge-endpoints zijn interne
endpoints zonder dashboard-auth; `GET /api/version` is bewust publiek.

Vergelijkingen van geheimen — het login-wachtwoord en de HMAC-signature van de remember-cookie
respectievelijk het bearer-token — gebeuren in constante tijd via `MessageDigest.isEqual` om
timing-side-channels te voorkomen. Dit geldt consistent voor zowel `FactoryDashboardAuth`
(softwarefactory) als de `AuthService` van de `dashboard-backend` (`requireAuthorization`); de
gebruikersnaam is geen geheim en wordt bewust met een gewone vergelijking gecontroleerd. Ook het
Bearer-token van het `POST /api/restart`-endpoint (`FactoryApiController`, geconfigureerd via
`SF_FACTORY_API_TOKEN`) wordt sinds SF-733 op deze constant-tijd manier vergeleken; een
ontbrekend/fout token blijft `401` opleveren.

De door de gebruiker meegegeven redirect-doelen rond login (`next`/`returnTo`, gebruikt voor de
Location-header en de login-`next`) worden gevalideerd via de helper
`SafeRedirect.localPath(value, default)`: alleen lokale paden die met een enkele `/` beginnen zijn
toegestaan. Een leidende `//` (protocol-relatieve URL) én een leidende `/\` worden geweigerd —
browsers normaliseren de backslash naar een forward slash, waardoor `/\evil.com` anders als
`//evil.com` een open redirect naar een externe host zou opleveren. Bij twijfel valt de waarde
terug op een veilig default-pad.
