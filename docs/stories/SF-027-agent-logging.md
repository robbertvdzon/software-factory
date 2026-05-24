# SF-027 - Agent lifecycle logging

## Story

Als operator van Software Factory wil ik in de applicatielog duidelijk kunnen zien wanneer de orchestrator een agent start, welke container/run daarbij hoort, wanneer de agent klaar is en wat de agent als resultaat terugmeldt.

## Stappenplan

[x]: bepaal waar agent dispatch en completion plaatsvinden
[x]: voeg orchestrator logging toe rond agent start
[x]: voeg completion logging toe met outcome, tokens, kosten en samenvatting
[x]: draai gerichte tests
[x]: noteer wat is aangepast en waarom

## Uitvoering

De lifecycle loopt via `OrchestratorService.dispatchIfAllowed` voor het starten van agents en `AgentRunCompletionService.complete` voor de callback wanneer agents klaar zijn.

In de orchestrator staat nu een `Starting agent dispatch` log voordat Docker wordt aangeroepen en een `Agent started` log nadat de container is gestart en de `agentRunId` in de database staat. Deze logs bevatten story, rol, story-run id, phase, supplier, level, model, target repo, PR/branch, container en workspace.

In de completion-service staat nu een `Agent completion received` log zodra de callback binnenkomt en een `Agent finished` log na opslag, eventverwerking en cleanup. Deze logs bevatten outcome, success-flag, tokens, kosten, duur, agent-run id en een korte geredacteerde samenvatting. Als de agent een PR-event meldt, komt er ook een aparte `Agent reported pull request` logregel.

Gerichte tests:

- `mvn -q -Dtest=OrchestratorServiceTest,AgentRunCompletionServiceTest test`

Volledige validatie:

- `mvn -q test`

De extra logging helpt lokale runs en productie-logs correleren met database-records en Docker-containers zonder promptinhoud, secrets of volledige agent-output in de applicatielog te dumpen. Repo-/PR-URLs en summary-tekst lopen door de bestaande redactor voordat ze in de log komen.
