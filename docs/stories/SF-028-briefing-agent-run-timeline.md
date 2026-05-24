# SF-028 - Briefing agent-run timeline

## Story

Als gebruiker van het Software Factory dashboard wil ik in story-detail en briefing duidelijk zien wanneer agent-runs gestart zijn, wat het afgeronde resultaat was en welke iteratie van een rol ik bekijk. De nieuwste run moet bovenaan staan, zodat de actuele status direct zichtbaar is.

## Stappenplan

[x]: analyseer huidige story-detail en briefing rendering
[x]: toon start- en eindtimestamps bij agent-runs
[x]: sorteer briefing-items expliciet nieuwste-eerst
[x]: vertaal technische outcomes naar leesbare resultaattekst
[x]: toon per rol iteratie, zoals developer (3/3)
[x]: voorkom overlap van lange statuslabels in agent-run rijen
[x]: voeg tests toe en draai validatie
[x]: noteer wat is aangepast en waarom

## Uitvoering

De benodigde data (`started_at`, `ended_at`, `outcome`, `role`) zit al in `agent_runs`. De wijziging hoort daarom vooral in de dashboard viewlaag en CSS, niet in de database of repositories.

De briefing sorteert agent-comments en agent-run summaries nu expliciet nieuwste-eerst. Voor agent-runs wordt per rol een iteratie-label berekend op basis van chronologische volgorde; de nieuwste derde developer-run toont daardoor `developer (3/3)`.

Outcomes worden vertaald naar leesbare Nederlandse labels, zoals `Review akkoord`, `Review met feedback`, `Ontwikkeld`, `Test geslaagd` en `Handmatig gestopt`. In de briefing blijft het technische phase-resultaat als compacte code zichtbaar, bijvoorbeeld `REVIEW_FINISHED` of `REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER`.

De agent-run rij toont nu start- en eindtijd in de detailtekst en een aparte starttijdkolom. De statusbadge gebruikt het leesbare label in plaats van de ruwe outcome, en de CSS geeft de rij meer ruimte plus horizontale scroll op kleinere schermen.

Validatie:

- `mvn -q -Dtest=FactoryDashboardViewsTest test`
- `mvn -q test`
