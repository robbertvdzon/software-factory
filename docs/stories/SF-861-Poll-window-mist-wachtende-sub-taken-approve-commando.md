# SF-861 - Poll-window mist wachtende (sub)taken: approve/commando genegeerd bij >50 recent bijgewerkte issues

## Story

Poll-window mist wachtende (sub)taken: approve/commando genegeerd bij >50 recent bijgewerkte issues

<!-- refined-by-factory -->

## Scope

`PostgresTrackerClient.findAiIssues` (query in `findAiIssues`, aangeroepen door `OrchestratorService.pollOnce` via `TrackerApi.findWorkIssues()`) selecteert alleen de top-N (`ORDER BY updated_at DESC LIMIT 50`) work-issues. Een (sub)taak die wacht op een mens (bv. `SubtaskPhase` niet-terminaal, zoals `manual-approve-needed`/`awaiting-human`/`*-with-questions`) kan buiten deze top-N vallen en blijft dan structureel onverwerkt, ook na een geldig `@factory:command:approve`-comment, omdat `postComment()` geen `updated_at` bijwerkt.

Wijzig de poll-query/aanroep zodat:
1. Issues in een niet-terminale, wacht-op-mens-fase altijd worden meegenomen in de pollset, ongeacht hun positie in `updated_at DESC` (bv. via een UNION van top-N by `updated_at` + alle niet-terminale/wachtende issues, of een gelijkwaardige aanpak binnen `findAiIssues`/`OrchestratorService.pollOnce`).
2. Deze wijziging blijft beperkt tot de Postgres-trackerlaag (`PostgresTrackerClient`, evt. `TrackerApi`-interface) en de poll-aanroep in `OrchestratorService`; geen wijzigingen aan dashboard-queries (die al ruimere windows van 200/500 gebruiken) nodig, behalve indien nodig om windows consistent te maken.
3. Er geen ongelimiteerde/onbegrensde query ontstaat: de niet-terminale subset moet expliciet en beperkt blijven (er zijn altijd weinig open wachtende gates t.o.v. het totaal), de bestaande top-N blijft de basis voor "recent bijgewerkt".
4. Optioneel/secundair: onderzoek of `postComment()` de `updated_at` van het bijbehorende issue kan bumpen, zodat een commando een issue sowieso terug het venster in trekt — dit is een aanvullende, geen vervangende, maatregel.

Buiten scope: het exacte mechanisme achter de waargenomen "~49 issues met identieke `updated_at` binnen 1 seconde" (rapid sequential inserts, vermoedelijk nightly subtask-materialisatie) hoeft niet opgelost te worden in deze story; wel mag de developer dit kort documenteren in het worklog als bevestigd/ontkracht bijeffect van de fix.

## Acceptance criteria

- Een issue (story of subtaak) in een niet-terminale, wacht-op-mens-fase (`SubtaskPhase.isTerminal == false`, incl. manual-approve/awaiting-human/`*-with-questions`-achtige fases) wordt door `OrchestratorService.pollOnce` altijd verwerkt, ook wanneer er meer dan 50 recenter bijgewerkte issues bestaan.
- Een geldig `@factory:command:approve` (of ander commando) op zo'n wachtende gate leidt aantoonbaar tot faseverandering bij de eerstvolgende poll, ongeacht de rangorde op `updated_at`.
- Er bestaat een regressietest (unit- of integratietest op `PostgresTrackerClient`/`OrchestratorService`, gebruikmakend van de bestaande Testcontainers-Postgres-testinfrastructuur) die aantoont: een niet-terminale gate met `updated_at` ver buiten de top-50 + een geldig approve-commando resulteert in verwerking/faseverandering.
- Bestaande gedrag voor de normale poll (top-N by `updated_at`) blijft ongewijzigd voor issues die niet wachten op een mens; geen ongewenste regressie in poll-volume/performance bij grote hoeveelheden terminale issues.
- Alle bestaande tests (inclusief `PostgresTrackerClientTest`, `OrchestratorService`-tests) blijven slagen.

## Aannames

- "Wacht op een mens / niet-terminale fase" wordt geoperationaliseerd via de bestaande `SubtaskPhase.isTerminal`-property (`SubtaskPhase.kt`); voor stories op storyniveau (zonder eigen `isTerminal`) geldt: een story is relevant zolang minstens één van haar subtaken niet-terminaal is, of de story zelf in een wacht-fase staat (bv. `*_WITH_QUESTIONS`/`in-progress` zonder afgeronde keten).
- De oplossing hoeft niet per se `postComment()` aan te passen; het primaire criterium is dat de poll wachtende issues nooit laat "verdwijnen" door de LIMIT, ongeacht of `updated_at` wordt gebumpt.
- Het "onderzoek secundair" deel (herkomst van de ~49 gelijktijdige updates) is informatief/optioneel en blokkeert de acceptatie van deze story niet als het geen concrete fix oplevert.
- Geen wijziging nodig aan `FactoryDashboardService`-queries (200/500-window), aangezien die al ruim boven de poll-limiet liggen; de fix richt zich op het poll-pad, niet op de UI.

## Eindsamenvatting

Ik heb voldoende context (worklog SF-861 met developer/reviewer/tester-secties). Geen code-wijzigingen nodig — alleen de samenvatting opstellen.

**Eindsamenvatting SF-861 — Poll-window mist wachtende (sub)taken: approve/commando genegeerd bij >50 recent bijgewerkte issues**

**Probleem:** `PostgresTrackerClient.findAiIssues` selecteerde alleen de top-50 issues op `updated_at DESC`. Een (sub)taak die op een mens wachtte (bijv. `manual-approve-needed`) kon buiten dat venster vallen zodra er ≥50 andere issues recenter waren bijgewerkt. Zo'n taak bleef daarna structureel onopgemerkt door de poll — zelfs na een geldig `@factory:command:approve`-commando, omdat `postComment()` de `updated_at` niet bijwerkt.

**Oplossing:** De query in `PostgresTrackerClient.findAiIssues` is aangepast naar een `UNION` van (a) de bestaande top-N op `updated_at DESC LIMIT` en (b) alle issues met een niet-terminale `subtask_phase` (echte wachtende subtaken), begrensd door een expliciete `PENDING_SUBSET_LIMIT` van 500 om een ongelimiteerde scan te voorkomen. `UNION` (i.p.v. `UNION ALL`) dedupliceert automatisch op `issue_key`. De terminale fases komen niet uit een hardcoded lijst maar uit `SubtaskPhase.entries.filter { it.isTerminal }` (single source of truth).

**Bewuste keuzes:**
- `TrackerApi` en `OrchestratorService.pollOnce` zijn ongewijzigd gelaten — de fix zit volledig in de queryresultaatset van `findAiIssues`.
- `postComment()` bumpt bewust géén `updated_at` (het optionele/secundaire deel van de story). De hoofdfix maakt dit overbodig, want een wachtende subtaak blijft sowieso in de pollset zolang de fase niet-terminaal is.
- Het secundaire onderzoek naar "~49 issues met identieke `updated_at`" is niet verder uitgediept — buiten scope en niet blokkerend; de fix is orthogonaal aan de oorzaak van dat verschijnsel.
- Geen wijzigingen aan `docs/factory/*` nodig; het is een intern implementatiedetail zonder zichtbaar nieuw gedrag voor gebruikers/UI.

**Getest:**
- 3 nieuwe regressietests in `PostgresTrackerClientTest` (Testcontainers-Postgres): een wachtende niet-terminale subtaak ver buiten de top-N wordt toch meegenomen; een terminale subtaak buiten de top-N blijft terecht uitgesloten (contrastcase, geen regressie); een approve-commando op een stale wachtende subtaak (60 ruis-issues, geen `updated_at`-bump) is zichtbaar in de pollset.
- Volledige testsuite: 420 tests, 0 failures. De 3 nieuwe Testcontainers-tests konden in de dev-/testeromgeving niet lokaal draaien (geen Docker-daemon — bekende omgevingsbeperking), maar zijn inhoudelijk gecontroleerd op logica en dekken de acceptatiecriteria expliciet. CI (met Docker) moet deze bevestigen.
- SQL/param-binding is door zowel reviewer als tester handmatig nagerekend (placeholdervolgorde, NOT-IN/NULL-valkuil, kolomnaam-mapping) — geen bevindingen.

**Oordeel reviewer en tester:** akkoord, geen blockers.
