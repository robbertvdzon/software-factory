# SF-882 - Overzicht wat er op productie staat

## Story

Overzicht wat er op productie staat

<!-- refined-by-factory -->

## Scope

Builds-informatie wordt samengevoegd met de Projects-sectie in het dashboard, met duidelijkere kolomtitels en zichtbare build-/deploy-status per project:

1. **Builds bij Projects tonen**: op het Projects-scherm (`/projects` resp. `ProjectsScreen`) wordt per project een builds-blok toegevoegd/samengevoegd met de bestaande project-panel-informatie (in plaats van een los `/builds`-scherm te moeten bezoeken). De losse Builds-pagina mag blijven bestaan als detailweergave, maar het projectoverzicht toont per project minimaal de kerninfo zonder dat de gebruiker hoeft te wisselen van scherm.
2. **Kolomtitels in de builds-tabel**: de builds-tabel (per workflow: Workflow / Last result / Branch / Event / Duration) krijgt een header-rij met kolomtitels, zodat duidelijk is wat elke kolom betekent (nu ontbreekt deze rij volledig in de Flutter-UI, terwijl de wireframe er wel een toont).
3. **Laatste main-build zichtbaar maken**: per project/repo wordt getoond wat de timestamp is van de laatst afgeronde build op de default branch (main).
4. **Live build-status main/PR**: per project/repo wordt getoond of er op dit moment een build loopt (status `queued`/`in_progress`) voor de default branch, en apart of er een build loopt voor een open PR (afgeleid uit het bestaande `event`-veld: `push` op default branch = main-build, `pull_request` = PR-build).
5. **Exacte productieversie tonen**: per artifact/project met een deploy-configuratie wordt de exacte gedeployde versie getoond (commit-hash/branch/timestamp, zoals nu al deels via `prdVersion`), én een duidelijke indicatie of deze productieversie gelijk is aan de laatste main-build (in-sync) of erachter loopt (out-of-sync). Dit geldt voor alle projecten/artifacts die een deploy-configuratie hebben; projecten zonder deploy-configuratie tonen expliciet dat er geen productieversie te vergelijken is.

Buiten scope: het toevoegen van nieuwe deploy-mechanismen, het wijzigen van bestaande deploy-flows, en het samenvoegen/verwijderen van de bestaande losse Builds-route (`/builds`) — die blijft bestaan voor het volledige overzicht per repo.

## Acceptance criteria

- Op het Projects-scherm ziet de gebruiker per project, zonder naar een ander scherm te hoeven navigeren: de laatste build-status van main, of er nu een build loopt (main en/of PR, apart aangeduid), en de huidige productieversie (indien een deploy-configuratie aanwezig is).
- De builds-tabel (op het Builds-scherm en/of embedded in Projects) heeft een zichtbare kolomtitel-rij boven de rijen met workflow-runs.
- Per project met een deploy-configuratie wordt getoond: het commit/versie-label van de huidige productieversie, het tijdstip van de laatste main-build, en of deze twee overeenkomen (in-sync) of niet (out-of-sync), met een duidelijk visueel onderscheid (bijv. badge/kleur) tussen beide situaties.
- Projecten zonder deploy-configuratie tonen expliciet "geen productieversie beschikbaar" (of gelijkwaardig) in plaats van een lege of misleidende status.
- Als er op dit moment geen build loopt voor main of voor een PR, wordt dat niet als aparte foutmelding getoond maar simpelweg als "geen actieve build".
- Bestaande functionaliteit van het Builds-scherm (filteren per project, doorlinken naar GitHub Actions-run, refresh) blijft werken.
- Er treedt geen regressie op in de bestaande Projects-functionaliteit (force-deploy-knop, story-counters, kosten-chip).

## Aannames

- "Bij de projects" betekent: builds-informatie zichtbaar maken op het Projects-scherm (samengevoegd of duidelijk geïntegreerd per project-panel), niet per se het volledig laten vervallen van het aparte Builds-scherm.
- "Laatste build van main" wordt afgeleid uit de meest recente afgeronde workflow-run met `event == push` op de default branch; als een repo meerdere workflows heeft, geldt de meest recente over alle workflows heen.
- "Of er nu een build loopt" wordt afgeleid uit workflow-runs met status `queued` of `in_progress`, onderscheiden naar main (`event == push` op default branch) versus PR (`event == pull_request`).
- "Productieversie" blijft gekoppeld aan de bestaande `prdVersion`-informatie (commit/branch/timestamp opgehaald via het project se version-endpoint), aangevuld met een vergelijking tegen de laatst bekende main-commit om in-sync/out-of-sync te kunnen tonen. Voor projecten zonder deploy-configuratie (geen `versionUrl`) is geen productieversie beschikbaar; dat blijft zo in deze story.
- "Voor alle artifacts" betekent: voor alle projecten die een deploy-configuratie hebben (zoals nu al het geval is voor de `prdVersion`-weergave), niet voor elk los gebouwd artifact (bijv. individuele APK's) los van een project.

## Eindsamenvatting

Diff bevestigt het worklog volledig. Dit is genoeg voor de eindsamenvatting.

## Eindsamenvatting SF-882: Overzicht wat er op productie staat

**Gebouwd**

Builds-informatie is samengevoegd met het Projects-scherm, zodat de gebruiker per project zonder te wisselen van scherm ziet: de laatste main-build-timestamp, of er nu een build loopt (apart voor main en PR), en of de productieversie in-sync of out-of-sync is met de laatste main-build. Daarnaast heeft de builds-tabel nu een kolomtitel-rij (Workflow / Resultaat / Branch / Event / Duur), conform de wireframe.

- **Backend**: `WorkflowRunInfo` uitgebreid met `headSha`/`runStartedAt` (geparsed uit GitHub Actions API). Nieuwe `BuildSyncStatus`-enum (IN_SYNC/OUT_OF_SYNC/UNAVAILABLE) en `ProjectBuildStatus`-model, berekend via de pure/testbare functie `buildStatusFor()` in `FactoryDashboardService`. Geen nieuwe REST-endpoints nodig — bestaande `/api/v1/projects` en `/api/v1/builds` geven de uitgebreide velden automatisch door.
- **Frontend**: nieuwe kolomtitel-header in `builds_screen.dart`; `ProjectsScreen` kreeg een build-status-blok met badges voor actieve builds en sync-status, additief naast bestaande widgets (force-deploy, story-chips, prd-versie).

**Belangrijke keuzes**

- `shaPrefixMatch` is bewust gedupliceerd i.p.v. hergebruikt vanuit `DeploySubtaskHandler`, omdat er geen precedent is om een kleine `internal`-helper cross-module te delen — reviewer ging hiermee akkoord, met de suggestie dit te heroverwegen bij een derde toepassing.
- `UNAVAILABLE`-status wordt getoond zowel bij projecten zonder deploy-configuratie als bij projecten mét deploy-configuratie maar zonder vergelijkbare main-build-sha; dit is inhoudelijk correct en gedocumenteerd, geen misleidende lege staat.
- De losse Builds-pagina blijft ongewijzigd bestaan als detailweergave (buiten scope om te verwijderen).

**Getest**

- Backend: nieuwe unit tests voor `shaPrefixMatch`/`buildStatusFor` en uitgebreide `GitHubActionsClientTest`, volledig groen. Volledige no-Docker testsuite (465 tests) geeft 0 failures; de 32 errors zijn pre-existing Docker/Testcontainers-omgevingsfouten, niet gerelateerd aan deze wijziging.
- Frontend: nieuwe widget-tests (`projects_screen_test.dart`, uitbreiding `builds_screen_test.dart`) dekken de kolomtitels en de drie sync-statussen. Kon niet lokaal draaien (geen bruikbare flutter/dart-CLI in dev-/testomgeving) — geverifieerd via statische code-review; CI draait `flutter test`.
- Reviewer en tester zijn beide tot "akkoord, geen blockers" gekomen; geen regressies gevonden in force-deploy-knop, story-counters, kosten-chip of bestaande Builds-scherm-functionaliteit.

**Bewust niet gedaan**

- Geen nieuwe deploy-mechanismen of wijzigingen aan bestaande deploy-flows (buiten scope).
- De losse `/builds`-route is niet samengevoegd/verwijderd.
- Frontend-tests zijn niet daadwerkelijk lokaal uitgevoerd door omgevingsbeperkingen (ontbrekende flutter-CLI); vertrouwd op CI en statische review.

**Documentatie**: `docs/factory/ux/screens/builds.md` bijgewerkt, nieuw `docs/factory/ux/screens/projects.md` toegevoegd, en `screen-map.md` aangevuld met de ontbrekende `/projects`-route.
