# Software Factory Repo Context

Deze repository bevat de lokale software-factory orchestrator en agent-runtime.
De factory pollt YouTrack, start agent-containers via Docker, houdt state bij in
Postgres en laat agents target-repo's refinen, ontwikkelen, reviewen, testen en
samenvatten.

Naast de `softwarefactory`-module (orchestrator, interne HTTP-adapters) en de
`agentworker`-module kent de repo een gedeelde `factory-common`-module en een
aparte `dashboard-backend` (Spring Boot JSON-API) met bijbehorende
`dashboard-frontend` (Flutter web-app) als dashboard. Zie `development.md` voor
de modulestructuur.

Belangrijke documentatie:

- `development.md`: lokaal bouwen, testen en projectstructuur.
- `technical-spec.md`: technische keuzes en conventies.
- `functional-spec.md`: functionele scope van de factory.
- `deployment.md`: runtime/deploy-informatie voor deze repo.
- `secrets-local.md`: lokale secrets en env-vars.
- `agents/`: rol-specifieke aanwijzingen voor agents.
- `ux/`: UX-specificatie en wireframes voor de webinterface.

`specs/specs.md` is een historisch archief (deels verouderd model); de actuele
specificatie is `functional-spec.md` in deze map. Deze `docs/factory/` map is de
factory-ready context die agents bij toekomstige stories moeten gebruiken.
