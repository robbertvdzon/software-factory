# SF-903 - Orchestrator-poller: zelfopgewekte wake-lus door onvoorwaardelijke transitionIssue op terminale subtasks

## Story

Orchestrator-poller: zelfopgewekte wake-lus door onvoorwaardelijke transitionIssue op terminale subtasks

<!-- refined-by-factory -->

## Scope

Twee complementaire idempotentie-fixes om te voorkomen dat afgeronde (terminale) subtasks/stories zichzelf blijven "opwekken" via no-op tracker-writes, waardoor de orchestrator-poller nooit op het bedoelde 60s-vangnet terugvalt:

1. **`SubtaskExecutionCoordinator.advanceSubtaskChain`** (`pipeline/service/SubtaskExecutionCoordinator.kt:417-455`): roep `issueTrackerClient.transitionIssue(...)` alleen aan wanneer de huidige board-status van de betreffende issue daadwerkelijk afwijkt van de doelstatus (`stateDone`). Dit geldt zowel voor de afgeronde subtask (regel 420) als voor de parent-story (regel 443).
2. **`PostgresTrackerClient.transitionIssue`** en **`PostgresTrackerClient.updateIssueFields`** (`tracker/clients/PostgresTrackerClient.kt:120-135` en `:234-241`): voeg een no-op-guard toe die de DB-write (en dus `publishStateChanged`/`FactoryStateChangedEvent`) overslaat wanneer de nieuwe waarde(n) gelijk zijn aan de huidige waarde(n) in de rij (`status` resp. de gewijzigde kolommen).

Buiten scope: wijzigingen aan `OrchestratorPoller.sleepUntilDeadlineOrWake`, aan `findAiIssues`/de top-50-selectie, of aan het event-driven wake-mechanisme uit SF-896 zelf — die blijven ongewijzigd en profiteren automatisch van deze fix doordat `updated_at` van afgeronde issues niet meer wordt gebumpt.

## Acceptance criteria

- Wanneer een subtask/parent-story al de doelstatus heeft (bv. al 'Done'), roept `advanceSubtaskChain` `transitionIssue` niet nogmaals aan voor die issue.
- `PostgresTrackerClient.transitionIssue` doet geen DB-write en publiceert geen `FactoryStateChangedEvent` wanneer de opgegeven `statusName` gelijk is aan de huidige `status` van de rij; `updated_at` blijft in dat geval ongewijzigd.
- `PostgresTrackerClient.updateIssueFields` doet geen DB-write en publiceert geen `FactoryStateChangedEvent` wanneer alle opgegeven veldwaarden al gelijk zijn aan de huidige waarden in de rij; `updated_at` blijft in dat geval ongewijzigd. Als minstens één veld wél wijzigt, wordt de update normaal uitgevoerd (inclusief de ongewijzigde velden, zoals nu).
- Bij een daadwerkelijke statuswijziging (of veldwijziging) verandert het gedrag niet: de write gebeurt, `updated_at` wordt gezet en het event wordt gepubliceerd, zoals vóór deze fix.
- Er is (unit)testdekking voor: (a) `advanceSubtaskChain` die geen dubbele `transitionIssue`-call doet als status al 'Done' is, voor zowel de subtask als de parent; (b) `PostgresTrackerClient.transitionIssue`/`updateIssueFields` die bij een no-op geen `updated_at`-bump en geen event-publish veroorzaken, en bij een echte wijziging wél.
- Bestaande tests (o.a. `PostgresTrackerClientTest.kt`) blijven slagen zonder gedragswijziging voor het niet-no-op-pad.
- Resultaat is functioneel te beschrijven als: een reeds afgeronde subtask/story die verder niet wijzigt, blijft na haar laatste echte wijziging met rust — `updated_at` stopt met bewegen, ze valt uit de 'recent'-lijst van `findAiIssues`, en de poller keert terug naar het 60s-interval totdat er een echte wijziging plaatsvindt (nieuwe comment, mens-actie, agent-run klaar).

## Aannames

- De no-op-guard in `updateIssueFields` vergelijkt per opgegeven veld de nieuwe waarde met de huidige DB-waarde vóór de update (bv. via een `SELECT` van de huidige rij, of door de `UPDATE` te herformuleren met een `WHERE`-conditie die alleen bij een echte wijziging matcht); de exacte implementatietechniek is aan de developer, zolang het resultaat (geen write/event bij volledige no-op) klopt.
- "Gelijk" voor status/velden betekent een exacte waardevergelijking (geen normalisatie/trimming nodig), consistent met hoe deze velden nu al worden opgeslagen en vergeleken elders in de codebase.
- De bestaande semantiek van `advanceSubtaskChain` (wel/niet resetten van de volgende subtask, wel/niet story-run sluiten) verandert niet — alleen de onvoorwaardelijke `transitionIssue`-aanroepen worden voorwaardelijk gemaakt.
- Er is geen aparte migratie of schema-wijziging nodig; dit is een pure code-fix binnen bestaande tabellen/kolommen.

## Eindsamenvatting

## Eindsamenvatting SF-903 — Idempotente transitionIssue/updateIssueFields tegen zelfopgewekte wake-lus

**Wat is gebouwd**
Twee complementaire fixes om te voorkomen dat afgeronde subtasks/stories zichzelf blijven "opwekken" via no-op tracker-writes:

1. `SubtaskExecutionCoordinator.advanceSubtaskChain` roept `transitionIssue(..., stateDone)` nu alleen aan als de status nog niet 'Done' is — zowel voor de afgeronde subtask (status stond al op het meegegeven `TrackerIssue`, geen extra lookup nodig) als voor de parent-story (via een `getIssue`-check).
2. `PostgresTrackerClient.transitionIssue` en `updateIssueFields` slaan de DB-`UPDATE` (en dus het publiceren van `FactoryStateChangedEvent`) nu over wanneer de nieuwe waarde(n) al gelijk zijn aan de huidige rij. Dit is opgelost met een `IS DISTINCT FROM`-conditie in de WHERE-clausule; het event wordt alleen gepubliceerd als er daadwerkelijk rijen zijn geraakt.

**Gemaakte keuzes**
- Voor `updateIssueFields` is gekozen voor een OR-combinatie van `IS DISTINCT FROM` over alle opgegeven velden: zodra minstens één veld écht wijzigt, wordt de volledige update (incl. ongewijzigde velden) alsnog uitgevoerd — exact zoals voorheen. Alleen een volledige no-op wordt overgeslagen.
- Geen aparte migratie of schema-wijziging nodig; pure code-fix binnen bestaande tabellen.
- Geen wijzigingen aan `OrchestratorPoller`, `findAiIssues` of het event-driven wake-mechanisme uit SF-896 — die profiteren automatisch doordat `updated_at` van afgeronde issues niet meer wordt gebumpt.

**Wat is getest**
- Nieuwe unit tests in `OrchestratorSubtaskChainTest` (15/15 groen): geen dubbele `transitionIssue`-call bij een reeds-Done subtask/parent, wél bij een echte transitie.
- Nieuwe tests in `PostgresTrackerClientTest`: no-op geeft geen `updated_at`-bump en geen event; echte wijziging (ook gemengd, één ongewijzigd + één gewijzigd veld) geeft wél beide.
- Volledige suite: 438 tests, 0 failures. Twee bekende, niet-gerelateerde errors (`PostgresTrackerClientTest` vereist Docker/Testcontainers, niet beschikbaar in reviewer/tester-omgeving; `ModulithArchitectureTest` faalt ook al op een schone `main`-checkout — beide pre-existing, geen regressie door deze story).

**Bewust niet gedaan**
- Geen wijzigingen aan `docs/factory/*.md`-specs: interne idempotentie-fix zonder extern zichtbaar gedrag.
- `PostgresTrackerClientTest` kon niet lokaal draaien door ontbrekende Docker-daemon in de sandbox-omgeving; de nieuwe testcases zijn wel statisch geverifieerd op correctheid en dekken de acceptatiecriteria. CI dient deze test alsnog live uit te voeren.

Geen openstaande vragen richting PO.
