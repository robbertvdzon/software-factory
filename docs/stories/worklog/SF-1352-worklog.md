# SF-1352 - Worklog

Story-context bij eerste pickup:
Bootstrap docs/adr/ met sjabloon en ADR 0001

Maak de map docs/adr/ aan met twee nieuwe bestanden, zonder enige andere bestanden te wijzigen (puur documentatie, geen code-/gedragswijziging).

1. docs/adr/template.md - kort ADR-sjabloon met secties `## Context`, `## Decision`, `## Consequences`, plus metadata-plekken voor Status (Proposed/Accepted/Superseded) en Datum, en een titel-/nummerplaceholder.
2. docs/adr/0001-kotlin-spring-backend-flutter-frontend.md - volledig ingevulde ADR volgens het sjabloon, Status: Accepted met datum 2026-07-27:
   - Context: waarom de stack is opgesplitst in Kotlin/Spring-backend (Maven-modules softwarefactory, dashboard-backend) en losstaande Flutter/Dart-frontend (dashboard-frontend, eigen Docker-build buiten Maven), met verwijzing naar docs/factory/technical-spec.md.
   - Decision: Kotlin/Spring Boot voor softwarefactory (orchestrator) en dashboard-backend (dunne bridge-service); Flutter voor dashboard-frontend.
   - Consequences: aparte build-toolchains (Maven/JVM vs. Docker-Flutter-build), aparte deploy-paden/artefacten, noodzaak beide stacks te onderhouden.
   - Geen Options Considered-sectie (retroactieve vastlegging, geen nieuwe afweging).

Geen wijziging aan technical-spec.md of andere bestanden. Geen ADR-index/README tenzij praktisch.

Stappenplan:
[ ]: read issue and target docs
[ ]: implement requested changes
[ ]: run relevant tests
[ ]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
