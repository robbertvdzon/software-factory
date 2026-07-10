# SF-903 - Worklog

Story-context bij eerste pickup:
Idempotente transitionIssue/updateIssueFields + guard in advanceSubtaskChain

Implementeer twee complementaire idempotentie-fixes tegen de zelfopgewekte wake-lus:

1. SubtaskExecutionCoordinator.advanceSubtaskChain (pipeline/service/SubtaskExecutionCoordinator.kt:417-455): roep transitionIssue(finished.key, stateDone) alleen aan als finished.status != stateDone (status is al beschikbaar op het meegegeven TrackerIssue). Roep transitionIssue(parentKey, stateDone) in de 'geen volgende subtaak'-tak alleen aan als de actuele parent-status (op te halen via issueTrackerClient.getIssue(parentKey)) afwijkt van stateDone. Overige logica (next-subtask bepalen, story-run sluiten) ongewijzigd laten.

2. PostgresTrackerClient.transitionIssue (tracker/clients/PostgresTrackerClient.kt:234-241) en updateIssueFields (:120-136): voeg een no-op-guard toe zodat de UPDATE (en dus publishStateChanged/FactoryStateChangedEvent) wordt overgeslagen wanneer de nieuwe waarde(n) al gelijk zijn aan de huidige rij-waarde(n). Gebruik bv. een WHERE-clausule met 'kolom IS DISTINCT FROM ?' (voor updateIssueFields: OR-combinatie over alle opgegeven velden) en beslis op basis van het aantal geraakte rijen of updated_at wordt gezet en het event wordt gepubliceerd. Bij een echte wijziging blijft het gedrag (alle opgegeven velden schrijven, updated_at zetten, event publiceren) exact zoals nu.

Schrijf bijbehorende unit tests: (a) advanceSubtaskChain doet geen dubbele transitionIssue-call als subtask/parent al 'Done' is, wél bij een echte transitie; (b) PostgresTrackerClientTest.kt-uitbreiding die aantoont dat een no-op transitionIssue/updateIssueFields geen updated_at-bump en geen event-publish veroorzaakt, en dat een echte wijziging beide wél veroorzaakt. Bestaande tests in PostgresTrackerClientTest.kt moeten blijven slagen zonder gedragswijziging op het niet-no-op-pad.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-904 (subtask 1/6 - development)

- `SubtaskExecutionCoordinator.advanceSubtaskChain` (pipeline/service/SubtaskExecutionCoordinator.kt):
  - de `transitionIssue(finished.key, stateDone)`-call gebeurt nu alleen als `finished.status != stateDone`
    (status stond al op de meegegeven `TrackerIssue`, geen extra lookup nodig).
  - de `transitionIssue(parentKey, stateDone)`-call in de 'geen volgende subtaak meer'-tak gebeurt nu
    alleen als `issueTrackerClient.getIssue(parentKey).status != stateDone`.
  - overige logica (next-subtask bepalen, story-run sluiten) is ongewijzigd gebleven.
- `PostgresTrackerClient` (tracker/clients/PostgresTrackerClient.kt):
  - `transitionIssue`: de `UPDATE` heeft nu `AND status IS DISTINCT FROM ?` in de WHERE-clausule; het
    event wordt alleen gepubliceerd als `jdbcTemplate.update(...)` > 0 rijen raakte.
  - `updateIssueFields`: de `UPDATE` heeft nu een OR-combinatie van `<kolom> IS DISTINCT FROM ?` over
    alle opgegeven velden in de WHERE-clausule (dus bij minstens één echte veldwijziging wordt de hele
    update, incl. de ongewijzigde velden, alsnog uitgevoerd — zoals nu); event alleen bij >0 rijen.
  - Bij een echte wijziging blijft het gedrag (write + `updated_at`-bump + event) identiek aan vóór de
    fix; alleen het volledige no-op-pad wordt overgeslagen.
- Tests:
  - `OrchestratorSubtaskChainTest`: twee nieuwe tests — een terminale subtaak met board-status al
    'Done' triggert geen extra `transitionIssue`-call (met en zonder volgende subtaak, incl. de
    parent-guard). Bestaande test `last terminal subtask untags itself and chains to nothing` moest
    een parent-issue in de `FakeTrackerApi`-issuelijst krijgen omdat de nieuwe parent-`getIssue`-call
    anders een `NoSuchElementException` gaf (fake had de parent nooit geseed) — geen gedragswijziging,
    puur test-fixture-fix.
  - `PostgresTrackerClientTest`: twee nieuwe tests (`transitionIssue is a no-op ...`,
    `updateIssueFields is a no-op ...`) die aantonen dat een volledige no-op geen `updated_at`-bump en
    geen extra event geeft, en dat een echte status-/veldwijziging beide wél geeft (ook bij een gemengde
    update met één ongewijzigd + één gewijzigd veld).
- Verificatie: `mvn -pl factory-common -am install -DskipTests` (nodig ivm lege `~/.m2`), daarna
  `mvn -f softwarefactory/pom.xml test-compile` (groen) en `mvn -f softwarefactory/pom.xml test
  -Dtest='!ModulithArchitectureTest,!AgentResultFileCompletionPollerTest,!*Repository*Test,!*E2eTest*'`
  (435 tests, 0 failures). `PostgresTrackerClientTest` (Testcontainers/Docker) en
  `NightlyRepositoriesTest` konden niet lokaal draaien (geen Docker in deze omgeving, bekende
  beperking) — statisch geverifieerd, laat CI ze draaien.
- Geen specs (`docs/factory/*.md`) aangepast: dit is een interne idempotentie-fix zonder
  gedrags-/API-wijziging voor gebruikers of externe contracten.

## Review SF-904

- Diff (`main...HEAD`) beperkt tot exact de beschreven scope: `SubtaskExecutionCoordinator.kt`,
  `PostgresTrackerClient.kt`, bijbehorende tests en dit worklog. Geen scope creep.
- `advanceSubtaskChain`: guard op `finished.status != stateDone` en
  `issueTrackerClient.getIssue(parentKey).status != stateDone` correct geplaatst rond de bestaande
  `transitionIssue`-calls; next-subtask/story-run-logica ongewijzigd. Nieuwe tests dekken beide
  guards (subtask-Done zonder/met volgende subtask, parent-Done) en bestaande fixture-fix
  (`last terminal subtask untags itself...` kreeg een parent-issue) is een pure test-fix, geen
  gedragswijziging.
- `PostgresTrackerClient.transitionIssue`/`updateIssueFields`: WHERE-clausule met
  `IS DISTINCT FROM` correct opgebouwd; args-volgorde in `updateIssueFields` (SET-waarden, issueKey,
  dan changeClauses-waarden in dezelfde volgorde als `update.values` iteratie) klopt omdat
  `TrackerFieldUpdate.of(...)` via `vararg.toMap()` een `LinkedHashMap` teruggeeft — insertion-order
  stabiel tussen de twee `forEach`-iteraties over dezelfde map. Event alleen bij `updated > 0` rijen.
  Bij een echte wijziging (ook gemengd: één ongewijzigd + één gewijzigd veld) blijft het gedrag
  (alle velden schrijven, `updated_at` bumpen, event publiceren) exact zoals voorheen.
- Tests: nieuwe cases in `OrchestratorSubtaskChainTest` en `PostgresTrackerClientTest` dekken zowel
  het no-op-pad (geen bump, geen event) als het echte-wijziging-pad, incl. gemengde velden — conform
  acceptatiecriteria.
- Verificatie: `mvn -pl factory-common,softwarefactory -am test-compile` slaagt schoon (geen
  Docker/Testcontainers-runtime beschikbaar in deze reviewer-omgeving, net als bij de developer-run;
  CI moet `PostgresTrackerClientTest` uitvoeren).
- Specs (`docs/factory/*.md`): geen wijziging nodig/aanwezig — interne idempotentie-fix zonder
  extern zichtbaar gedrag, consistent met de rest van de spec.
- Conclusie: correct, coherent, testbaar en binnen scope. Akkoord.
