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

## Test-stap (SF-693, tester)

Docs-only story geverifieerd door elke nieuwe documentatieclaim los tegen de productiecode te toetsen.

- **Scope-check**: `git diff --name-only main...HEAD` = uitsluitend `docs/factory/functional-spec.md`,
  `docs/factory/technical-spec.md` en dit worklog. Geen `.kt`/test/migratie/config-bestand gewijzigd
  → broncode-acceptatiecriterium gehaald. Geen wijziging in `docs/stories` (inhoudelijk).
- **V14-migratie**: `V14__nightly_run_ai_detail_pending.sql` bestaat en voegt
  `ai_detail_pending BOOLEAN NOT NULL DEFAULT FALSE` toe → doc-opsomming klopt.
- **`nightly_run`-kolommen**: `summary_text` en `ai_detail_pending` worden gelezen/geschreven in
  `NightlyRepositories.kt` (select r225, `setAiDetailPending`/`pendingAiDetail` r207/212, mapping r237)
  → doc klopt.
- **Digest-inhoud**: `NightlyDigest.kt` (`NightlySection`, `sections`, link-regel r102) +
  `NightlyGateway.describeChanges` → doc klopt. Link-prioriteit "merge-commit bij voorkeur, anders PR":
  bevestigd in `NightlyChangeSummarizer.kt:66` (`changeUrl = commitUrl ?: ctx.prUrl`); YouTrack-link
  via `youTrackUrl`.
- **Uitgestelde AI-verrijking**: `NightlyScheduler.aiEnrichmentTick` (`@Scheduled`,
  `sf.nightly.ai-retry-ms` default `1200000` ms = 20 min) en `enrichPendingDigests` (markeert
  `ai_detail_pending`, stuurt aanvulling, geeft op na `MAX_ENRICH_HOURS = 12L`) → doc klopt; de
  feitelijke digest gaat direct uit, AI-details volgen later als aanvullend bericht.
- **Geen `mvn test` nodig**: er is geen code/test/gedrag gewijzigd (alleen docs); de beschreven
  features stonden al op `main` en zijn nu correct in de documentatie beschreven.

Conclusie: documentatie is nu in lijn met de code; geen discrepanties resteren. **tested**.

## Documentatie-stap (SF-695, documenter)

De developer-subtaak corrigeerde `docs/factory/`, maar de parallelle doc-tree `docs/technical/`
(spiegelt dezelfde code-feiten, zie agent-tip `technical-docs-parallel-tree`) beschreef de nightly
digest nog in de oude vorm en miste de nieuwe `aiEnrichmentTick`. Bijgewerkt zodat beide trees
kloppen met de code:

- `docs/technical/scheduled-jobs.md`
  - Digestbeschrijving van de nightly scheduler aangevuld met de AI-samenvatting
    (`NightlyGateway.describeChanges`) en klikbare links (`NightlyDigest.kt`).
  - Nieuwe sectie "4b. Nightly AI-verrijking (uitgesteld)" voor de tweede `@Scheduled`-methode
    `aiEnrichmentTick` (`sf.nightly.ai-retry-ms` default `1200000` ms / 20 min,
    `ai-retry-initial-delay-ms` default `120000` ms, `MAX_ENRICH_HOURS = 12`),
    geverifieerd tegen `NightlyScheduler.kt:221-262`.
  - Intro aangepast: de nightly scheduler heeft nu twee `@Scheduled`-methodes.
  - Discrepantie gecorrigeerd: agent-result-poll default `5000` → `2000`
    (`AgentResultFileCompletionPoller.kt:30` = `agent-result-poll-ms:2000`).
- `docs/technical/modules.md`
  - `nightly`-module: digesttaak aangevuld met AI-samenvatting, `ai_detail_pending` (migratie `V14`),
    `aiEnrichmentTick`-follow-up en `NightlyGateway.describeChanges`.

Overige docs gecontroleerd, geen wijziging nodig:
- `docs/technical/overview.md` / `endpoints.md` / `external-systems.md`: noemen de digestinhoud niet.
- `docs/factory/ux/screens/settings.md` + `ux/screen-map.md`: beschrijven alleen het
  nightly-settingsformulier en de screen-routes, niet de digestinhoud — blijven correct.
- `docs/factory/functional-spec.md` + `technical-spec.md`: al bijgewerkt in de developer-subtaak,
  geverifieerd correct.

Geen broncode, tests of `docs/stories`-inhoud gewijzigd; alleen documentatie + dit worklog.
