# SF-024 - Stale Tracker Issues In Cost Monitor

## Story

Als een actieve lokale story-run verwijst naar een issue-key die niet meer in
YouTrack bestaat, mag de cost monitor niet elke poll blijven falen met een
stacktrace. De run moet lokaal als stale worden afgesloten, zodat oude
Jira/YouTrack testdata de applicatie niet blijft vervuilen.

## Stappenplan

[x]: create story document
[x]: handle missing tracker issues in cost monitor
[x]: keep transient tracker failures visible
[x]: add unit coverage
[x]: run tests

## Uitvoering

- Aangemaakt omdat lokale startup een actieve run voor `KAN-69` vond, terwijl
  YouTrack dit issue niet meer kent en `GET /api/issues/KAN-69` daarom 404
  teruggeeft.
- `CostMonitorService` herkent nu tracker responses met status 404 als stale
  lokale story-runs en sluit die lokaal af met final status `tracker-missing`.
- Andere trackerfouten blijven zichtbaar als waarschuwing met stacktrace, omdat
  die transient kunnen zijn en geen reden zijn om lokale runs te sluiten.
- Unit tests toegevoegd voor beide paden.
- `mvn -q -Dtest=CostMonitorServiceTest test` en `mvn -q test` draaien
  succesvol.
