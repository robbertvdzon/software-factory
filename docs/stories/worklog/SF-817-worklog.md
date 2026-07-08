# SF-817 - Worklog

Story-context bij eerste pickup:
Stories-overzicht: sortering, tijdstempels, repo-/zoekfilter en Project-veld verwijderen

Backend: expose created_at en een afrondtijdstip (afgeleid van updated_at) op TrackerIssueFields/de /api/v1/stories-response via PostgresTrackerClient en de bridge (BridgeApiController); geen nieuwe migratie. Maak projectKey optioneel in FactoryDashboardService.createStory met fallback naar het enige geconfigureerde project (ensureConfiguredProjects/FactorySecrets.youTrackProjects), zodat key-generatie (SF-###) blijft werken. Frontend (dashboard-frontend/lib/screens/stories_screen.dart): sorteer de lijst altijd aflopend op storynummer; toon per rij een leesbaar tijdstip (afgeronde story=afrondtijdstip, overige=creation, robuust bij ontbrekende waarde); vervang de projectKey-filter door een repo-filter (distinct repos + 'alle repos') en een case-insensitive titel-zoekveld, combineer met de bestaande todo/bezig/klaar-buckets (AND) en persisteer via SharedPreferences (oude stories_filter_project vervangen); verwijder de Project-dropdown uit _CreateStoryDialog en stuur geen projectKey meer mee. Voeg unit-tests toe voor de optionele-projectKey-default en de geexposeerde tijdstempels op het niveau dat in de repo gebruikelijk is; houd bestaande tests groen. Sluit af met een review-stap.

Stappenplan:
[ ]: read issue and target docs
[ ]: implement requested changes
[ ]: run relevant tests
[ ]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
