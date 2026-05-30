# SF-030 - Handmatige repository-sync via secrets-instelling

## Story

Als factory-owner wil ik per machine kunnen bepalen of de orchestrator na een agent-run automatisch commit en pusht, zodat mijn thuisomgeving automatisch kan doorlopen maar mijn werk-pc alleen na een expliciete handmatige actie Git-mutaties uitvoert.

## Stappenplan

[x]: Voeg een `secrets.env` instelling toe voor automatische repository-sync.
[x]: Laat agent-completion bij uitgeschakelde automatische sync geen commit/push/PR actie uitvoeren.
[x]: Pauzeer de story na een succesvolle developer-run wanneer handmatige sync nodig is.
[x]: Voeg een handmatig `sync` commando toe dat commit + push + PR update uitvoert en de story hervat.
[x]: Voeg een dashboardknop toe voor het handmatige sync-commando.
[x]: Werk specs, technische docs en secrets voorbeeld bij.
[x]: Voeg regressietests toe voor config, completion, command parsing, dashboard en manual sync.
[x]: Draai de relevante tests.

## Uitwerking

De nieuwe instelling is `SF_AUTO_SYNC_AFTER_AGENT`. De default is `true`, zodat het huidige gedrag thuis hetzelfde blijft. Als deze waarde op `false` staat, slaat `AgentRunCompletionService` de automatische repository-sync over. Na een succesvolle developer-run zet de factory de story op `Paused = true`, zodat de volgende rol niet automatisch start voordat er gepusht is.

Voor de handmatige actie is `@factory:command:sync` toegevoegd. De dashboardknop heet `Commit + push` en queue't hetzelfde commando. Het commando gebruikt dezelfde `StoryWorkspaceService.syncAfterAgent(...)` flow als de automatische sync: `git add`, `git commit`, `git push` en PR aanmaken/bijwerken. Na succesvolle sync zet het commando `Paused = false` en wist het `Error`, zodat de normale state-machine verder kan.

Dit maakt het gedrag lokaal instelbaar: werk-pc `SF_AUTO_SYNC_AFTER_AGENT=false`, thuis `SF_AUTO_SYNC_AFTER_AGENT=true`.
