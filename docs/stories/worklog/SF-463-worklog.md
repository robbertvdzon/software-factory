# SF-463 - Worklog

Story-context bij eerste pickup:
Documentatie onder docs/factory in lijn brengen met de code

Vergelijk systematisch alle documentatie onder docs/factory/ (functional-spec.md, technical-spec.md, development.md, deployment.md, secrets-local.md, README.md, agents/*.md en ux/**) met de actuele broncode. Corrigeer verouderde verwijzingen (bestandspaden, modulenamen, commando's, configuratie-opties, agent-rollen/fases) en voeg ontbrekende functionaliteit die wel in de code zit maar niet (correct) gedocumenteerd is toe aan de juiste pagina's. Bij twijfel is de code de bron van waarheid. ux/wireframes* alleen aanpassen bij aantoonbare discrepantie, niet cosmetisch. Pas GEEN broncode, tests of build/config aan en wijzig docs/stories/ niet (alleen als read-only input). Leg in docs/stories/worklog/SF-463-worklog.md vast welke discrepanties zijn gevonden en hoe ze zijn opgelost; bij geen discrepanties dat expliciet noteren. Sluit af met een zelf-review dat de diff uitsluitend documentatiebestanden raakt.

## Subtaak SF-464 (developing) — documentatie in lijn met code

Stappenplan:
- [x]: docs/factory volledig gelezen (README, functional-spec, technical-spec, development, deployment, secrets-local, agents/*, ux-index)
- [x]: claims systematisch tegen de code geverifieerd (config/env-vars, agent-rollen/fases/keten, nightly-scheduler, web-endpoints, padverwijzingen)
- [x]: gevonden discrepanties gecorrigeerd in de docs
- [x]: ontbrekende functionaliteit toegevoegd aan de juiste pagina's
- [x]: zelf-review dat de diff uitsluitend documentatiebestanden raakt
- [ ]: tests draaien — n.v.t., documentatie-only story (geen code-wijziging)

### Gevonden discrepanties en oplossing

**1. `SF_YOUTRACK_PROJECTS` ten onrechte als verplicht gedocumenteerd**
- Code: `FactorySecrets.REQUIRED_KEYS` bevat alleen 5 keys (base-url, token, github-token, database-url, database-schema); `SecretsEnvLoader` leest `youTrackProjects` via `resolveOptional()`. Leeg = factory ontdekt zelf alle niet-gearchiveerde projecten (`YouTrackClient`).
- Fix: `secrets-local.md` — key verplaatst van het "Verplichte keys"-blok naar een eigen optioneel blok met uitleg.

**2. Verkeerde default voor `SF_POLL_INTERVAL_MS`**
- Code: `OrchestratorSettings.fromEnvironment` default = `1000` (niet `15000`); ook `properties.default.env` bevestigt 1000.
- Fix: aangepast in `technical-spec.md` (Orchestrator tuning) én `secrets-local.md` van `15000` → `1000`.

**3. Niet-gedocumenteerde SF_-config-vars die de code wél leest**
- Code: `OrchestratorSettings` leest ook `SF_POLL_INTERVAL_IDLE_MS` (1000), `SF_ACTIVE_PHASE_RECOVERY_DELAY_MS` (60000), `SF_COST_MONITOR_INTERVAL_MS` (300000), `SF_CREDITS_PAUSE_DEFAULT_MINUTES` (30). `FactoryDashboardAuth` leest `SF_DASHBOARD_USERNAME` (admin), `SF_DASHBOARD_REMEMBER_SECRET`, `SF_DASHBOARD_REMEMBER_DAYS` (30), `SF_DASHBOARD_COOKIE_SECURE` (false). `FactoryApiController` gebruikt `SF_FACTORY_API_TOKEN`; `ProjectRepoResolver` gebruikt `SF_PROJECTS_FILE`.
- Fix: toegevoegd aan de tuning-lijst in `technical-spec.md` en aan de optionele/dashboard-blokken in `secrets-local.md`.

**4. Nightly-scheduler: meerdere runs per dag + handmatige run + onderbreken (V13)**
- Code: migratie `V13__nightly_run_multiple_per_day.sql` verwijdert de `UNIQUE (run_date)`-constraint en voegt kolom `kind` toe (`scheduled`/`manual`, `NightlyRunKind`). `NightlyScheduler.startManualRun()` (knop "Run nu", `POST /nightly/run-now`) en `stopActiveRun()` (knop "Onderbreek run", `POST /nightly/stop`) bestaan; `NightlyJobStatus.CANCELLED` is toegevoegd en telt als terminaal. `NightlyPlanner`: digest nooit vóór summary-tijd, een `scheduled` run stuurt op summary-tijd, een `manual` run wacht tot alle jobs terminaal zijn.
- Docs zeiden nog "precies één run per kalenderdag", "`run_date` uniek" en "idempotent op `run_date` (`ON CONFLICT DO NOTHING`)" — verouderd.
- Fix: `technical-spec.md` (Nightly scheduler + Reconciliation-scheduler): V13 toegevoegd aan migratielijst, `kind`-kolom + `NightlyRunKind`, `cancelled`-jobstatus, run-creatie herschreven (scheduled vs manual, `hasScheduledRunOn`), digest-timing scheduled vs manual, nieuw bullet "Handmatig onderbreken", en de `/nightly`-UI-beschrijving uitgebreid met starttijd per job, "Run nu", "Onderbreek run" en de `?run=`-feedback. `functional-spec.md` (Nightly scheduler): bullet "Handmatige run (Run nu)" toegevoegd, digest-bullet en `/nightly`-bullet bijgewerkt.

**5. Verkeerd pad naar docker-compose**
- Code/repo: compose-bestand staat op `docker/docker-compose.yml` (zo aangeroepen door `./factory local-db`), niet op repo-root.
- Fix: `technical-spec.md` Config-sectie pad gecorrigeerd naar `docker/docker-compose.yml`.

### Geverifieerd correct (geen wijziging nodig)
- Agent-pijplijn-rollen in `functional-spec.md` (refiner→developer→reviewer→tester→summarizer→documenter) kloppen met de `AgentRole`-enum. De interne rollen `PLANNER`/`ASSISTANT`/`COST_MONITOR`/`ORCHESTRATOR` zijn geen onderdeel van het zichtbare verhaal en bewust niet aan de functionele opsomming toegevoegd.
- Keten-volgorde `development → review → test → summary → documentation → manual-approve → merge → deploy` klopt met `AgentRunCompletionService` (plannedSpecs + documentationSpecs + manualApproveSpecs + chainClosingSpecs).
- Documentatie-fases `DOCUMENTING/DOCUMENTED/DOCUMENTATION_WITH_QUESTIONS/DOCUMENTATION_QUESTIONS_ANSWERED/DOCUMENTATION_APPROVED` en de `*-with-questions`-fases bestaan exact zoals beschreven (`SubtaskPhase`/`StoryPhase`).
- `manual-approve`-poort: `!parentSilent && ProjectRepoResolver.manualApproveFor(...)` (default true), aangemaakt ná documentation en vóór merge — klopt.
- Overige tuning-defaults (max-parallel, loopbacks, test-chain-resets, transient-retries, hard-timeout) komen overeen met `OrchestratorSettings`.
- `agents/*.md`, `development.md`, `deployment.md`, `README.md`: padverwijzingen (`specs/specs.md`, `./factory local-db`, modulestructuur, poorten 8080/9090/9080) gecontroleerd en correct.
- UX-wireframes (`ux/wireframes*`): geen aantoonbare code-discrepantie aangetroffen; conform scope niet cosmetisch aangepast.

### Zelf-review
`git diff --stat` raakt uitsluitend documentatiebestanden:
- `docs/factory/functional-spec.md`
- `docs/factory/secrets-local.md`
- `docs/factory/technical-spec.md`
- (plus dit worklog onder `docs/stories/worklog/`)

Geen `.kt`, test-, build- of config-bestanden gewijzigd; geen wijzigingen in `docs/stories/` buiten dit worklog. Documentatie-only story, dus build/tests blijven groen en hoeven niet gedraaid te worden.

## Review SF-464 (reviewer) — akkoord

Statisch gereviewd; volledige story-diff `git diff main...HEAD` beoordeeld. Bevindingen:

- [info] Scope correct: diff raakt uitsluitend `docs/factory/{functional-spec,secrets-local,technical-spec}.md` + dit worklog. Geen code/test/build/config, geen wijzigingen in `docs/stories/` buiten worklog. Acceptance-criterium (alleen documentatie) gehaald.
- [info] Alle gewijzigde claims geverifieerd tegen de code en correct bevonden:
  - `SF_POLL_INTERVAL_MS`/`SF_POLL_INTERVAL_IDLE_MS` default `1000` → `OrchestratorSettings.fromEnvironment` (regels 48-49).
  - `REQUIRED_KEYS` = 5 keys, `SF_YOUTRACK_PROJECTS` optioneel → `FactorySecrets.kt:53`.
  - Extra config-vars (`SF_ACTIVE_PHASE_RECOVERY_DELAY_MS` 60000, `SF_COST_MONITOR_INTERVAL_MS` 300000, `SF_CREDITS_PAUSE_DEFAULT_MINUTES` 30) → `OrchestratorSettings.kt:59-61`.
  - Dashboard-vars (`SF_DASHBOARD_USERNAME` default `admin`, `SF_DASHBOARD_COOKIE_SECURE` false, `SF_DASHBOARD_REMEMBER_DAYS` 30) → `FactoryDashboardAuth.kt`; `SF_DASHBOARD_REMEMBER_SECRET` met fallback `"$username:$password"` → `dashboard-backend/.../DashboardConfig.kt:81`. `SF_FACTORY_API_TOKEN` → `FactoryApiController.kt`; `SF_PROJECTS_FILE` → `ConfigApi.kt`/`ProjectRepoResolverConfiguration.kt`.
  - Nightly V13: `kind` (`NightlyRunKind` scheduled/manual), `NightlyJobStatus.CANCELLED`, `hasScheduledRunOn`, `startManualRun`/`stopActiveRun`, endpoints `POST /nightly/run-now` + `/nightly/stop`, `?run=started|busy|stopped|stop-none` feedback en digest-timing (scheduled op summary-tijd, manual wacht tot alle jobs terminaal) → `NightlyRepositories.kt`, `NightlyScheduler.kt`, `NightlyPlanner.kt:93-100`, `FactoryDashboardController.kt:326-345`, `FactoryDashboardViews.kt`.
  - Pad `docker/docker-compose.yml` bestaat; root-`docker-compose.yml` niet.
- [info] Geen tests vereist: documentatie-only, geen code-wijziging — conform acceptance criteria.

Conclusie: coherent, accuraat t.o.v. de code, binnen scope. Goedgekeurd.

## Test SF-465 (tester) — story-brede verificatie

Documentatie-only story: geverifieerd via diff-scope + losse code-checks (geen build/tests nodig, geen code/tests gewijzigd).

- [info] Diff-scope: `git diff --name-only main...HEAD` raakt uitsluitend `docs/factory/{functional-spec,secrets-local,technical-spec}.md` + dit worklog. Geen `.kt`, test-, build- of config-bestanden; geen wijzigingen in `docs/stories/` buiten het worklog. Acceptance-criterium (alleen documentatie) gehaald.
- [info] Elke gewijzigde doc-claim los tegen de code geverifieerd en correct bevonden:
  - `FactorySecrets.REQUIRED_KEYS` = exact 5 keys (base-url, token, github-token, database-url, database-schema); `SF_YOUTRACK_PROJECTS` via `SecretsEnvLoader.resolveOptional` → optioneel.
  - `OrchestratorSettings.fromEnvironment`: `SF_POLL_INTERVAL_MS`/`SF_POLL_INTERVAL_IDLE_MS` default `1000`, `SF_ACTIVE_PHASE_RECOVERY_DELAY_MS` 60000, `SF_COST_MONITOR_INTERVAL_MS` 300000, `SF_CREDITS_PAUSE_DEFAULT_MINUTES` 30.
  - Dashboard/API-vars: `SF_DASHBOARD_USERNAME`→`admin`, `SF_DASHBOARD_COOKIE_SECURE`→false (`FactoryDashboardAuth.kt`); `SF_FACTORY_API_TOKEN` (`FactoryApiController.kt`); `SF_PROJECTS_FILE` (`ConfigApi.kt`/`ProjectRepoResolverConfiguration.kt`).
  - Nightly V13: migratie `V13__nightly_run_multiple_per_day.sql` aanwezig; `NightlyRunKind` (scheduled/manual), `NightlyJobStatus.CANCELLED` (terminaal), endpoints `POST /nightly/run-now`→`?run=started|busy` en `POST /nightly/stop`→`?run=stopped|stop-none` (`FactoryDashboardController.kt`), planner-timing scheduled vs manual (`NightlyPlanner.kt`).
  - Pad `docker/docker-compose.yml` bestaat; geen root-`docker-compose.yml`.
- [info] Geen build/tests gedraaid: documentatie-only story, geen code-wijziging — conform acceptance criteria blijven bestaande build/tests groen.

Conclusie: documentatie is accuraat t.o.v. de code, diff is binnen scope (docs-only). Test geslaagd.
