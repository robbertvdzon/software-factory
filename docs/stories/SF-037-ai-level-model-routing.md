# SF-037 - AI-level modelrouting voor Claude en Copilot

## Story

Als gebruiker wil ik dat `AI Level` weer bepaalt welk model en effort een agent gebruikt. Voor `claude` moet de level-matrix uit de eerdere Personal News Feed factory worden overgenomen. Voor `copilot` moet een compactere matrix gelden: level 0 gebruikt GPT-4.1, levels 1 t/m 3 gebruiken Claude Haiku 4.5, levels 4 t/m 9 gebruiken Claude Sonnet 4.5 en level 10 gebruikt Claude Opus 4.5.

## Stappenplan

[x]: Inventariseer de oude level-matrix in `/Users/robbertvdzon/git/personal-news-feed-by-claude-code`.
[x]: Pas `AiRouting` aan zodat de matrix per supplier, level en agentrol wordt bepaald.
[x]: Geef het gekozen model en effort door naar de agent-container.
[x]: Laat Claude en Copilot CLI het gekozen effort ook als CLI-argument ontvangen.
[x]: Werk specs en technische docs bij.
[x]: Voeg regressietests toe en draai verificatie.

## Uitvoering

- De oude Claude-matrix uit `deploy/tooling/agent-levels.yaml` is overgenomen in Kotlin. De rol-specifieke tiers blijven behouden: refiner, developer, reviewer en tester kunnen bij hetzelfde level dus een ander Claude-model krijgen.
- Voor Copilot is de gevraagde modelmatrix toegevoegd:
  - level 0: `gpt-4.1`
  - level 1 t/m 3: `claude-haiku-4.5`
  - level 4 t/m 9: `claude-sonnet-4.5`
  - level 10: `claude-opus-4.5`
- De bestaande `AI Level` wordt nog steeds begrensd op 0 t/m 10. Onbekende/future suppliers krijgen geen expliciet model, maar behouden wel effort-routing.
- Claude en Copilot krijgen naast `--model` nu ook `--effort`, zodat de effort niet alleen in de prompt staat.

## Verificatie

- `mvn -q -pl softwarefactory -Dtest=AiRoutingTest,OrchestratorServiceTest test`
- `mvn -q -pl agentworker -Dtest=ClaudeCodeAiClientTest,CopilotAiClientTest test`
- `mvn -q -pl softwarefactory test`
- `mvn -q -pl agentworker test`
- `git diff --check`
