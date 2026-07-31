# SF-1550 - Worklog

Story-context bij eerste pickup:
workflow_run-gates aanscherpen in beide image-workflows

Scherp de workflow_run-tak van drie if-condities aan zodat image-builds alleen nog starten na een push binnen deze repo.

1. .github/workflows/dashboard-backend-image.yml, job `build`.
2. .github/workflows/dashboard-frontend-image.yml, job `build`.
3. .github/workflows/dashboard-frontend-image.yml, job `build-apk`.

Voeg in elk van die drie condities, naast de bestaande `github.event.workflow_run.conclusion == 'success'` en `github.event.workflow_run.head_branch == 'main'`, toe:

    github.event.workflow_run.event == 'push' &&
    github.event.workflow_run.head_repository.full_name == github.repository

Behoud exact de bestaande structuur `github.event_name == 'workflow_dispatch' || (<alle vier de workflow_run-eisen>)`, zodat handmatig dispatchen van de image-workflow zelf blijft werken. Let op de gefoldede `>-`-scalar: elke vervolgregel moet op een operator eindigen, anders vouwt YAML de expressie stuk.

LET OP: de regelnummers uit de story lopen één regel voor op de werkelijkheid (backend build: 24-26, frontend build: 27-29, frontend build-apk: 69-71). De job-namen zijn leidend.

Buiten scope, niet aanraken: de `bump-manifests`-jobs (al afgeschermd via `needs: [build]`), verify.yml, de permissions-blokken, de checkout-refs, .github/scripts/bump-images.sh en alle applicatiecode. Buiten de drie if-blokken moet de diff leeg zijn.

Er worden geen geautomatiseerde tests toegevoegd: de repo kent geen testharnas voor workflow-condities. Zelfcontrole vóór afronden: beide bestanden zijn geldige YAML, `bash tools/verify-repository` slaagt, en de diff bevat uitsluitend de drie condities. Voer daarna een korte eigen review van de diff uit (vier eisen aanwezig per conditie, dispatch-tak ongewijzigd) en werk het worklog bij.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- SF-1609 (development): de `workflow_run`-tak van drie `if`-condities aangescherpt met
  `github.event.workflow_run.event == 'push'` en
  `github.event.workflow_run.head_repository.full_name == github.repository`:
  - `.github/workflows/dashboard-backend-image.yml`, job `build`
  - `.github/workflows/dashboard-frontend-image.yml`, job `build`
  - `.github/workflows/dashboard-frontend-image.yml`, job `build-apk`
  Waarom: de repo is openbaar; zonder deze eisen kon een `workflow_run` van een fork-PR
  (of een handmatige dispatch van `verify.yml`) een image-build met push-rechten op GHCR en
  de Android-ondertekensleutel afdwingen.
- Structuur bewust behouden als
  `github.event_name == 'workflow_dispatch' || (<vier workflow_run-eisen>)`, zodat handmatig
  dispatchen van de image-workflows zelf blijft werken. De vervolgregels van de gefoldede
  `>-`-scalar eindigen elk op `&&`, zodat YAML de expressie op één regel vouwt.
- Geen wijzigingen aan `bump-manifests` (al afgeschermd via `needs: [build]`), `verify.yml`,
  permissions-blokken, checkout-refs of applicatiecode. Geen `docs/factory/`-spec raakt dit
  gedrag (image-workflow-condities staan daar niet beschreven), dus geen spec-updates.
- Geen nieuwe geautomatiseerde tests: de repo kent geen testharnas voor workflow-condities.
  In plaats daarvan is het YAML-foldresultaat expliciet geverifieerd door beide bestanden met
  een YAML-parser (js-yaml) te laden en de drie `if`-strings af te drukken; alle drie leveren
  één geldige expressie met de vier eisen op.

- Boyscout (buiten de drie `if`-blokken, bewust): `docs/technical/module-dependencies.md`
  opnieuw gegenereerd met `tools/generate-module-dependencies`. De gate-stap
  `repository-module-dependency-drift` was al rood op de ongewijzigde branch (geverifieerd met
  `git stash`); een eerdere story voegde `support` toe aan de `allowedDependencies` van module
  `dashboard` zonder de gegenereerde doc bij te werken. Deze regeneratie is deterministisch en
  raakt geen code.

Bewijs (2026-07-31, `bash tools/verify-repository` + losse herdraai van de stappen erna):
- YAML-parse van beide workflows (js-yaml): 3 `if`-condities correct gevouwen tot één
  expressie, elk met `conclusion == 'success'`, `head_branch == 'main'`, `event == 'push'`
  en `head_repository.full_name == github.repository`; dispatch-tak ongewijzigd.
- `repository-maven-verify` (`mvn -B clean verify` vanaf repo-root): BUILD SUCCESS, exit 0,
  0 failures, 0 errors.
- `repository-quality-ratchet` (`./quality/run.sh`): **exit 1** — 21 blocking detekt-findings
  t.o.v. `quality/baselines/plan-07-ratchet.json`. PRE-EXISTING: identiek rood na `git stash`
  op de ongewijzigde branch, en deze diff bevat geen enkele Kotlin-regel. Zie escalatie hieronder.
- `repository-module-dependency-drift`: rood vóór, groen na de regeneratie hierboven.
- `repository-documentation-audit`: `documentation-audit/v1: PASS`, exit 0.
- `agent-mini-reactor-smoke`: `mini-reactor tests: PASS`, exit 0.
- `dashboard-flutter-pub-get` / `analyze` / `test`: exit 0 / `No issues found!` / 106 tests
  passed. `pubspec.lock` bleef ongewijzigd.
- `agent-image-build-stage`: `agentRunnable: false` in `.factory/verification.yaml`, draait
  alleen in CI.

Escalatie naar PO (openstaand):
- `quality/run.sh` is structureel rood op main met 21 blocking findings
  (CyclomaticComplexMethod/LongMethod/LongParameterList/TooManyFunctions/ReturnCount) in o.a.
  `AgentPromptContracts.kt`, `AgentCli.kt`, `ProjectConfiguration.kt`, `AuditGatewayAdapter.kt`,
  `AuditScheduler.kt`, `OrchestratorService.kt`, `DashboardQueryService.kt`. Herstellen betekent
  óf complexiteitsrefactors in kernklassen (groot/riskant, ver buiten deze security-story), óf de
  baseline regenereren en die 21 items als schuld accepteren — dat laatste is een beleidskeuze.
  Deze keuze is aan de PO; er is hier bewust niets stilzwijgend gedaan.
- Merk op: `repository-quality-ratchet` staat niet in `.factory/verification.yaml`, dus de
  deterministische harness-herdraai van het vangnet raakt deze stap niet.
