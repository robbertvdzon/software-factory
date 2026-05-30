# SF-032 - Doorwerken zonder automatische sync-pauze

## Story

Als gebruiker wil ik op mijn werk-pc automatische commit/push kunnen uitzetten zonder dat de AI-flow na de developer stopt. De reviewer moet dezelfde lokale story-workspace kunnen gebruiken om de uncommitted wijzigingen te beoordelen. Commit/push blijft dan een handmatige actie via `sync`.

## Stappenplan

[x]: Maak story-document aan met scope en plan.
[x]: Verwijder de automatische `Paused=true` na succesvolle developer-run wanneer `SF_AUTO_SYNC_AFTER_AGENT=false`.
[x]: Houd handmatige `sync` beschikbaar voor commit + push + PR update.
[x]: Pas comments, specs en technische docs aan zodat de instelling niet meer als pauze wordt beschreven.
[x]: Werk regressietests bij.

## Uitvoering

- Gestart met het plan. De bestaande implementatie sloeg repository-sync al over, maar pauzeerde daarna ook de story. Dat laatste moet weg omdat reviewer dezelfde workspace gebruikt.
- `AgentRunCompletionService` zet bij `SF_AUTO_SYNC_AFTER_AGENT=false` geen `Paused` meer. De phase wordt nog steeds bijgewerkt, waardoor de orchestrator de volgende rol kan dispatchen.
- `@factory:command:sync` blijft beschikbaar voor de handmatige commit/push/PR-update en zet `Paused=false` als dat veld om een andere reden aan stond.
- Regressietest aangepast zodat manual-sync mode wel `phase=developed` zet maar geen `Paused`-veld update. Volledige Maven-suite draait groen.
