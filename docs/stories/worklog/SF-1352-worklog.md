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

## Review-notities (reviewer, SF-1354/SF-1352)

- Gecontroleerd: alleen `docs/adr/template.md`, `docs/adr/0001-kotlin-spring-backend-flutter-frontend.md`
  en dit worklog zijn gewijzigd t.o.v. `main` — geen code-/configwijzigingen, conform scope.
- Template bevat `## Context`, `## Decision`, `## Consequences` + Status/Datum-placeholders, conform AC.
- ADR 0001: poorten (9090/9080), module-namen en rolomschrijvingen (`softwarefactory` orchestrator,
  `dashboard-backend` dunne bridge, `dashboard-frontend` Flutter zonder Maven-module) komen exact
  overeen met `docs/factory/technical-spec.md` (regels 16-41). Status Accepted + datum 2026-07-27 conform AC.
  Geen Options Considered-sectie, zoals afgesproken (retroactieve vastlegging).
- Geen tests/build nodig: pure documentatietoevoeging, geen gedragswijziging.
- Akkoord.

## Test-notities (tester, SF-1355)

- Geverifieerd: `git diff main...HEAD --name-only` = alleen `docs/adr/template.md`,
  `docs/adr/0001-kotlin-spring-backend-flutter-frontend.md` en dit worklog. Geen
  code-/configwijziging.
- Template bevat `## Context`, `## Decision`, `## Consequences` + Status/Datum-plek, conform AC.
- ADR 0001: Status `Accepted`, datum `2026-07-27`. Inhoud (poorten 9090/9080, rollen
  `softwarefactory`/`dashboard-backend`/`dashboard-frontend`) klopt letterlijk t.o.v.
  `docs/factory/technical-spec.md`.
- `.factory/verification.yaml`: enige in-scope command voor deze docs-only diff is
  `repository-documentation-audit` (geen pathPrefixes-restrictie) — `bash tools/audit-documentation`
  → `documentation-audit/v1: PASS` (exit 0). `repository-maven-verify` en de flutter-commands
  zijn out-of-scope (geen overlap met gewijzigde paden onder `softwarefactory/`,
  `dashboard-backend/`, `dashboard-frontend/`, etc.).
- Akkoord.
