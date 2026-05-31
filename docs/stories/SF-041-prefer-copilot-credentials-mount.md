# SF-041 - Prefer Copilot credentials mount

## Story

Als de Software Factory met `AI-supplier=copilot` draait en
`SF_COPILOT_CREDENTIALS_DIR` is ingesteld, moet de agent de gemounte Copilot
login gebruiken. De orchestrator mag dan niet alsnog `gh auth token` ophalen en
als `COPILOT_GITHUB_TOKEN` doorgeven, omdat dat een andere auth-route kan
forceren dan het Copilot-abonnement van de gemounte login.

Als er expliciet een Copilot-token is gezet, blijft die token leidend. Als er
geen token en geen credentials-mount is, mag de bestaande `gh auth token`
fallback blijven bestaan.

## Stappenplan

[x]: Reproduceer de fout via de Docker/container logs.
[x]: Pas de Docker runtime aan zodat de credentials-mount voorrang heeft boven de `gh auth token` fallback.
[x]: Verbeter de Copilot foutmelding zodat raw CLI-errors in de agent-comment zichtbaar worden.
[x]: Werk specs en technische docs bij.
[x]: Voeg regressietests toe voor de nieuwe auth-volgorde en de foutmelding.
[x]: Draai de tests.

## Uitwerking

De container startte wel, maar de Copilot CLI faalde met:
`Model "claude-opus-4.5" from --model flag is not available.`

De handmatige `docker run` faalde eerder omdat de gelogde commandline een
tijdelijke env-file bevatte die de orchestrator direct na `docker run -d`
verwijdert. Dat is bewust, zodat tokens niet op disk blijven staan, maar het
maakt de gelogde commandline niet herbruikbaar.

De root cause in de auth-flow is aangepast: bij een ingestelde
`SF_COPILOT_CREDENTIALS_DIR` wordt geen host `gh auth token` meer opgehaald.
De container gebruikt dan de gemounte `/home/runner/.copilot` login. Alleen
expliciete tokens blijven via een tijdelijke env-file gaan; zonder credentials
mount blijft `gh auth token` de fallback.

Daarnaast neemt de Copilot adapter nu een raw foutregel uit de CLI-output op in
de agent-comment wanneer de CLI met een non-zero exit-code stopt en geen finale
assistant-message heeft gegeven.
