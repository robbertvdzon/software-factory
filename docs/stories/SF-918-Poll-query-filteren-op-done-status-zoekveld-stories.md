# SF-918 - Poll-query filteren op done-status + zoekveld stories-overzicht ook op story-key

## Story

Poll-query filteren op done-status + zoekveld stories-overzicht ook op story-key

<!-- refined-by-factory -->

## Scope

**Deel 1 — poll-query filtert niet op done-status**

1. `PostgresTrackerClient.findAiIssues` / `TrackerApi.findWorkIssues`: voeg aan de hoofdquery (de top-N `ORDER BY updated_at DESC`-tak) een `WHERE`-conditie toe die issues met een afgeronde status uitsluit, consistent met `StoryStatusPresenter.classifyStatus` (status genormaliseerd lowercase in `"done", "fixed", "verified", "closed", "resolved"` → uitgesloten). Geen schema-wijziging nodig; de `status`-kolom bestaat al op de `issues`-tabel.
   - De union-tak die non-terminale subtaken forceert (via `subtask_phase NOT IN (...)`) blijft ongewijzigd/behouden, zodat nog actief lopende subtaken van een niet-afgeronde story wel bereikbaar blijven.
   - Zowel story- als subtask-rijen met een afgeronde `status` vallen buiten de selectie.

2. `TrackerStoryApiController.status` (`GET /stories/{key}`): breid de response uit met het board-status-veld en/of een computed done-boolean, zodat consumers (o.a. `tools/sf-story status`) kunnen zien of een story/subtaak echt klaar is, naast de bestaande `phase`. Hergebruik hiervoor bestaande logica/definities (`status`-veld van het issue, en/of `StoryStatusPresenter`-achtige classificatie) in plaats van een nieuwe done-definitie te verzinnen.

**Deel 2 — zoekveld stories-overzicht matcht ook op story-key**

In `dashboard-frontend/lib/screens/stories_screen.dart`:
1. Breid de bestaande filterlogica (rond regel 168-174) uit zodat naast `issue['summary']` ook `issue['key']` (bv. `SF-910`) meegenomen wordt in de substring-match (case-insensitive, net als nu al voor de titel).
2. Optioneel: pas de hint-tekst van het zoekveld (`'Zoek in story-titel'`, rond regel 233) aan zodat duidelijk is dat ook op story-nummer gezocht kan worden.

## Acceptance criteria

**Deel 1**
- Een story waarvan de status (board-lane) al op `"Done"` staat (of een van de overige finished-synoniemen die `classifyStatus` als `FINISHED` classificeert) verschijnt niet meer in het resultaat van `findAiIssues`/`findWorkIssues`, tenzij er nog niet-terminale subtaken aan hangen.
- Een story die nog actief is (status niet finished, of nog subtaken met een niet-terminale `subtask_phase`) blijft gewoon in de poll-selectie zitten — geen regressie op het bestaande gedrag voor lopend werk.
- `GET /stories/{key}` (TrackerStoryApiController.status) geeft in de response, naast het bestaande `phase`-veld, ook het onderliggende `status`/board-lane-veld en/of een expliciete done-boolean terug, zodat een afgeronde story (alle subtaken terminaal, status = Done) via deze API herkenbaar is als klaar en niet langer als "in-progress" wordt gerapporteerd.
- Bestaande tests voor `PostgresTrackerClient`/orchestrator-poller en voor `TrackerStoryApiController` blijven slagen; nieuwe/aangepaste tests dekken minimaal: (a) een afgeronde story valt buiten `findAiIssues`, (b) een story met nog een actieve subtaak blijft erin, (c) de status-endpoint-response bevat het nieuwe status/done-veld correct voor een afgeronde en een lopende story.

**Deel 2**
- Zoeken op een deel van de story-key (bv. `910` of `sf-910`, case-insensitive) levert `SF-910` op in de resultatenlijst van het stories-overzicht.
- Zoeken op (een deel van) de titel blijft ook werken zoals voorheen (geen regressie).
- Bestaande filterlogica (project- en status-bucket-filters) blijft ongewijzigd samenwerken met de uitgebreide zoekfilter.

## Aannames

- "Done" in de zin van deze story betekent: dezelfde classificatie als `StoryStatusPresenter.classifyStatus` gebruikt voor `StatusBucket.FINISHED` (dus niet alleen de letterlijke string `"Done"` die de app zelf schrijft via `BoardState.DONE`, maar ook eventuele legacy/handmatige statuswaarden als `"closed"`/`"resolved"`/`"fixed"`/`"verified"`), zodat de poll-filter en de bestaande dashboard-classificatie niet uit elkaar gaan lopen.
- Voor Deel 1 punt 2 wordt geen nieuwe "done"-definitie bedacht; de developer hergebruikt de bestaande status-classificatie (rechtstreeks het `status`-veld doorgeven, en/of de bestaande `StoryStatusPresenter`-achtige logica) in plaats van een losstaande implementatie.
- De union-tak in `findAiIssues` die niet-terminale subtaken forceert in de resultaten blijft functioneel ongewijzigd; alleen de hoofd-("recent")-tak krijgt de extra done-filter, zodat actieve subtaken van niet-afgeronde stories niet per ongeluk verdwijnen.
- Voor Deel 2 is alleen substring-matching (geen fuzzy/prefix-only matching) vereist, consistent met de huidige titel-zoekfunctie.
- De hint-tekst-aanpassing in Deel 2 is optioneel (expliciet zo genoemd in de issue) en blokkeert de story niet als deze wordt overgeslagen.

## Eindsamenvatting

Ik heb voldoende context (worklog, reviewer-notities, diffs) om de eindsamenvatting te schrijven zonder verdere codewijzigingen.

## Eindsamenvatting SF-918 — Poll-query filteren op done-status + zoekveld stories-overzicht op story-key

**Wat is gebouwd**

*Deel 1 — Poll-query done-filter & status-API*
- Een nieuwe `FinishedStatus`-constante in `core/BoardState.kt` is de single source of truth voor genormaliseerde "afgerond"-statuswaarden (`done, fixed, verified, closed, resolved`). `StoryStatusPresenter.classifyStatus` is hierop omgezet (gedragsneutrale refactor).
- `PostgresTrackerClient.findAiIssues`: de top-N-tak van de UNION-query sluit nu rijen met een afgeronde status uit. De bestaande union-tak die actieve subtaken van niet-afgeronde stories forceert, is ongewijzigd gebleven — actieve subtaken van al-Done-gezette stories blijven dus bereikbaar.
- `TrackerStoryApiController.status` (`GET /api/tracker/stories/{key}`) geeft nu naast `phase` ook `status` (ruw issue-statusveld) en `done` (boolean) terug, voor zowel story- als subtask-rijen.

*Deel 2 — Zoekveld op story-key*
- Het zoekveld in `dashboard-frontend/lib/screens/stories_screen.dart` matcht nu ook op `issue['key']` (case-insensitive substring), naast de titel. Hint-tekst aangepast naar "Zoek in story-titel of storynummer".

**Gemaakte keuzes**
- `FinishedStatus` is bewust in `core` geplaatst (i.p.v. hergebruik van de `internal` `StoryStatusPresenter`) om een omgekeerde module-dependency (`tracker` → `web`) en een architectuurtest-breuk te voorkomen.
- Geen nieuwe "done"-definitie: bestaande statusclassificatie is hergebruikt op zowel poll-filter als status-API.

**Getest**
- Backend: `mvn test-compile` groen; gerichte tests (`TrackerStoryApiControllerTest`, `StoryStatusPresenterTest`, `FactoryDashboardServiceTest`) groen; volledige non-Docker-suite (472 tests) groen op de wijziging na (31 bekende Docker-afhankelijke e2e-fouten, ongerelateerd).
- Nieuwe tests: 2 Testcontainers-tests in `PostgresTrackerClientTest` (afgeronde story buiten selectie; actieve subtaak van afgeronde story blijft erin), nieuw `TrackerStoryApiControllerTest` (status/done voor Done-, In-Progress- en legacy-synoniemstatus, plus bestaande auth-flow), nieuw `StoryStatusPresenterTest`.
- Reviewer heeft de volledige story-diff tweemaal beoordeeld; tweede ronde: geen blockers, akkoord.

**Bewust niet gedaan**
- Geen Flutter widget-test voor de zoekfilter-uitbreiding: er is geen `stories_screen_test.dart` en geen `flutter`-toolchain lokaal beschikbaar (bekende omgevingsbeperking). CI draait `flutter analyze`/`flutter test`; wijziging is statisch gereviewd als kleine, consistente uitbreiding van al geteste filterlogica.
- `PostgresTrackerClientTest` kon lokaal niet draaien (geen Docker/Testcontainers beschikbaar); nieuwe tests volgen exact het patroon van bestaande buurtests en zijn statisch geverifieerd via `test-compile`; CI draait deze wel met Docker.

**Review-traject**
- Eerste review: blocker — `docs/factory/ux/screens/stories.md` beschreef het zoekveld nog als pure titel-zoekfunctie, niet consistent met de nieuwe key-matching (extern zichtbaar gedrag, expliciet onderdeel van de acceptatiecriteria).
- Fix: UX-spec bijgewerkt (zoekveld-beschrijving en Actions-bullet noemen nu ook story-key-matching).
- Herbeoordeling: geen blockers meer, akkoord voor merge.
