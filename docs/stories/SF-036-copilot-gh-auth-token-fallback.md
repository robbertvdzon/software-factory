# SF-036 - Gebruik host gh-auth voor Copilot in Docker

## Story

Als gebruiker wil ik GitHub Copilot in Docker kunnen gebruiken met mijn bestaande `gh auth` login, zonder handmatig een Copilot-token in `secrets.env` te zetten. Op macOS staat de GitHub CLI-token meestal in de Keychain, waardoor een mount van `~/.config/gh` of `~/.copilot` alleen niet genoeg is voor de Linux-container.

## Stappenplan

[x]: Maak story-document aan met scope en plan.
[x]: Laat de orchestrator bij `AI-supplier=copilot` een host `gh auth token` ophalen als er geen expliciete Copilot-token is.
[x]: Geef die token via een tijdelijke Docker env-file door als `COPILOT_GITHUB_TOKEN`, zonder tokenwaarde in de commandline.
[x]: Verwijder de tijdelijke env-file direct na `docker run`.
[x]: Werk docs/specs bij.
[x]: Pas tests aan en draai verificatie.

## Uitvoering

- Analyse van PNF-4 developer-run: Copilot CLI startte wel, maar gaf `No authentication information found`. `~/.copilot` was gemount, maar bevatte geen bruikbare auth omdat Copilot/GitHub login op macOS in de system credential store staat.
- De Docker runtime haalt nu voor `copilot`/`github` suppliers een host-token op via `gh auth token` wanneer er geen expliciete `SF_COPILOT_TOKEN`, `COPILOT_GITHUB_TOKEN`, `GH_TOKEN` of `GITHUB_TOKEN` is ingesteld.
- Een expliciete Copilot-token of de host-token gaat via een tijdelijke env-file naar Docker als `COPILOT_GITHUB_TOKEN`, zodat de token niet in de `docker run` commandline of story-workspace staat. De tijdelijke file wordt na `docker run` verwijderd.
- `SF_COPILOT_TOKEN` staat nu op de agent-workspace denylist; de Docker runtime geeft hem apart en tijdelijk door.
- Verificatie gedraaid: `mvn -q -pl softwarefactory -Dtest=DockerAgentRuntimeTest test`, `mvn -q -pl agentworker -Dtest=CopilotAiClientTest test`, `mvn -q -pl softwarefactory test` en `mvn -q -pl agentworker test`.
- Handmatige containercheck: `agent-base:local` kon met host `gh auth token` via env-file de Copilot CLI starten en kreeg een `OK` antwoord terug.
