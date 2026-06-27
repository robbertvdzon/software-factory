# SF-399 - Worklog

## Story in eigen woorden

Documentatie-audit: vergelijk de broncode (modules, config, endpoints, UI) met de
documentatie — primair `docs/factory/` en de README's elders in de repo — en breng
de docs in lijn met de code. Code is source of truth. Uitsluitend documentatie
(.md) wijzigen; geen broncode, tests of `docs/stories` (laatste alleen read-only
input). Een lege doc-diff (docs al in sync) is een geldig eindresultaat, mits
gerapporteerd.

## Stappenplan / checklist

- [x]: read issue and target docs
- [x]: codebase vergelijken met docs (modules, pom, env-vars, endpoints, scheduler, sync-flow)
- [x]: afwijkingen corrigeren in documentatiebestanden
- [x]: zelf-review dat `git diff main` alleen docs/worklog bevat
- [x]: worklog bijwerken met gecontroleerde docs, afwijkingen en wijzigingen

> Tests: deze story wijzigt uitsluitend Markdown. Er is geen productiecode of test
> geraakt, dus er zijn geen unit-tests toegevoegd of gedraaid (niet van toepassing).

## Gecontroleerde documenten

- `docs/factory/README.md`, `development.md`, `technical-spec.md`,
  `functional-spec.md`, `deployment.md`, `secrets-local.md`
- `docs/factory/agents/*.md` (rolinstructies — procesmatig, geen code-drift gevonden)
- `docs/factory/ux/*` (beschrijven het externe dashboard; consistent met code)
- Root `README.md`, `runbook.md`
- `docs/technical/README.md`, `modules.md` (+ overige bestanden gelezen ter controle)
- Vergeleken met: root `pom.xml`, `compose.yaml`/`docker/docker-compose.yml`,
  `secrets.env.example`, de Kotlin-packages onder `softwarefactory`,
  `agentworker`, `dashboard-backend`, de Flutter `dashboard-frontend`, en de
  scheduler-/sync-code.

## Gevonden afwijkingen en doorgevoerde wijzigingen

1. **Nieuwe modules niet in `docs/factory` gedocumenteerd.** De repo heeft naast
   `softwarefactory` en `agentworker` inmiddels een `dashboard-backend`
   (Spring Boot JSON-API, Maven-module 3 in de root-`pom.xml`) en een
   `dashboard-frontend` (Flutter web-app, los van Maven). `development.md` noemde
   alleen twee modules en "alleen de twee onafhankelijke builds".
   - `development.md`: build/test-commando's en de "Structuur"-lijst uitgebreid met
     `dashboard-backend`/`dashboard-frontend`; root-pom als aggregator over drie
     modules; Flutter-toolchain toegelicht.
   - `technical-spec.md`: Dart/Flutter aan de stack toegevoegd en een nieuwe
     "Modules"-sectie (incl. poorten 8080 ingebouwd dashboard / 9090 backend /
     9080 frontend).
   - `docs/factory/README.md`: korte alinea over de dashboard-backend/-frontend.

2. **`docs/technical/` feitelijk verouderd.**
   - `README.md` noemde hoofdbranch `master` (is `main`) en "14 Kotlin packages".
     Gecorrigeerd naar `main` en 15 directe packages; verwijzing naar de losse
     modules toegevoegd. Het niet-verifieerbare "HTTP endpoints: 17" is verwijderd.
   - `modules.md`: intro zei "twee modules"/"12 directe packages" → drie
     Maven-modules en 15 packages. Ontbrekende module-secties toegevoegd voor
     `core`, `pipeline`, `nightly` en `telegram`. De sectie dashboard-backend/
     -frontend stond al beschreven.

3. **Verouderde sync-beschrijving in `docs/technical/modules.md`.** De docs
   beschreven dat de orchestrator alleen commit/pusht/PR-t bij
   `SF_AUTO_SYNC_AFTER_AGENT=true` en anders via `@factory:command:sync`. Die
   env-var bestaat niet meer in de code en `ManualCommandService` kent geen
   `sync`-command meer. `AgentRunCompletionService.syncRepositoryAfterAgent` synct
   nu onvoorwaardelijk ná elke geslaagde agent-run die de repo raakt (alle rollen
   behalve refiner/planner). Beschrijving daarop aangepast en de stale
   `@factory:command:sync`-regel uit de github-module verwijderd.

4. **`secrets-local.md` miste reële env-vars** die wel in `secrets.env.example`
   en de code staan: `SF_YOUTRACK_PUBLIC_URL`, `SF_DASHBOARD_PASSWORD`,
   `SF_DASHBOARD_BASE_URL`, `SF_TELEGRAM_BOT_TOKEN`, `SF_TELEGRAM_CHAT_ID`,
   `SF_CODEX_CREDENTIALS_DIR` en `SF_MAX_TEST_CHAIN_RESETS`. Toegevoegd.

## Niet gewijzigd (bewust)

- `docs/factory/functional-spec.md` en `deployment.md`: gecontroleerd, in sync met
  de code (Silent/SF-335, documentation-stap/SF-213, manual-approve/SF-192,
  merge-automatisch/SF-244, test-reset/SF-200, nightly/SF-350 kloppen).
- `docs/factory/agents/*` en `docs/factory/ux/*`: geen code-drift geconstateerd op
  medium-niveau.
- Het effort-niveau is "medium": structurele/feitelijke afwijkingen zijn
  geadresseerd, niet elke triviale formulering.

## Zelf-review

`git diff main --stat` toont uitsluitend wijzigingen in `docs/factory/*` en
`docs/technical/*` plus dit worklog; geen broncode, tests of `docs/stories`
(anders dan dit worklog) geraakt.

## Review (reviewer SF-400)

- [info] Diff bevat uitsluitend documentatie (`docs/factory/*`, `docs/technical/*`)
  en dit worklog; geen broncode/tests/`docs/stories` gewijzigd. Scope-conform.
- [info] Steekproef tegen de code: root `pom.xml` heeft 3 modules
  (softwarefactory, agentworker, dashboard-backend); `dashboard-frontend` is een
  losse Flutter-app (pubspec.yaml, lib/main.dart). Klopt met de docs.
- [info] 15 directe packages onder `softwarefactory` geverifieerd. Klopt met
  technical/README.md en modules.md.
- [info] `SF_AUTO_SYNC_AFTER_AGENT` en `@factory:command:sync` bestaan niet meer
  in de code; sync is onvoorwaardelijk via
  `AgentRunCompletionService.syncRepositoryAfterAgent`, met skip voor
  REFINER/PLANNER. De herschreven modules.md-tekst klopt.
- [info] Toegevoegde env-vars en poorten (9090 backend / 9080 frontend in
  docker-compose) geverifieerd; aanwezig in code/compose.
- [info] Nieuwe module-secties (core/nightly/pipeline/telegram) verwijzen naar
  bestaande bestanden. Geen onjuiste referenties aangetroffen.
- Conclusie: akkoord, geen blockers.
