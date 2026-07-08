# SF-818 — Stories-overzicht: sortering, tijdstempels, repo-/zoekfilter en Project-veld verwijderen

## Story in eigen woorden

Het stories-overzicht in de Flutter dashboard-frontend krijgt een aantal verbeteringen, met de
minimale backend-uitbreiding die daarvoor nodig is:

1. Sorteer stories altijd op storynummer aflopend (nieuwste bovenaan), ongeacht filters.
2. Toon per story-regel een tijdstempel: afgeronde story → afrondmoment, anders aanmaakmoment.
   Daarvoor exposet de backend `created_at`/`updated_at` op de `/api/v1/stories`-response.
3. Vervang het project-filter door een repo-filter + een case-insensitive titel-zoekveld; combineer
   met de bestaande todo/bezig/klaar-buckets (AND) en bewaar via SharedPreferences.
4. Verwijder het Project-dropdownveld uit het "Nieuwe story"-dialoog; maak `projectKey` optioneel in
   de backend met terugval op het enige geconfigureerde project.

## Checklist

- [x]: Backend — `createdAt`/`updatedAt` op `TrackerIssueFields` (default null).
- [x]: Backend — `PostgresTrackerClient` leest `created_at`/`updated_at` (ISSUE_COLUMNS + mapRow).
- [x]: Backend — `FactoryDashboardService.createStory` maakt `projectKey` optioneel (fallback naar
  het enige geconfigureerde project via `ensureConfiguredProjects()`/`FactorySecrets.youTrackProjects`).
- [x]: Backend — `BridgeRequestHandler` stuurt `projectKey` optioneel door.
- [x]: Backend — `dashboard-backend` `CreateStoryRequest.projectKey` nullable; alleen meesturen indien aanwezig.
- [x]: Frontend — sorteer aflopend op storynummer.
- [x]: Frontend — tijdstempel per rij (afgerond=updatedAt, anders createdAt; robuust bij ontbreken).
- [x]: Frontend — repo-filter (distinct repos + "alle repos") + titel-zoekveld; AND met buckets.
- [x]: Frontend — filters persisteren via SharedPreferences (oude `stories_filter_project` vervangen).
- [x]: Frontend — Project-dropdown weg uit `_CreateStoryDialog`; geen `projectKey` meer meesturen.
- [x]: Tests — unit-test optionele projectKey-default (`FactoryDashboardServiceTest`).
- [x]: Tests — unit-test geëxposeerde tijdstempels (`PostgresTrackerClientTest`, Testcontainers/CI).
- [x]: Specs — `docs/factory/ux/screens/stories.md` bijgewerkt.

## Wat en waarom

### Backend

- **Tijdstempels.** `TrackerIssueFields` krijgt `createdAt`/`updatedAt` (`OffsetDateTime?`, default
  null). Bewust géén nieuwe migratie: de `issues`-tabel heeft de kolommen al. `PostgresTrackerClient`
  leest ze in `mapRow` en voegt `created_at, updated_at` toe aan `ISSUE_COLUMNS`. Ze zijn geen
  `TrackerField` (dus niet in `applying`/`columnFor`); puur read-only UI-metadata. Jackson
  serialiseert ze net als het bestaande `agentStartedAt` als ISO-string in de stories-response.
  Aanname (uit de story): een afgeronde story wordt niet verder bijgewerkt, dus `updatedAt` dient als
  afrondmoment; er is geen aparte `resolved_at`-kolom.
- **Optionele projectKey.** `createStory(projectKey: String?)` valt via de nieuwe helper
  `resolveProjectKey` terug op het enige geconfigureerde project (zelfde bron als `createNightlyStory`).
  `BridgeRequestHandler` gebruikt nu `params.optional("projectKey")`, en `dashboard-backend`'s
  `CreateStoryRequest.projectKey` is nullable en wordt alleen meegestuurd als 'ie aanwezig is.
  Bij géén geconfigureerd project faalt het aanmaken met een leesbare melding i.p.v. een foute key.

### Frontend (`stories_screen.dart`)

- Stories worden vóór het filteren aflopend gesorteerd op het numerieke key-suffix (`_storyNumber`).
- Per rij wordt het passende tijdstempel via de bestaande `formatTimestamp`-helper getoond
  (afgerond=`updatedAt` met terugval op `createdAt`, anders `createdAt`; `-` bij ontbreken).
- De project-`ChoiceChip`s zijn vervangen door repo-`ChoiceChip`s (distinct repo's van de getoonde
  stories + "alle repos") plus een `TextField`-zoekveld op de titel. Repo, zoekterm en buckets
  combineren met AND. Prefs-sleutels: `stories_filter_buckets`, `stories_filter_repo`,
  `stories_filter_search` (oude `stories_filter_project` verwijderd). Een geselecteerde repo die niet
  meer voorkomt valt automatisch terug op "alle repos".
- Het `_CreateStoryDialog` heeft geen Project-dropdown/`projects`-param meer en stuurt geen
  `projectKey` mee.

## Verificatie

- `mvn -f softwarefactory/pom.xml test-compile` en `dashboard-backend` `mvn test-compile`: groen.
- `FactoryDashboardServiceTest`: 30 tests groen (incl. nieuwe optionele-projectKey-test).
- `BridgeApiControllerTest` (dashboard-backend): 12 tests groen.
- `PostgresTrackerClientTest` vereist Docker/Testcontainers en draait in CI (lokaal geen Docker).
- Flutter: geen lokale Flutter-SDK; frontend geverifieerd via statische review, CI draait de analyze/build.

## Review (SF-818, reviewer)

Volledige story-diff `git diff main...HEAD` beoordeeld. Akkoord.

- [info] Backend: `createdAt`/`updatedAt` als `OffsetDateTime?` (default null) op `TrackerIssueFields`,
  gelezen in `PostgresTrackerClient.mapRow` + `ISSUE_COLUMNS`. Geen `TrackerField`, dus terecht buiten de
  exhaustieve `applying`-blokken; YouTrack-backend blijft compileren dankzij default null.
- [info] `resolveProjectKey` spiegelt bestaande `createNightlyStory`-logica (ensureConfiguredProjects →
  `FactorySecrets.youTrackProjects`) maar faalt luid met `error(...)` bij geen enkel project i.p.v. een
  hardcoded "SF" — nette keuze. Signatuurwijziging `String?` breekt de legacy `FactoryDashboardController`
  niet (String is assignbaar aan String?).
- [info] Frontend: aflopende sortering via `_storyNumber` (niet-numerieke suffix → onderaan), repo-/zoekfilter
  met AND-combinatie, persistente prefs, en robuuste tijdstempel (finished→updatedAt met terugval createdAt,
  overige→createdAt; lege waarde → `formatTimestamp` geeft `-`). Verdwenen repo valt terug op "alle repos".
- [info] Tests: optionele-projectKey-default (`FactoryDashboardServiceTest`) en tijdstempels
  (`PostgresTrackerClientTest`, Testcontainers/CI, transitionIssue zet `updated_at=now()`) afgedekt.
- [info] Spec `docs/factory/ux/screens/stories.md` consistent bijgewerkt (sortering, filters, dialoog).
- [info] Geen scope creep; oude HTML-dashboard bewust ongemoeid. Flutter niet lokaal gedraaid (CI dekt analyze/build).

## Review (SF-818, 2026-07-08)

Volledige story-diff (`git diff main...HEAD`) beoordeeld. Akkoord.

- **AC1 sortering** – `_storyNumber()` + aflopende sort op de volledige lijst vóór filtering: correct, filter-onafhankelijk.
- **AC2 tijdstempels** – backend exposet `createdAt`/`updatedAt` (`OffsetDateTime?`, geen `TrackerField`, dus geen when-blokken geraakt); frontend toont afgerond→`updatedAt` (fallback `createdAt`), anders `createdAt`, robuust via bestaande `formatTimestamp`. V15 heeft de kolommen al; geen migratie nodig.
- **AC3/4/5 repo-/zoekfilter** – repo-filter op distinct `_repoOf` (fields.repo → run.targetRepo, gelijk aan tile), case-insensitive titel-zoek, AND-combinatie met buckets, prefs `stories_filter_repo`/`stories_filter_search` vervangen het oude `stories_filter_project`. Stale repo-pref valt netjes terug op "alle repos".
- **AC6 Project-veld weg** – dropdown verwijderd, geen `projectKey` meer verstuurd; backend `resolveProjectKey` valt terug op enig geconfigureerd project, faalt leesbaar bij geen project. Oude HTML-`FactoryDashboardController` blijft compileren (non-null String past in `String?`).
- **AC7 tests** – `FactoryDashboardServiceTest` (optionele projectKey-default) en `PostgresTrackerClientTest` (tijdstempels + `updated_at` schuift op bij transitie) toegevoegd; overige callers ongewijzigd.
- **Specs** – `docs/factory/ux/screens/stories.md` consistent bijgewerkt (sortering, filters, tijdstempel, dialoog zonder Project).

Bevindingen:
- [suggestie] `_setSearch` schrijft bij elke toetsaanslag naar SharedPreferences (geen debounce). Functioneel prima, minor.
- [info] `data['projects']` blijft in de response maar wordt frontend niet meer gebruikt — bewust buiten scope gelaten.
