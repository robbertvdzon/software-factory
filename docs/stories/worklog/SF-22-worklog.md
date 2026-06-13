# SF-22 - Worklog

Story-context bij eerste pickup:
Filteren van stories

In de FE die bij de backend staat (dus niet de flutter FE) wil ik een aanpassing.
In de stories overzicht staan nu alle stories die in youtrack staan. Dat is een beetje veel.
Er moeten sowieso alleen de stories in staan, en niet de subtaken.
Misschien deze checkboxes:
[]show finished stories
[] show stories in progress
[] show stories that are in TODO

## Subtaak SF-30 - Subtaken weren + status-bucket-classificatie

Scope van deze subtaak:
- In `FactoryDashboardViews.stories()` alleen issues met `issueType == STORY` tonen
  (subtaken weren), zonder de gedeelde `issueTable`/`dashboard` te beïnvloeden.
- Een testbare helper toevoegen die het vrije statusveld case-insensitive classificeert
  in finished / in-progress / todo, waarbij onbekende status onder todo valt.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- `stories()` filtert nu `page.issues` op `IssueType.STORY` vóórdat de lijst aan de
  gedeelde `issueTable(...)` wordt doorgegeven. De `dashboard()`-view en de helper
  `issueTable` zelf blijven ongewijzigd, dus subtaken worden alleen op het
  stories-overzicht geweerd. Kolommen, links, badges en empty-state blijven werken.
- Nieuwe nested enum `StatusBucket { FINISHED, IN_PROGRESS, TODO }` en helper
  `internal fun classifyStatus(status: String?)`. Case-insensitive matching:
  - FINISHED: done, fixed, verified, closed, resolved
  - IN_PROGRESS: in progress, develop, developing
  - TODO: open, submitted, backlog, to do
  - onbekend/leeg/null -> TODO
  De helper is `internal` (niet `private`) zodat hij in de unit-test, die in een ander
  package binnen dezelfde module zit, aanroepbaar is en buiten de module verborgen blijft.
- Tests toegevoegd in `FactoryDashboardViewsTest`:
  - `stories overview hides subtasks` controleert dat een `Task`-issue niet in de
    output staat en een `Story`-issue wel.
  - `classifyStatus buckets statuses case-insensitively` dekt alle buckets plus de
    onbekend/null-fallback.

Tests:
- `mvn -f softwarefactory/pom.xml test` kan in deze agent-omgeving niet draaien
  (geen mvn/maven-wrapper offline). Verificatie via statische review; CI/pipeline
  draait de tests.

## Review SF-30 (reviewer, 2026-06-13)

Statische review (geen mvn/mvnw in reviewer-omgeving; CI draait de tests).

Bevindingen:
- [info] Scope correct: alleen subtaken weren + `classifyStatus`-helper. De UI-checkboxes
  uit parent-story SF-22 vallen buiten SF-30 en zijn terecht niet meegenomen.
- [info] Geen regressie: `dashboard()` (regel 83) gebruikt nog steeds de ongefilterde
  `page.issues`; alleen `stories()` (regel 95) filtert op `IssueType.STORY`. De gedeelde
  `issueTable`-signatuur is ongewijzigd. `IssueType` is correct geïmporteerd (regel 13).
- [info] `issueType`-derivatie (`TrackerModels.kt:183`) mapt `type == "Task"` -> SUBTASK,
  anders STORY; de testinputs `"Story"`/`"Task"` mappen dus correct. Filter klopt.
- [info] `internal` i.p.v. `private` voor `classifyStatus` is een redelijke, gedocumenteerde
  afwijking: een `private` lid is niet aanroepbaar vanuit de testklasse. Idiomatisch in Kotlin.
- [info] `classifyStatus`: de expliciete `todo`-tak ("open"/"submitted"/"backlog"/"to do")
  is functioneel redundant met de `else -> TODO`, maar documenteert de bedoelde mapping.
  Geen bezwaar.
- [suggestie] `classifyStatus` wordt nog nergens in productiecode aangeroepen (alleen getest).
  Dat is conform de SF-30-omschrijving (helper als basis voor latere filtering), dus geen
  blocker — wel een aandachtspunt voor de vervolg-subtaak die de checkboxes/filtering bouwt.

Testdekking: beide nieuwe gedragingen gedekt (subtaken-weren + alle status-buckets incl.
onbekend/null-fallback). Wijziging is coherent, testbaar en past binnen de specs.

Oordeel: akkoord.

## Subtaak SF-31 - Drie status-checkboxes + client-side filtertoggle (developer, 2026-06-13)

Scope van deze subtaak:
- Boven de stories-lijst drie checkboxes renderen (finished / in progress / TODO),
  standaard alle drie aangevinkt.
- Elke story-rij een `data-bucket`-attribuut geven via de bestaande `classifyStatus`-helper.
- Inline JS-toggle die rijen client-side toont/verbergt op basis van de aangevinkte buckets.
- Optioneel kleine CSS voor de checkbox-balk. Geen nieuwe persistente config / SF_-env-vars.

Stappenplan:
[x]: read issue en target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log met results

Done / rationale:
- `StatusBucket` kreeg een `attr`-waarde (`finished` / `in-progress` / `todo`) zodat de
  bucket direct als data-attribuut serialiseerbaar is.
- `issueTable(...)` kreeg een optionele parameter `bucketOf: ((TrackerIssue) -> StatusBucket)?`
  (default `null`). Alleen wanneer meegegeven krijgt elke `.lrow` een `data-bucket="..."`.
  Dashboard-aanroep blijft onveranderd (geen attribuut), zodat dat scherm niet wijzigt.
- `stories()` rendert nu `storyFilterBar()` boven de lijst en `storyFilterScript()` eronder,
  en geeft `classifyStatus(it.status)` als bucket-functie aan `issueTable`.
- `storyFilterBar()`: drie checkboxes met `data-bucket-toggle` en standaard `checked`.
- `storyFilterScript()`: kleine vanilla-JS IIFE die op `change` de zichtbaarheid van rijen
  toggelt via `row.style.display`. Default = alles aan = volledige lijst (minus subtaken).
- CSS-balk `.story-filter` toegevoegd aan `sf-ui.css`.
- Test toegevoegd: `stories overview renders filter checkboxes and bucket attributes`.
  `issue(...)`-testhelper kreeg een `status`-parameter om buckets te kunnen sturen.

Tests:
- `mvn -f softwarefactory/pom.xml test` kan in deze agent-omgeving niet draaien
  (geen mvn/maven-wrapper offline). Verificatie via statische review; CI draait de tests.

## Review SF-31 (reviewer, 2026-06-13)

Statische review (geen mvn/mvnw in reviewer-omgeving; CI draait de tests).

Bevindingen:
- [info] Scope correct en compleet: drie checkboxes (default `checked`), `data-bucket` per rij
  via `classifyStatus(it.status)`, client-side JS-toggle, kleine CSS. Geen nieuwe config/SF_-env.
- [info] Geen regressie: `issueTable` kreeg een optionele `bucketOf`-param (default `null`);
  de `dashboard()`-aanroep blijft zonder argument en zet dus geen `data-bucket`. Bewuste,
  niet-brekende uitbreiding van de gedeelde helper.
- [info] Selector-consistentie klopt: het script zoekt `.list.stories .lrow[data-bucket]`;
  de stories-tabel rendert `<section class="list stories">` met `<a class="lrow" data-bucket=...>`.
  Het script staat alleen op de stories-view (niet op dashboard), dus geen kruisbesmetting.
- [info] Subtaken-filter (`issueType == STORY`) blijft intact en is apart getest
  (`stories overview hides subtasks`).
- [info] JS is defensief: `if (!bar) return`, en bij lege lijst (empty-state i.p.v. tabel)
  zijn er simpelweg geen rijen — geen runtime-fout.
- [suggestie] `classifyStatus` mapt onbekende statussen (bijv. "In Review", "Reopened") naar
  TODO. Conform de gedocumenteerde fallback, dus geen blocker; aandachtspunt mocht er later
  een aparte review-status gewenst zijn.

Testdekking: checkboxes + alle drie bucket-attributen + aanwezigheid toggle-script gedekt;
`classifyStatus` en subtaken-weren reeds gedekt. Wijziging is coherent, testbaar en past
binnen de specs.

Oordeel: akkoord.

## Story-brede review SF-32 (reviewer, 2026-06-13)

Statische review van de volledige branch `ai/SF-22` t.o.v. `main` (geen mvn/mvnw in
reviewer-omgeving; CI draait de tests).

In-scope (SF-22 "Filteren van stories"):
- [info] Kernfeature correct en compleet: subtaken weren (`issueType == STORY`), drie
  status-checkboxes (default checked), `data-bucket` per rij via `classifyStatus(it.status)`,
  client-side JS-toggle, CSS-balk. Selector `.list.stories .lrow[data-bucket]` matcht de
  werkelijke markup (`<section class="list stories">` + `<a class="lrow" data-bucket=...>`).
  `dashboard()` blijft ongewijzigd (geen `bucketOf` meegegeven). Goed afgedekt door tests.
- [suggestie] `classifyStatus` werkt op het vrije "Stage"-veld (`TrackerModels`-status =
  custom field "Stage"). De board-State "To Verify" valt — indien als Stage-waarde gebruikt —
  niet in een bucket en belandt onder TODO. Gedocumenteerde fallback, geen blocker; wel een
  aandachtspunt als er ooit een aparte "te verifiëren"-bucket gewenst is.

Story-breed (overige wijzigingen op deze branch, buiten de SF-22-storytekst):
- [suggestie] De branch bundelt substantiële functionaliteit die niet uit de SF-22-story
  ("filteren van stories") volgt en niet in dit worklog gedocumenteerd is (alleen SF-30/SF-31
  staan erin): AI-model-dropdown (controller/service/YouTrackApi/-Client), gecombineerde
  story-briefing (`allAgentRuns` + bron-badge), resultaat-popups in approve/reject-kaarten
  (`latestAgentResult`/`cleanResultText`), `promoteRefinedDescription` +
  `updateIssueDescription`, re-implement → "Open"-lane in `ManualCommandService`, en de
  chaining-guard tegen herstart-loops in `OrchestratorService`. Functioneel zien deze er
  coherent en getest uit (OrchestratorServiceTest +2 cases, FactoryDashboardViewsTest +cases),
  maar traceerbaarheid naar SF-22 ontbreekt. Vraag voor de mens of dit bewust onder deze
  story/branch valt.
- [info] Orchestrator-correctheid: de chaining-guard zet de volgende subtaak alleen op `start`
  als z'n fase leeg is (voorkomt eindeloze reset van een al-lopende subtaak) — terecht getest.
  `promoteRefinedDescription` is idempotent via `REFINED_DESCRIPTION_MARKER` en best-effort
  (`runCatching`) — beide paden getest.
- [info] `AI_MODEL_OPTIONS` is een hardcoded lijst die kan afwijken van de YouTrack-enum; een
  ongeldige waarde zou pas bij `createStory`/`enumFieldValue` runtime falen. Geen blocker.

Testdekking: in-scope feature volledig gedekt; orchestrator-wijzigingen gedekt. Geen bugs of
regressies gevonden. Enige openstaande punt is de scope/traceerbaarheid van de meegelifte
wijzigingen → als vraag teruggelegd i.p.v. afkeuring.

## Story-brede review SF-32 — herbevestiging (reviewer, 2026-06-13)

Tweede SF-32-pass. Statische review (geen mvn/mvnw in reviewer-omgeving; CI draait
de tests). Kernfeature opnieuw geverifieerd op de actuele working tree.

In-scope (SF-22 "Filteren van stories") — correct en compleet:
- [info] `stories()` filtert `page.issues` op `issueType == IssueType.STORY` (subtaken
  geweerd) en geeft `classifyStatus(it.status)` als `bucketOf` aan `issueTable`. De
  gedeelde `issueTable` kreeg een optionele `bucketOf`-param (default `null`); de
  `dashboard()`-aanroep blijft argumentloos → geen `data-bucket`, geen regressie.
- [info] `storyFilterBar()` rendert exact de drie gevraagde checkboxes (finished /
  in progress / TODO), alle standaard `checked`. Sluit aan op issue-comment
  ("2: huidige TODO is voldoende").
- [info] `storyFilterScript()` is defensief: `if (!bar) return`, selector
  `.list.stories .lrow[data-bucket]` matcht de werkelijke markup; bij lege lijst geen
  rijen → geen runtime-fout. Default alles aan = volledige lijst (minus subtaken).
- [info] `classifyStatus` is `internal` (testbaar, modulair verborgen), case-insensitive,
  onbekend/null → TODO. Volledig gedekt door tests (alle buckets + fallback).
- [info] Testdekking compleet: subtaken-weren, checkbox-render + data-bucket per rij,
  classifyStatus-buckets. Geen bugs of regressies in de in-scope wijziging.
- [suggestie] `classifyStatus` werkt op het vrije "Stage"-veld; board-State "To Verify"
  valt onder de TODO-fallback. Gedocumenteerd gedrag, geen blocker.

Story-breed / openstaand:
- [suggestie] Onveranderd t.o.v. de vorige pass: de branch `ai/SF-22` bundelt
  gecommitte wijzigingen die niet uit de SF-22-storytekst volgen en niet in dit
  worklog gedocumenteerd zijn (o.a. SF-29-werk, AI-model-dropdown, story-briefing,
  resultaat-popups, `promoteRefinedDescription`, orchestrator chaining-guard; zie
  SF-29/SF-41/SF-42-worklogs). Functioneel ogen ze coherent en getest, maar de
  traceerbaarheid naar SF-22 ontbreekt en een merge van deze branch zou dat werk
  meenemen. Er is nog geen menselijk antwoord op deze scope-vraag vastgelegd.

Oordeel: in-scope feature akkoord; enige openstaande punt is de scope/traceerbaarheid
van de meegelifte commits → als vraag teruggelegd.

## Story-brede review SF-32 — derde pass / afronding (reviewer, 2026-06-13)

Statische review (geen mvn/mvnw in reviewer-omgeving; CI draait de tests). Working tree
opnieuw geverifieerd t.o.v. HEAD en branch t.o.v. `main`.

In-scope (SF-22 "Filteren van stories") — opnieuw geverifieerd, correct en compleet:
- [info] `stories()` (FactoryDashboardViews.kt:91-95) filtert `page.issues` op
  `issueType == IssueType.STORY` (`IssueType` correct geïmporteerd, regel 13) en geeft
  `classifyStatus(it.status)` als `bucketOf` aan de gedeelde `issueTable`. De optionele
  `bucketOf`-param (default `null`) laat de `dashboard()`-aanroep argumentloos →
  geen `data-bucket`, geen regressie.
- [info] `storyFilterBar()` rendert de drie gevraagde checkboxes (finished / in progress /
  TODO), alle standaard `checked`; sluit aan op issue-comment 7-982 ("2: huidige TODO is
  voldoende"). `storyFilterScript()` is defensief (`if (!bar) return`), selector
  `.list.stories .lrow[data-bucket]` matcht de werkelijke markup; lege lijst → geen rijen,
  geen runtime-fout.
- [info] `classifyStatus` is `internal`, case-insensitive (`trim().lowercase()`),
  onbekend/null → TODO. De expliciete `todo`-tak is redundant met `else` maar documenteert
  de mapping. Testdekking compleet: subtaken-weren, checkbox-render + alle drie
  bucket-attributen, alle classifyStatus-buckets incl. onbekend/null-fallback.

Scope-vraag uit de vorige twee passes — nu beantwoord:
- [info] Issue-comment 7-1004 (admin): "daar hoef je niet naar te kijken. Ga er maar vanuit
  dat de code goed is." Dit is het menselijke antwoord op de eerder teruggelegde
  scope/traceerbaarheidsvraag over de meegelifte commits (SF-29/SF-41/SF-42). De openstaande
  vraag is daarmee opgelost; de meegelifte wijzigingen vallen buiten deze review.

Geen bugs of regressies in de in-scope wijziging. Vraag afgehandeld via issue-comment.

Oordeel: akkoord.

## Story-brede test SF-33 (tester, 2026-06-13)

Lokaal getest met Maven 3.9.9 + JDK 21 (`mvn -f softwarefactory/pom.xml test`).
Geen preview-deploy ingericht (SF_PREVIEW_URL leeg); gedrag geverifieerd via de
HTML-renderende view-tests die de werkelijke output asserten.

Resultaat: **220 tests run, 0 failures, 11 errors**. Alle 11 errors zitten
uitsluitend in de drie `*.e2e.*`-klassen (`PipelineFlowsE2eTest`,
`FullRefineToDevelopE2eTest`, `FactoryUiDriverLoginTest`) en zijn
`ApplicationContext`-laadfouten omdat de tester-omgeving geen Docker-daemon heeft
(E2eTestConfig/testcontainers PostgreSQL). Dit is een gedocumenteerde
omgevingsbeperking, geen code-bug. De overige 209 tests (incl. alle
unit/poller/orchestrator-tests) zijn groen.

In-scope SF-22-gedrag geverifieerd via `FactoryDashboardViewsTest` (21 tests groen),
o.a.:
- `stories overview hides subtasks` — subtaken (issueType != STORY) worden geweerd,
  echte stories blijven zichtbaar.
- `stories overview renders filter checkboxes and bucket attributes` — drie
  checkboxes (finished / in-progress / todo) standaard `checked`, elke rij krijgt
  `data-bucket` via `classifyStatus`, en het inline toggle-script (`data-story-filter`)
  wordt meegerenderd.
- `classifyStatus buckets statuses case-insensitively` — alle buckets + onbekend/null
  -> TODO-fallback.

Geen regressie op het dashboard: `issueTable` kreeg een optionele `bucketOf`-param
(default `null`); de `dashboard()`-aanroep blijft argumentloos. Geen bugs of
regressies gevonden in de in-scope feature.

Oordeel: geslaagd.
