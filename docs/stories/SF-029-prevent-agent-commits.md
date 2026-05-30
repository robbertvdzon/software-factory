# SF-029 - Voorkom commits door Claude-agent

## Story

Als factory-owner wil ik dat Claude-agenten nooit zelf lokale commits, pushes of PR-acties uitvoeren, zodat alle Git-mutaties centraal en voorspelbaar via de orchestrator lopen.

## Stappenplan

[x]: Controleer waar de AI-prompts commit- of push-instructies bevatten.
[x]: Pas de Claude developer-prompt aan zodat commit, push en PR-acties expliciet verboden zijn.
[x]: Leg in de specs vast dat de agent wijzigingen uncommitted achterlaat en de orchestrator commit/pusht.
[x]: Voeg een technische guard toe die een agent-run faalt als de agent zelf een commit maakt.
[x]: Werk technische documentatie bij zodat de end-to-end flow klopt.
[x]: Voeg een regressietest toe voor de developer-prompt.
[x]: Draai de relevante tests.

## Uitwerking

De Claude developer-prompt bevatte nog de instructie om lokaal te committen als dat lukte. Dat botst met de gewenste eigenaarschapverdeling: de agent schrijft alleen bestanden, terwijl de orchestrator na succesvolle completion `git add`, `git commit`, `git push` en PR-aanmaak of PR-update uitvoert.

Ik heb de prompt daarom aangepast naar een expliciet verbod op `git commit`, `git push`, `gh pr create/update/merge` en andere PR-acties. De agent moet wijzigingen uncommitted in de working tree laten staan. Omdat de Codex- en Copilot-adapters dezelfde prompt builder hergebruiken, geldt deze beperking ook voor die echte AI-adapters zodra ze deze builder gebruiken.

Daarnaast is er een technische guard toegevoegd in de agentworker. Die bewaart de Git `HEAD` voordat de AI-run start en vergelijkt die na afloop opnieuw. Als een agent toch zelf een commit maakt, wordt de run als foutresultaat afgerond en gaat de orchestrator die wijziging niet pushen.

De specs en technische overview/modules zijn bijgewerkt zodat de documentatie dezelfde verantwoordelijkheid beschrijft. Er zijn tests toegevoegd die controleren dat de developer-prompt het verbod bevat, dat de oude commit-instructie niet terugkomt en dat de commit-guard een agent-commit detecteert.
