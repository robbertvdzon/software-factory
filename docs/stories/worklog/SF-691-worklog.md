# SF-691 - Worklog

Story-context bij eerste pickup:
Documentatie in lijn brengen met de code

Vergelijk de documentatie onder docs/ (nadruk op docs/factory/: README.md, functional-spec.md, technical-spec.md, development.md, deployment.md, agents/*.md, ux/**) met de feitelijke broncode, scripts en configuratie. Corrigeer, vul aan of verwijder documentatie zodat alle in de code aanwezige functionaliteit correct beschreven staat en onjuiste/verouderde documentatie wordt rechtgezet; bij twijfel is de code leidend en blijven wijzigingen beperkt tot wat nodig is voor sync. Wijzig GEEN broncode en GEEN bestanden onder docs/stories (alleen lezen als context). Leg per aangepast documentatiebestand een korte motivatie met codeverwijzing vast in docs/stories/worklog/SF-691-worklog.md; is alles al in sync, onderbouw dan 'geen wijzigingen' in de worklog. Sluit af met een review-stap: controleer dat de diff uitsluitend documentatie- en worklogbestanden raakt en geen code.

## Story in eigen woorden

Controleer of de factory-documentatie (vooral `docs/factory/`) nog klopt met de huidige
broncode, scripts en config. Werk uitsluitend documentatie bij waar die afwijkt; broncode en
`docs/stories` blijven ongemoeid.

## Checklist

[x]: read issue and target docs
[x]: documentatie vergeleken met broncode/config (defaults, ketenvolgorde, suppliers, secrets, nightly, migraties)
[x]: discrepanties gecorrigeerd in de documentatie
[x]: gecontroleerd dat de diff alleen docs + worklog raakt (geen code, geen docs/stories)
[x]: update story-log with results

## Aanpak en bevindingen

De documentatie bleek grotendeels al in lijn met de code (recent bijgewerkt via o.a. SF-530,
SF-565 en de nightly-stories). Ik heb de concrete, feitelijke claims uit de docs één voor één
tegen de broncode geverifieerd:

- **In sync (geen wijziging nodig), geverifieerd tegen code:**
  - `OrchestratorSettings.fromEnvironment` SF_-defaults (poll-intervals, max-parallel, loopbacks,
    test-chain-resets, transient-retries, timeouts, cost-monitor, credits-pause) — exact gelijk aan
    technical-spec.md/secrets-local.md.
  - Afgedwongen ketenvolgorde `development → review → test → summary → documentation →
    manual-approve → merge → deploy` (`AgentRunCompletionService.materializeSubtasksIfPlanned`).
  - AI-supplier-keuzelijst `none/mock/claude/openai/copilot/microsoft` en de
    `AiClientFactory.create`-mapping (incl. `microsoft` → niet-uitvoerbare client) — docs correct.
  - `FactorySecrets.REQUIRED_KEYS` = de 5 verplichte keys; `SF_YOUTRACK_PROJECTS` optioneel.
  - `YouTrackClient.findWorkIssues` filtert client-side op `AI-supplier`, geen `Stage = Develop`.
  - Onvoorwaardelijke sync na agent-runs (REFINER/PLANNER overgeslagen); geen `MergeConfig` meer.
  - Poorten 8080 (ingebouwd dashboard, Spring default) / 9090 (dashboard-backend) / 9080
    (dashboard-frontend) — conform `docker/docker-compose.yml`.
  - Agent-instructies (`agents/*.md`) zijn procesinstructies en consistent met de code.

- **Gecorrigeerd — nightly digest AI-verrijking (was niet gedocumenteerd):**
  De nachtelijke digest is uitgebreid met een AI-samenvatting van de wijzigingen per job en
  klikbare links, plus een uitgestelde AI-detail-follow-up wanneer de AI-samenvatting op het
  moment van versturen niet lukt (bv. Claude-limiet). Dit zit in code/migratie maar ontbrak in de
  specs:
  - `softwarefactory/.../db/migration/V14__nightly_run_ai_detail_pending.sql` (kolom
    `ai_detail_pending`).
  - `NightlyScheduler.kt` (`buildDigestJobs` → `gateway.describeChanges`, `sendDigest` zet
    `ai_detail_pending`, `aiEnrichmentTick`/`enrichPendingDigests`,
    `sf.nightly.ai-retry-ms` default 20 min, `MAX_ENRICH_HOURS`).
  - `NightlyDigest.kt` (`NightlySection`/`NightlyJobChanges`, render van links + AI-secties).
  - `NightlyRepositories.kt` (`aiDetailPending`, `setAiDetailPending`, `pendingAiDetail`).

## Aangepaste documentatiebestanden + motivatie

- `docs/factory/technical-spec.md`
  - Migratie-opsomming aangevuld met `V14__nightly_run_ai_detail_pending.sql` (motivatie: code/migratie
    aanwezig, stond niet in de lijst V11–V13).
  - `nightly_run`-kolommenbeschrijving aangevuld met `summary_text` en `ai_detail_pending`
    (`NightlyRepositories.kt` leest/schrijft beide).
  - Digest-beschrijving aangevuld met AI-samenvatting (`NightlySection`/`NightlyGateway.describeChanges`)
    + change-/YouTrack-links, en een nieuwe bullet "Uitgestelde AI-verrijking" voor
    `aiEnrichmentTick`/`ai_detail_pending` (`NightlyScheduler.kt`, `NightlyDigest.kt`).

- `docs/factory/functional-spec.md`
  - Nightly-digest-bullet aangevuld met de AI-samenvatting van de wijzigingen, de klikbare links en de
    uitgestelde AI-detail-follow-up bij een tijdelijke Claude-limiet (`NightlyScheduler.enrichPendingDigests`).

## Review-stap

`git diff --stat` toont uitsluitend wijzigingen in `docs/factory/functional-spec.md` en
`docs/factory/technical-spec.md`; daarnaast dit worklog. Geen broncode en geen `docs/stories`
gewijzigd. Geen unittests toegevoegd of gedraaid: deze story wijzigt alleen documentatie en raakt
geen code/gedrag.
