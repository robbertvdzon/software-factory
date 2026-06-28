# SF-449 - Worklog

Story-context bij eerste pickup:
Inconsistente patronen opsporen en gladstrijken

Spoor in de Kotlin-code van de factory (primair softwarefactory/src/main/kotlin, evt. agentworker/dashboard-backend bij evidente afwijkingen) inconsistente patronen op (naamgeving, structuur, error-handling, logging, API/HTTP-conventies, dubbel-opgeloste problemen) en breng ze in lijn met de meerderheidsnorm en de conventies uit development.md (expliciete imports, geen wildcard-imports, SF_-prefix, geredigeerde secrets). Strikt gedrag-neutraal: bij twijfel niet wijzigen of in error. Wijzig GEEN e2e-/integratietests; verslechter de detekt-meetlat (quality/run.sh) niet en los inconsistenties niet op via @Suppress. Houd de diff klein/reviewbaar, voer zelf-review uit en werk docs/stories/worklog/SF-449-worklog.md bij met bevindingen (of onderbouwde no-op).

Stappenplan:
[x]: read issue and target docs
[x]: inconsistenties inventariseren
[x]: implement requested changes (gedrag-neutraal)
[x]: run relevant tests
[x]: update story-log with results

## Bevindingen (inventarisatie)

Onderzocht: softwarefactory/src/main, agentworker/src/main, dashboard-backend/src/main.

- **Wildcard-imports**: geen project-interne (of andere) wildcard-imports in
  main-code. Conventie uit `development.md` wordt al nageleefd -> niets te doen.
- **Logger-declaraties**: norm is `LoggerFactory.getLogger(javaClass)` voor
  reguliere klassen en `getLogger(<Class>::class.java)` in companion objects (dat
  laatste is bewust correct, geen inconsistentie). Geen afwijkers -> niets te doen.
- **Naamgeving (klassen/functies/variabelen/packages)**: consequent camelCase,
  geen snake_case-afwijkers. Nederlands in tekst/UI-strings is consistent en
  intentioneel -> niets te doen.
- **HTTP/ResponseEntity-conventies in controllers**: consistent
  (`ResponseEntity.ok()`, `status(SEE_OTHER)` voor redirects) -> niets te doen.
- **String-templates vs `+`-concatenatie**: templates zijn de norm (~176 vs ~15).
  De resterende `+`-gevallen zijn deels idiomatisch (regex-bouw, multiline-opbouw)
  en omzetten levert geen detekt-winst en wel ruis op -> bewust met rust gelaten
  (geen duidelijke veiligheidswinst, diff klein houden).
- **Error-handling (`throw` vs Kotlin-builtins)**: de norm voor generieke
  null-/state-guards is `error(...)` (13x) / `require(...)`; raw
  `throw IllegalStateException(...)` is de minderheid. Domeinspecifieke excepties
  (`YouTrackApiException`, `GitHubClientException`, `MissingTrackerFieldException`,
  `ResponseStatusException`) zijn bewust en blijven staan. De
  `?: throw IllegalArgumentException(...)`-gevallen zijn NIET omgezet: `error()`
  zou het exceptietype IAE->ISE veranderen (semantische wijziging) -> bij twijfel
  niet wijzigen.

## Doorgevoerde wijzigingen (gedrag-neutraal)

Drie raw `throw IllegalStateException("...")`-aanroepen gelijkgetrokken naar de
codebase-norm `error("...")`. `error(msg)` gooit per Kotlin-stdlib exact een
`IllegalStateException(msg)`, dus zowel het exceptietype als de melding blijven
byte-identiek - puur vorm, geen gedrag.

1. `softwarefactory/.../runtime/workspaces/StoryWorkspaceService.kt` - conflict-
   marker faal-vangnet.
2. `dashboard-backend/.../dashboard/api/DashboardController.kt` - IntelliJ-open
   timeout.
3. `dashboard-backend/.../dashboard/api/DashboardController.kt` - IntelliJ-open
   faalt.

Effect op de detekt-meetlat (`quality/run.sh`): dit reduceert
`UseCheckOrError`-findings (of houdt ze gelijk) en verslechtert de score niet;
er is niets met `@Suppress` weggewerkt.

## Verificatie

- `mvn -f dashboard-backend/pom.xml test-compile` -> groen.
- `mvn -f softwarefactory/pom.xml test-compile` -> groen.
- `mvn -f softwarefactory/pom.xml test -Dtest=StoryWorkspaceServiceTest` -> groen.
  Bestaande test `syncAfterAgent fails when merge conflict markers remain
  unresolved` verwacht `IllegalStateException` en blijft slagen (error() gooit
  exact dat type), wat de gedrag-neutraliteit bevestigt.
- `mvn -f dashboard-backend/pom.xml test` (volledige suite) -> groen.

Geen e2e-/integratietests gewijzigd. Geen nieuwe unittests nodig: de wijziging is
een 1-op-1 vorm-refactor met identiek exceptietype/-melding, gedekt door de
bestaande assertie hierboven.
