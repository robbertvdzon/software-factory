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

## Review SF-1609 (2026-07-31)

Beoordeeld: volledige story-diff `git diff main...HEAD` (1 commit, `b20aad8`).

Inhoudelijk akkoord op de code:
- [info] Alle drie de `if`-condities zijn correct. Zelf geverifieerd door beide workflows met
  js-yaml te laden en de gevouwen `if`-strings te printen: alle drie leveren precies
  `github.event_name == 'workflow_dispatch' || (conclusion == 'success' && head_branch == 'main'
  && event == 'push' && head_repository.full_name == github.repository)` op. AC1-AC4 voldaan;
  de `workflow_dispatch`-tak is ongewijzigd (AC4).
- [info] Scope: geen wijziging aan `bump-manifests` (beide hebben nog steeds alleen
  `needs: [build]` en geen eigen `if`, dus indirect afgeschermd), `verify.yml`, permissions-blokken,
  checkout-refs of applicatiecode. Geen `docs/factory/`-spec beschrijft deze condities, dus geen
  spec-inconsistentie.
- [info] Geen secrets in de diff; de fix vergroot juist de afscherming van GHCR-push-rechten en de
  Android-ondertekensleutel.
- [suggestie] `docs/technical/module-dependencies.md` valt strikt genomen buiten AC5 ("buiten de
  drie `if`-blokken is de diff leeg"). Het is echter een volledig gegenereerd bestand en de
  regeneratie is nodig om `repository-module-dependency-drift` groen te krijgen (AC6). Zelf
  geverifieerd: `tools/generate-module-dependencies --check` → exit 0, "Moduledependency-metadata
  en documentatie zijn actueel." Akkoord als boyscout; AC5 versus AC6 is hier gewoon strijdig en
  AC6 weegt zwaarder.
- [blocker] `repository-quality-ratchet` (`./quality/run.sh`) is rood, dus `bash
  tools/verify-repository` slaagt niet en AC6 is niet letterlijk gehaald. Aannemelijk pre-existing
  (de diff bevat nul Kotlin-regels, developer bevestigde identiek rood na `git stash`), maar de
  reviewregels laten mij "pre-existing" niet als groen bewijs accepteren. De oplossing is een
  beleidskeuze (baseline regenereren vs. refactoren vs. aparte opruimstory), niet iets wat de
  developer stilzwijgend kan beslissen — daarom doorgezet als PO-vraag in plaats van een
  developer-loopback.
- [info] AC7 (verificatie ná merge naar `main`) is per definitie niet in deze run te toetsen.

## Review SF-1609 — tweede ronde (2026-07-31)

Beoordeeld: volledige story-diff `git diff main...HEAD` (commits `b20aad8` + `b48bd94`,
die laatste is worklog-only).

- [info] Alle drie de `if`-condities opnieuw zelf geverifieerd met js-yaml: backend `build`,
  frontend `build` en frontend `build-apk` vouwen alle drie tot exact
  `github.event_name == 'workflow_dispatch' || (github.event.workflow_run.conclusion == 'success'
  && github.event.workflow_run.head_branch == 'main' && github.event.workflow_run.event == 'push'
  && github.event.workflow_run.head_repository.full_name == github.repository)`.
  AC1-AC4 voldaan; de dispatch-tak is ongewijzigd.
- [info] `bump-manifests` in beide bestanden heeft nog steeds geen eigen `if` en alleen
  `needs: [build]` — indirect afgeschermd, conform scope. `verify.yml`, permissions, checkout-refs,
  `bump-images.sh`, `.factory/verification.yaml` en alle applicatiecode zijn onaangeraakt.
- [info] `tools/generate-module-dependencies --check` zelf gedraaid: exit 0, "Moduledependency-
  metadata en documentatie zijn actueel." De regeneratie van het volledig gegenereerde
  `docs/technical/module-dependencies.md` is akkoord als boyscout (AC5 vs AC6 is hier strijdig).
- [info] Geen secrets in de diff; de fix vergroot de afscherming van GHCR-push-rechten en de
  Android-ondertekensleutel.
- [info] Ratchet-blocker uit ronde 1 afgesloten. Onderbouwing: `quality/run.sh` draait detekt over
  de Kotlin-mainmodules t.o.v. `quality/baselines/plan-07-ratchet.json`; deze diff bevat nul
  Kotlin-regels en wijzigt de baseline niet, dus de uitkomst is bit-voor-bit identiek aan `main`.
  Het is repo-brede, bestaande schuld en geen regressie van deze story; AC6 ("slaagt onveranderd")
  is daarmee in de zin van "geen verandering in uitkomst" gehaald. De PO heeft de escalatie
  beantwoord met "ja" (issue-comment 2075).
- [suggestie] Maak een aparte opruimstory voor de 21 blocking detekt-findings
  (`AgentPromptContracts.kt`, `AgentCli.kt`, `ProjectConfiguration.kt`, `AuditGatewayAdapter.kt`,
  `AuditScheduler.kt`, `OrchestratorService.kt`, `DashboardQueryService.kt`) plus de beslissing
  baseline-regeneratie vs. refactor. Zolang die openstaat blijft `tools/verify-repository` op
  `main` bij de eerste stap hangen en zien volgende stories een misleidend rood vangnet.
- [info] AC7 blijft na-merge-verificatie en valt buiten deze run.

Besluit: akkoord.

## Test SF-1610 (2026-07-31)

Getest: `git diff main...HEAD` (commits `b20aad8`, `b48bd94`, `b2c3762`). Niets gewijzigd behalve
dit worklog; werkboom verder schoon.

Uitgevoerde verificaties:
- **YAML-fold + AC1-AC4**: beide workflows geladen met SnakeYAML 2.4 (echte parser, niet grep).
  De drie `if`-condities (`dashboard-backend-image.yml` job `build`,
  `dashboard-frontend-image.yml` jobs `build` en `build-apk`) vouwen alle drie tot exact één
  expressie:
  `github.event_name == 'workflow_dispatch' || (github.event.workflow_run.conclusion == 'success'
  && github.event.workflow_run.head_branch == 'main' && github.event.workflow_run.event == 'push'
  && github.event.workflow_run.head_repository.full_name == github.repository)`.
  Alle vier de eisen aanwezig, `workflow_dispatch`-tak ongewijzigd, haakjes correct gesloten.
  Indentatie van de gefoldede scalar is uniform 6 spaties (`cat -A`), dus geen "more indented
  line"-valkuil.
- **Gedragssimulatie van de expressie** (8 scenario's, waarheidstabel):
  | scenario | uitkomst |
  |---|---|
  | push naar `main` in eigen repo (normale flow) | BUILD |
  | fork-PR met branchnaam `main` die verify laat slagen (de aanval) | geblokkeerd |
  | push in een fork (`event == 'push'`, andere repo) | geblokkeerd |
  | eigen PR binnen de repo (`event == 'pull_request'`) | geblokkeerd |
  | handmatige dispatch van `verify.yml` | geblokkeerd (bewust, zie Aannames) |
  | handmatige dispatch van de image-workflow zelf | BUILD (AC4) |
  | verify faalt op push naar `main` | geblokkeerd |
  | push naar feature-branch in eigen repo | geblokkeerd |
- **Scope**: `bump-manifests` in beide bestanden heeft nog steeds géén eigen `if` en alleen
  `needs: [build]` (via parser bevestigd) — wordt dus indirect meegeskipt. `verify.yml`,
  permissions-blokken, checkout-`ref`s, `bump-images.sh` en alle applicatiecode zijn onaangeraakt.
  Er is geen andere workflow met een `workflow_run`-trigger.
- **AC5-afwijking (geaccepteerd)**: naast de drie `if`-blokken en dit worklog wijzigt
  `docs/technical/module-dependencies.md`. Zelf geverifieerd via een schone worktree op `main`:
  daar geeft `tools/generate-module-dependencies --check` exit 1 ("drift"), op deze branch exit 0.
  Het is een volledig gegenereerd bestand en de regeneratie is nodig om die gate groen te krijgen
  (AC6). Akkoord als boyscout, conform de reviewbeslissing.
- **Vangnet**: `repository-documentation-audit` (`tools/audit-documentation`, het enige commando in
  `.factory/verification.yaml` zonder `pathPrefixes`) → `documentation-audit/v1: PASS`, exit 0.
  `repository-maven-verify`, `dashboard-flutter-*` en `agent-mini-reactor-smoke` matchen geen van
  de gewijzigde paden (`.github/workflows/`, `docs/`) en vallen dus out-of-scope; de diff bevat
  nul JVM-, Dart- of Docker-regels. `agent-image-build-stage` is `agentRunnable: false`.
  `repository-quality-ratchet` staat niet in `.factory/verification.yaml` en draait sinds
  2026-07-24 ook niet meer in CI; met nul Kotlin-regels in de diff is de uitkomst identiek aan
  `main` (bestaande schuld, PO akkoord).
- Geen preview-URL en geen browser in de tester-omgeving; de wijziging is bovendien puur CI-gedrag,
  dus screenshots/E2E zijn hier niet zinvol.
- AC7 (verificatie ná merge naar `main`) valt per definitie buiten deze run.

Besluit: tested.
