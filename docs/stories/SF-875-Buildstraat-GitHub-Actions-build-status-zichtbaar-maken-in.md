# SF-875 - Buildstraat: GitHub Actions build-status zichtbaar maken in het dashboard

## Story

Buildstraat: GitHub Actions build-status zichtbaar maken in het dashboard

<!-- refined-by-factory -->

## Scope

Zichtbaarheid van GitHub Actions build-status in het dashboard, voor beheerde repositories (gedefinieerd in `projects.yaml` / `ProjectRepoResolver`):

1. **Backend (factory + dashboard-backend)**: een service/client die de GitHub Actions REST API bevraagt via het bestaande `SF_GITHUB_TOKEN`/`FactorySecrets.githubToken`-pad, per beheerd repo de laatste run per workflow ophaalt (naam, conclusion, branch, event, duration, timestamp, html_url), met een korte in-memory TTL-cache naar het bestaande patroon (`@Volatile Pair<Long, T>`, vgl. `NightlyJobsReader`/`FactoryDashboardService`). De ophaal-logica hoeft niet letterlijk in `dashboard-backend` te zitten als de bestaande bridge-architectuur (dashboard-backend → factory-bridge) dat elders logischer maakt; de dashboard-backend-endpoint(s) `GET /api/v1/repositories/{owner}/{repo}/workflows` en `/runs` moeten wel bestaan en werken.
2. **Frontend (Flutter dashboard-frontend)**: een "Builds"/"Buildstraat"-scherm dat per repo de laatste workflow-runs toont (kolommen Workflow / Last result / Branch / Event / Duration), gefilterd per project (PNF/SF/SP-achtige pills), naar het patroon van `wireframes2/builds.html`, ingepast in de bestaande navigatie (`app_shell.dart`) — niet noodzakelijk als losse tab op een nog niet-bestaand repository-detailscherm, aangezien dat scherm nu nog niet bestaat.
3. Een repo waarvan de laatste build op de default/main-branch gefaald is (`conclusion == failure`), moet zichtbaar zijn in een "attention"/waarschuwingen-sectie op het bestaande dashboard-overzicht, niet alleen op het builds-scherm.
4. Voor repo's zonder GitHub Actions workflows: nette empty state ("No GitHub Actions workflows found...").

## Acceptance criteria

- Er bestaat een backend-endpoint (bereikbaar via dashboard-backend, `GET /api/v1/repositories/{owner}/{repo}/workflows` en/of `/runs`) dat voor een beheerd repo per workflow de laatste run teruggeeft met minimaal: workflow-naam, conclusion, branch, event, duration, timestamp, html_url.
- Deze data wordt kort gecached (TTL, geen realtime push) volgens het bestaande cache-patroon in de codebase.
- Auth/token hergebruikt het bestaande GitHub-credentials-pad (`SF_GITHUB_TOKEN` / `FactorySecrets.githubToken`); er wordt geen nieuw secret/config-mechanisme toegevoegd.
- Er is een "Builds" scherm in `dashboard-frontend` bereikbaar via de navigatie, dat per repo de laatste workflow-runs toont in tabelvorm (Workflow/Last result/Branch/Event/Duration), filterbaar per project.
- Een repo zonder workflows toont een duidelijke empty state in plaats van een foutmelding of lege tabel.
- Als de laatste run op de default branch van een beheerd repo `conclusion == failure` heeft, verschijnt dit zichtbaar in een attention/waarschuwingen-sectie op het bestaande dashboard-overzichtsscherm (niet alleen op het Builds-scherm), met minimaal repo-naam, workflow-naam en een link naar de failing run.
- Geen automatische acties (auto-retry, Telegram-melding) bij een gefaalde build in deze story.
- Geen verplichte Cucumber/e2e-uitbreiding; normale unit-/widget-tests dekken de nieuwe service/client en het nieuwe scherm.
- Bestaande functionaliteit (overige dashboard-schermen, bridge-dispatch, andere GitHub-integraties) blijft ongewijzigd werken.

## Aannames

- "Beheerde repo's" = de repo's gedefinieerd in `projects.yaml` / bereikbaar via `ProjectRepoResolver`, inclusief `software-factory` zelf.
- De GitHub Actions REST API wordt bevraagd via HTTPS met bearer-token (naar het patroon van `GitHubReleaseClient`) of via `gh`-CLI (naar het patroon van `GitHubCliClient`) — de ontwikkelaar kiest het mechanisme dat het beste past bij waar de nieuwe client wordt geplaatst.
- Het is aan de developer of de GitHub Actions-ophaallogica in `dashboard-backend` zelf komt of (naar bestaand bridge-patroon) in de factory (`softwarefactory`) met dispatch via de bridge; de bestaande architectuur (dashboard-backend delegeert GitHub-achtige data via bridge naar de factory, zie `downloads.list`) is leidend boven de letterlijke tekst "dashboard-backend: een service/client" in de oorspronkelijke omschrijving.
- Er is nog geen repository-detailscherm met tabs `[Overview] [Buildstraat] [Stories] [Releases/APKs]`; het bouwen van dat volledige detailscherm is geen harde eis van deze story — een los "Builds"-overzichtsscherm (per de bestaande `screen-map.md`-navigatie-vermelding "Builds") volstaat om aan de acceptatiecriteria te voldoen. Een latere story kan het volledige repository-detailscherm met tabs toevoegen.
- "Default/main-branch" voor de faal-detectie op het dashboard-overzicht = de default branch van het repo zoals bekend bij GitHub Actions (`main` of `master`, per repo verschillend), niet elke branch.
- TTL-waarde voor de cache is aan de developer, in lijn met bestaande waarden in de codebase (orde van grootte 20-60s).

## Eindsamenvatting

{"agent_tips_update":[]}
{"phase":"summarized"}
