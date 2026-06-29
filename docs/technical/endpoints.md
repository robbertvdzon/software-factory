# HTTP endpoints

Er zijn 17 HTTP endpoints. Static resources uit `softwarefactory/src/main/resources/static` zijn niet meegeteld.

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

Vergelijkingen van geheimen — het login-wachtwoord en de HMAC-signature van de remember-cookie respectievelijk het bearer-token — gebeuren in constante tijd via `MessageDigest.isEqual` om timing-side-channels te voorkomen. Dit geldt consistent voor zowel `FactoryDashboardAuth` (softwarefactory) als de `AuthService` van de `dashboard-backend` (`requireAuthorization`); de gebruikersnaam is geen geheim en wordt bewust met een gewone vergelijking gecontroleerd. Ook het Bearer-token van het `POST /api/restart`-endpoint (`FactoryApiController`, geconfigureerd via `SF_FACTORY_API_TOKEN`) wordt sinds SF-733 op deze constant-tijd manier vergeleken; een ontbrekend/fout token blijft `401` opleveren.

De door de gebruiker meegegeven redirect-doelen rond login (`next`/`returnTo`, gebruikt voor de Location-header en de login-`next`) worden gevalideerd via de helper `SafeRedirect.localPath(value, default)`: alleen lokale paden die met een enkele `/` beginnen zijn toegestaan. Een leidende `//` (protocol-relatieve URL) én een leidende `/\` worden geweigerd — browsers normaliseren de backslash naar een forward slash, waardoor `/\evil.com` anders als `//evil.com` een open redirect naar een externe host zou opleveren. Bij twijfel valt de waarde terug op een veilig default-pad.
