# Software Factory Repo Context

Deze repository bevat de lokale software-factory orchestrator en agent-runtime.
De factory pollt Jira, start agent-containers via Docker, houdt state bij in
Postgres en laat agents target-repo's refinen, ontwikkelen, reviewen en testen.

Belangrijke documentatie:

- `development.md`: lokaal bouwen, testen en projectstructuur.
- `technical-spec.md`: technische keuzes en conventies.
- `functional-spec.md`: functionele scope van de factory.
- `deployment.md`: runtime/deploy-informatie voor deze repo.
- `secrets-local.md`: lokale secrets en env-vars.
- `agents/`: rol-specifieke aanwijzingen voor agents.

De volledige product-specificatie staat voorlopig ook nog in
`specs/specs.md`. Deze `docs/factory/` map is de factory-ready context die
agents bij toekomstige stories moeten gebruiken.
