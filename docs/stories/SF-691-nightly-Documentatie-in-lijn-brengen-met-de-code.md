# SF-691 - nightly: Documentatie in lijn brengen met de code

## Story

nightly: Documentatie in lijn brengen met de code

<!-- refined-by-factory -->

## Scope

Controleer of de documentatie in de repository nog in lijn is met de huidige broncode en werk **uitsluitend de documentatie** bij waar deze afwijkt. De broncode mag niet worden gewijzigd.

In scope:
- Doorlopen van de documentatie, met nadruk op `docs/factory/` (o.a. `README.md`, `functional-spec.md`, `technical-spec.md`, `development.md`, `deployment.md`, de `agents/`-instructies en de `ux/`-beschrijvingen).
- Vergelijken van beschreven functionaliteit, commando's, flows, configuratie en conventies met wat de code daadwerkelijk doet.
- Corrigeren, aanvullen of verwijderen van documentatie zodat functionaliteit die in de code bestaat ook correct in de documentatie staat, en documentatie die niet (meer) klopt wordt rechtgezet.
- `docs/stories` mag als input gebruikt worden om te begrijpen wáárom functionaliteit bestaat, maar mag niet gewijzigd worden.

Buiten scope:
- Elke wijziging aan broncode (Kotlin, scripts, configuratie-/buildbestanden, tests). Geen functionele of gedragsverandering van de applicatie.
- Wijzigingen aan `docs/stories` (alleen lezen).
- Toevoegen van nieuwe features of het herontwerpen van documentatiestructuur die niet nodig is voor sync.

## Acceptance criteria

- De broncode is niet gewijzigd: een diff toont uitsluitend wijzigingen in documentatiebestanden (en eventueel de worklog). Is er wel code aangepast, dan wordt de story afgekeurd.
- Geconstateerde discrepanties tussen documentatie en code zijn weggewerkt: functionaliteit die in de code aanwezig is, is correct in de documentatie beschreven, en documentatie die niet met de code overeenkomt is gecorrigeerd of verwijderd.
- Geen wijzigingen in `docs/stories`.
- De doorgevoerde documentatiewijzigingen (of de constatering dat documentatie al in sync is) zijn navolgbaar vastgelegd in de worklog (`docs/stories/worklog/SF-691-worklog.md`), met per aangepast bestand een korte motivatie verwijzend naar de relevante code.
- Als de documentatie al volledig in lijn is met de code, is dat een geldig eindresultaat (geen documentatiewijzigingen), mits dit in de worklog onderbouwd is.

## Aannames

- "Documentatie" omvat primair de bestanden onder `docs/` (in het bijzonder `docs/factory/`); broncode-commentaar en README's buiten `docs/` vallen buiten scope tenzij ze evident onderdeel zijn van de projectdocumentatie.
- Het bijwerken van de worklog onder `docs/stories/worklog/` geldt niet als verboden wijziging aan `docs/stories`, omdat de worklog het standaard factory-verslagmechanisme is; de inhoudelijke story-bestanden in `docs/stories` blijven ongemoeid.
- Bij twijfel is de code leidend: de documentatie wordt aangepast aan de code, nooit andersom.
- Het doel is correctheid en volledigheid van bestaande documentatie, niet een uitputtende herstructurering; stilistische herschrijvingen blijven beperkt tot wat nodig is om docs en code te laten kloppen.

## Eindsamenvatting

Ik heb alle context gelezen (`.task.md`, het worklog `SF-691-worklog.md` en de diff). Hier is de eindsamenvatting.

## Eindsamenvatting — SF-691: Documentatie in lijn brengen met de code

**Doel:** controleren of de factory-documentatie (vooral `docs/factory/`) nog klopt met de huidige broncode, scripts en config, en uitsluitend de documentatie bijwerken waar die afweek. Broncode en `docs/stories` bleven ongemoeid.

### Wat is gebouwd / gewijzigd
De documentatie bleek grotendeels al in sync (recent bijgewerkt via o.a. SF-530/SF-565). Er was één reëel gat: de **nightly digest AI-verrijking** zat wel in de code/migratie maar ontbrak in de specs. Twee documentatiebestanden zijn gecorrigeerd:

- **`docs/factory/technical-spec.md`**
  - Migratie-overzicht aangevuld met `V14__nightly_run_ai_detail_pending.sql`.
  - `nightly_run`-kolommen aangevuld met `summary_text` en `ai_detail_pending`.
  - Digest-beschrijving uitgebreid met de AI-samenvatting, change-/YouTrack-links en de uitgestelde AI-verrijking (`aiEnrichmentTick`/`ai_detail_pending`).
- **`docs/factory/functional-spec.md`**
  - Nightly-digest-bullet aangevuld met de AI-samenvatting, klikbare links en de uitgestelde AI-detail-follow-up bij een tijdelijke Claude-limiet.

### Belangrijkste keuzes
- **Code is leidend:** alleen docs aangepast aan de code, nooit andersom. Bewust minimale, feitelijke correcties i.p.v. herstructurering.
- Veel claims zijn geverifieerd en **bewust niet gewijzigd** omdat ze al klopten: SF_-defaults (`OrchestratorSettings`), afgedwongen ketenvolgorde, AI-supplierlijst (`none/mock/claude/openai/copilot/microsoft`), de 5 verplichte secrets, onvoorwaardelijke sync na agent-runs, poorten 8080/9090/9080, en de agent-instructies.

### Wat is getest
- **Scope-check:** `git diff --name-only main...HEAD` raakt uitsluitend `docs/factory/functional-spec.md`, `docs/factory/technical-spec.md` en het worklog — geen `.kt`/test/migratie/config, geen wijziging in `docs/stories`. Acceptatiecriterium "broncode niet gewijzigd" gehaald.
- Elke nieuwe documentatieclaim is los tegen de productiecode getoetst (V14-migratie bestaat; `NightlyRepositories.kt`, `NightlyDigest.kt`, `NightlyScheduler.kt`, `NightlyChangeSummarizer.kt` bevestigen de beschreven flows, incl. retry-default 20 min en `MAX_ENRICH_HOURS = 12`). Resultaat: **tested**.

### Bewust niet gedaan
- Geen broncodewijzigingen, geen wijziging aan `docs/stories` (alleen als context gelezen).
- Geen unittests/`mvn test`: docs-only story zonder code- of gedragsverandering.
- Geen documentatie-herstructurering of stilistische herschrijvingen buiten wat nodig was voor sync.

**Resultaat:** documentatie is nu in lijn met de code; er resteren geen bekende discrepanties.
