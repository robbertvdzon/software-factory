# SF-817 - update stories overzicht

## Story

update stories overzicht

<!-- refined-by-factory -->

## Scope

Verbeteringen aan het stories-overzicht in de dashboard-frontend (`dashboard-frontend/lib/screens/stories_screen.dart`) plus de minimale backend-uitbreiding die daarvoor nodig is (het exposen van tijdstempels en het optioneel maken van `projectKey` bij het aanmaken van een story).

In scope:
1. **Sortering** – het overzicht sorteert stories altijd op storynummer aflopend (hoogste/nieuwste bovenaan), ongeacht actieve filters.
2. **Tijdstempel per story** – elke story-regel toont een tijdstempel: voor afgeronde stories het moment van afronden, voor niet-afgeronde stories het aanmaakmoment. Hiervoor exposet de backend `created_at` en het afrondtijdstip via de `/api/v1/stories`-response (velden op `TrackerIssue`/`fields`), en toont de frontend het passende tijdstempel.
3. **Filterbalk bovenaan** – het bestaande project-filter wordt vervangen door:
   - een **repo-filter** (kiezen uit de distinct repo-waarden van de getoonde stories, plus "alle repos");
   - een **zoekveld** dat filtert op een subtekst in de story-titel (case-insensitive).
   De bestaande todo/bezig/klaar-bucketfilters blijven bestaan. Filterkeuzes blijven, net als nu, bewaard via SharedPreferences (het oude `stories_filter_project`-pref wordt vervangen door repo-/zoekfilter-prefs).
4. **Project-veld verwijderen bij aanmaken** – het "Project"-dropdownveld verdwijnt volledig uit het "Nieuwe story"-dialoog. De backend maakt `projectKey` optioneel en valt terug op het enige geconfigureerde project wanneer de client geen projectKey meestuurt, zodat key-generatie (bv. `SF-001`) blijft werken.

Buiten scope:
- Wijzigingen aan de subtaken-weergave, story-detailscherm of andere schermen.
- Ondersteuning voor meerdere gelijktijdig geconfigureerde projecten in het aanmaakformulier (het factory-model is nu single-project sinds YouTrack is vervangen).

## Acceptance criteria

1. In het stories-overzicht staan stories altijd gesorteerd op storynummer aflopend (bv. `SF-817` boven `SF-816`), ook na het wijzigen van filters of het zoekveld.
2. Elke story-regel toont een leesbaar tijdstempel:
   - voor een afgeronde story het afrondtijdstip;
   - voor een niet-afgeronde story het aanmaaktijdstip.
   De backend levert de daarvoor benodigde tijdstempels mee in de `/api/v1/stories`-response.
3. Bovenaan het overzicht staat een repo-filter waarmee op één repo gefilterd kan worden, met een optie om alle repos te tonen. Filteren toont alleen stories van de gekozen repo.
4. Bovenaan staat een zoekveld; het intypen van tekst toont alleen stories waarvan de titel die subtekst bevat (case-insensitive). Leeg zoekveld toont alle (verder gefilterde) stories.
5. Repo-filter, zoekterm en bucketfilters werken gecombineerd (AND) en blijven behouden na navigatie/herstart van de app.
6. Het "Nieuwe story"-dialoog bevat geen Project-veld meer. Een story aanmaken zonder projectkeuze slaagt en krijgt een correcte story-key (juiste prefix, oplopend nummer). Bestaande verplichte velden (titel) en overige opties (repo, AI-supplier/-model, direct starten, auto-approve) blijven werken.
7. Bestaande tests blijven groen; nieuwe/aangepaste backend-logica (optionele projectKey, geëxposeerde tijdstempels) is met tests afgedekt op het niveau dat in de repo gebruikelijk is.

## Aannames

- **Afrondtijdstip:** de Postgres-tracker kent geen aparte `resolved_at`/`finished_at`-kolom; alleen `created_at` en `updated_at` bestaan. Voor afgeronde stories wordt `updated_at` als afrondtijdstip gebruikt (een afgeronde story wordt in de praktijk niet verder bijgewerkt). Er wordt géén nieuwe migratie/afrond-tracking toegevoegd tenzij dat alsnog gewenst blijkt.
- **Afgerond = bucket "klaar":** of een story "afgerond" is, wordt bepaald met dezelfde status→bucket-classificatie als het bestaande klaar-filter (`done/fixed/verified/closed/resolved`).
- **Default project bij aanmaken:** wanneer de frontend geen projectKey meestuurt, kiest de backend het enige geconfigureerde project (via `ensureConfiguredProjects()`/`FactorySecrets.youTrackProjects`). Bij precies één geconfigureerd project is dit eenduidig; het huidige factory-model is single-project.
- **Repo-waarde:** de repo van een story komt uit het bestaande `fields.repo` (met dezelfde fallback naar de run-`targetRepo` als nu in de tile wordt gebruikt).
- **Tijdstempel-format:** een korte, leesbare datum/tijd-notatie in lijn met de bestaande UI; exacte format is een implementatiedetail.

## Eindsamenvatting

## Eindsamenvatting — SF-817: Update stories-overzicht

**Wat is gebouwd**

Het stories-overzicht in het Flutter-dashboard is verbeterd, met de minimale backend-uitbreiding die daarvoor nodig was:

1. **Sortering** — Stories staan altijd aflopend op storynummer (nieuwste bovenaan), ongeacht de actieve filters. De sortering gebeurt op de volledige lijst vóór het filteren, dus filter-onafhankelijk.
2. **Tijdstempel per story** — Elke story-regel toont nu een leesbaar tijdstip: voor afgeronde stories het afrondmoment, voor overige het aanmaakmoment. De backend exposet hiervoor `created_at`/`updated_at` op de `/api/v1/stories`-response.
3. **Nieuwe filterbalk** — Het oude project-filter is vervangen door een **repo-filter** (distinct repo's van de getoonde stories + "alle repos") en een **case-insensitive zoekveld** op de titel. Repo, zoekterm en de bestaande todo/bezig/klaar-buckets combineren met AND en blijven bewaard via SharedPreferences.
4. **Project-veld verwijderd bij aanmaken** — De Project-dropdown is weg uit het "Nieuwe story"-dialoog. De backend maakt `projectKey` optioneel en valt terug op het enige geconfigureerde project, zodat key-generatie (`SF-###`) blijft werken.

**Gemaakte keuzes**

- **Afrondtijdstip = `updated_at`.** Er is geen aparte `resolved_at`-kolom; een afgeronde story wordt in de praktijk niet verder bijgewerkt, dus `updated_at` dient als afrondmoment. Bewust **géén nieuwe migratie** — de kolommen bestaan al (V15).
- **Tijdstempels zijn read-only UI-metadata**, geen `TrackerField`. Daardoor blijven de exhaustieve field-update-blokken en de YouTrack-backend ongewijzigd compileren (default `null`).
- **`resolveProjectKey`** spiegelt de bestaande nightly-logica, maar faalt luid met een leesbare foutmelding bij géén geconfigureerd project i.p.v. een hardcoded prefix.
- Een verdwenen repo-selectie valt automatisch terug op "alle repos"; frontend stuurt geen `projectKey` meer mee.

**Wat is getest**

- Backend unit-tests groen: `FactoryDashboardServiceTest` (30, incl. nieuwe optionele-projectKey-fallback), `BridgeApiControllerTest` (12). Volledige reactor: **472 tests, 0 failures**.
- `PostgresTrackerClientTest` (tijdstempel-exposure) is toegevoegd maar vereist Docker/Testcontainers en draait in CI — de enige overgebleven "Error" lokaal is deze bekende Docker-afwezigheid, geen regressie.
- Frontend statisch geverifieerd (geen Flutter-SDK/Docker in de agent-omgeving); CI dekt analyze/build.
- Reviewer akkoord op de volledige story-diff; alle 7 acceptatiecriteria kloppen tegen de code.

**Bewust niet gedaan**

- Geen aparte afrond-tracking/migratie (`resolved_at`) toegevoegd — `updated_at` volstaat.
- Multi-project-ondersteuning in het aanmaakformulier blijft buiten scope (factory is single-project).
- `data['projects']` blijft nog in de API-response staan maar wordt frontend niet meer gebruikt (bewust buiten scope).
- Minor niet-blokkerend: het zoekveld schrijft zonder debounce bij elke toetsaanslag naar SharedPreferences.
