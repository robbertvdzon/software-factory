# HTTP endpoints

Er zijn 17 HTTP endpoints. Static resources uit `src/main/resources/static` zijn niet meegeteld.

## Dashboard endpoints

Deze endpoints zitten in `web/FactoryDashboardController.kt` en leveren HTML of redirects.

| Methode | Pad | Doel |
| --- | --- | --- |
| GET | `/login` | Loginpagina tonen. |
| POST | `/login` | Login verwerken en sessie/cookie zetten. |
| POST | `/logout` | Uitloggen en login-cookie wissen. |
| GET | `/` | Rootpagina; toont dashboard na authenticatie. |
| GET | `/dashboard` | Dashboard met samenvatting van stories/runs. |
| GET | `/stories` | Story-overzicht. |
| GET | `/stories/{storyKey}` | Detailpagina voor een story. |
| GET | `/stories/{storyKey}/briefing` | Briefing/timeline voor een story. |
| GET | `/stories/{storyKey}/screenshots` | Screenshotoverzicht voor een story. |
| POST | `/stories/{storyKey}/commands/{command}` | Handmatig command voor een story queueen. |
| GET | `/agents` | Agent-runs en runtime status. |
| GET | `/merged` | Recent gemergde story runs / PRs. |
| GET | `/downloads` | Downloads-pagina. |
| GET | `/settings` | Settings en redacted configuratie. |

## Agent completion endpoint

| Methode | Pad | Doel |
| --- | --- | --- |
| POST | `/agent-run/complete` | Compatibility endpoint om outcome, usage en events te verwerken. De Docker-agent gebruikt nu primair `/work/agent-result.json`. |

Controller: `web/controllers/AgentRunCompletionController.kt`.

## Agent knowledge endpoints

| Methode | Pad | Doel |
| --- | --- | --- |
| GET | `/agent-knowledge` | Kennis ophalen voor `target_repo` en `role`; bedoeld voor interne tooling/UI, niet voor de agentworker-container. |
| POST | `/agent-knowledge/update` | Kennis upserten voor een repo/rol/categorie/key; runtime verwerkt agent-updates nu vanuit `agent-result.json`. |

Controller: `knowledge/AgentKnowledge.kt`.

## Authenticatie

De dashboardpagina's gebruiken `FactoryDashboardAuth`. Niet-geauthenticeerde gebruikers krijgen de login view of een redirect naar `/login`. De completion- en knowledge-endpoints zijn interne endpoints en hebben in de controller zelf geen dashboard-auth check.
