# Functional Spec

De software-factory automatiseert YouTrack-issues via een lokale agent-pijplijn:

1. Refiner scherpt een story aan of stelt vragen.
2. Developer implementeert de story, houdt een story-log bij en opent/update
   een PR.
3. Reviewer beoordeelt de PR.
4. Tester test de preview-deploy.

De orchestrator:

- Pollt YouTrack-issues met `Stage = Develop` en `AI-supplier` niet leeg/niet `none`.
- Stuurt op `AI-supplier`, `AI Phase`, `Paused` en `Error`.
- Start agent-runs in Docker-containers.
- Houdt run-state en tokengebruik bij in Postgres.
- Ondersteunt budget-pauzes, credit-pauzes en handmatige comment-commands.

`AI-supplier=mock` gebruikt dummy agents zodat de workflow end-to-end kan
werken zonder echte AI CLI. `AI-supplier=claude` gebruikt Claude Code.
