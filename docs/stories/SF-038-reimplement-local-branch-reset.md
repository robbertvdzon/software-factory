# SF-038 - Re-implement reset lokale story-branch

## Story

Als gebruiker wil ik dat `re-implement` ook werkt wanneer de factory niet automatisch commit/pusht of wanneer de target repository geen `github.com` repository is. Bij een re-implement moet de lokale story-workspace opnieuw schoon beginnen: de lokale story-branch wordt weggegooid en opnieuw aangemaakt vanaf de configured base branch, zodat eerdere lokale commits of wijzigingen niet meer meedoen.

## Stappenplan

[x]: Analyseer waarom `re-implement` faalt bij niet-GitHub repositories.
[x]: Voeg een workspace-operatie toe om de lokale story-branch opnieuw vanaf de base branch te maken.
[x]: Pas `re-implement` aan zodat GitHub PR/branch cleanup best-effort is en non-GitHub repositories niet blokkeert.
[x]: Zorg dat de bestaande workspace behouden blijft maar de repo inhoud schoon opnieuw begint.
[x]: Voeg regressietests toe voor non-GitHub re-implement en git branch reset.
[x]: Verwijder bij re-implement de actieve story-run uit de database, inclusief agent-runs en events via cascade.
[x]: Draai de volledige verificatie.

## Uitvoering

- De fout kwam doordat `re-implement` altijd remote GitHub branch cleanup probeerde zodra er een branchnaam was. Voor niet-GitHub repositories gaf de GitHub-client daarom `Only github.com repositories are supported for PR operations`.
- `re-implement` doet remote GitHub cleanup nu alleen nog voor `github.com` repositories en behandelt dat als best-effort.
- De lokale workspace wordt niet meer verwijderd bij `re-implement`. In plaats daarvan wordt de repo in de workspace teruggezet door de story-branch lokaal opnieuw te maken vanaf de base branch.
- De lokale reset gebruikt: fetch base branch, `git reset --hard`, `git clean -fd`, detached checkout van `origin/<base>`, lokale story-branch verwijderen en opnieuw `checkout -B` vanaf `origin/<base>`.
- De actieve `story_runs` rij wordt bij `re-implement` verwijderd in plaats van gesloten. De gekoppelde `agent_runs` en `agent_events` verdwijnen mee via de bestaande `ON DELETE CASCADE` foreign keys.

## Verificatie

- `mvn -q -pl softwarefactory -Dtest=ManualCommandServiceTest,GitCommandClientTest test`
- `mvn -q -pl softwarefactory test`
- `git diff --check`
