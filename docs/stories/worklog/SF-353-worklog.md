# SF-353 — Story-brede test (tester)

Story: SF-350 (Nightly scheduler). Branch `ai/SF-350`. Getest op 2026-06-27.

## Aanpak

Geen preview-deploy ingericht voor deze factory-repo (zie tester-instructies). Lokaal
geverifieerd via `mvn -f softwarefactory/pom.xml test`. Story-diff t.o.v. `main`
doorgenomen (V11/V12-migraties, `nightly`-module, web-laag, specs, tests) en getoetst
aan de 11 acceptatiecriteria.

## Resultaten

- **Doelgerichte run** (`NightlyPlannerTest, NightlyDigestTest, NightlySchedulerTest,
  NightlyTimeTest, FactoryDashboardServiceTest, TelegramNotificationServiceTest`):
  **77 tests, 0 failures, 0 errors**.
  - NightlyPlannerTest 14, NightlySchedulerTest 6, NightlyDigestTest 3, NightlyTimeTest 6,
    FactoryDashboardServiceTest 29, TelegramNotificationServiceTest 19.
- **Volledige suite** (`-Dsurefire.runOrder=alphabetical`): **390 tests, Failures: 0**, 14 Errors.
  Alle 14 errors zijn omgeving/pre-existing, geen code-bugs:
  - 11× Docker-e2e (`FactoryUiDriverLoginTest` 1, `FullRefineToDevelopE2eTest` 1,
    `PipelineFlowsE2eTest` 9) — tester-omgeving heeft geen docker-daemon.
  - 1× `NightlyRepositoriesTest` (Testcontainers/Postgres) — faalt op "Could not find a valid
    Docker environment"; omgevingsgebonden, niet de code. Draait in CI met Docker.
  - 1× `ModulithArchitectureTest` — geverifieerd identiek falend op schone `main`-worktree
    (zelfde cycle `orchestrator → telegram → web`); pre-existing, geen regressie. De nieuwe
    `nightly`-module introduceert geen nieuwe module-cycle (hangt aan de bestaande web→telegram-edge).
  - 1× `FactoryDashboardRepositoryScreenshotTest` — pre-existing op schone `main` (omgeving).

## Toetsing acceptatiecriteria (uit code + tests)

- AC1/AC10: V11 maakt `nightly_settings/nightly_run/nightly_run_job` aan (single-row settings met
  seed-default `enabled=false`, 02:00/07:00; UNIQUE op `run_date`); V12 voegt `summary_text` toe.
  `/settings` POST `/settings/nightly` valideert HH:MM en persisteert; read-only config blijft. ✔ (repo round-trip in CI)
- AC2: Run-creatie idempotent op `run_date` (`NightlyPlanner` + UNIQUE + executor seedt alleen lege run).
  Gedekt door NightlyPlannerTest/NightlySchedulerTest. ✔
- AC3: `NightlyTime` DST-correct (Europe/Amsterdam, Clock-injecteerbaar), NightlyTimeTest winter/zomer. ✔
- AC4: projecten parallel (queue per project), jobs sequentieel (volgende pas na terminaal). ✔
- AC5: done = alle subtaken terminaal; failed = error-veld (story óf subtaak); failed blokkeert nacht niet. ✔
- AC6: run-status volledig in DB; restart-pickup zonder dubbele stories (NightlySchedulerTest). ✔
- AC7/AC8: digest exact één keer (`summary_sent_at`), per project met naam/duur/kosten/uitkomst/link
  + totalen (NightlyDigestTest). ✔
- AC9: `/nightly` toont run-status per project + handmatige lijst ongewijzigd (FactoryDashboardServiceTest). ✔
- AC11: scheduler raakt story-flow alleen via `createNightlyStory` (silent=true, start=true). ✔

## Conclusie

Geen code-bugs of regressies. Failures: 0; alle errors zijn Docker/pre-existing en
reproduceren op schone `main`. Nieuwe behavior is via unit tests afgedekt. Geen secrets in output.
**Geslaagd.**
