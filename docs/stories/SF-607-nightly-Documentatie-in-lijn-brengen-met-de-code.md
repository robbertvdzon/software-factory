# SF-607 - nightly: Documentatie in lijn brengen met de code

## Story

nightly: Documentatie in lijn brengen met de code

<!-- refined-by-factory -->

## Scope

Audit de volledige projectdocumentatie tegen de huidige broncode en breng de documentatie in lijn met wat de code daadwerkelijk doet. Dit is een terugkerende nightly-onderhoudstaak.

In scope:
- Alle documentatie onder `docs/factory/` (README, `functional-spec.md`, `technical-spec.md`, `development.md`, `deployment.md`, `agents/*.md`, `ux/**`).
- Vergelijken van beschreven functionaliteit, commando's, flows, configuratie en agent-rollen met de feitelijke implementatie in `softwarefactory/` (en overige broncode).
- Corrigeren van: ontbrekende functionaliteit die wél in de code zit maar niet in de docs staat, verouderde/onjuiste beschrijvingen, en verwijzingen die niet meer kloppen.

Expliciet buiten scope (niet wijzigen):
- Broncode (geen enkele `.kt`/config/build-wijziging). Code is leidend; de documentatie volgt de code, niet andersom.
- `docs/stories/**` (worklogs, story-historie) — alleen lezen als context over waaróm functionaliteit bestaat, niet bewerken.
- Geen nieuwe features, geen gedrags- of API-wijzigingen.

## Acceptance criteria

- Er zijn geen wijzigingen in broncode of build/config-bestanden; de diff bevat uitsluitend documentatiebestanden (en de SF-607-worklog).
- De documentatie onder `docs/factory/` beschrijft de functionaliteit die in de code aanwezig is; geconstateerde discrepanties zijn óf gecorrigeerd in de docs óf expliciet als bewust-niet-gewijzigd benoemd in de worklog met reden.
- Significante in de code aanwezige functionaliteit die ontbrak in de documentatie is toegevoegd op de juiste plek (passend bij de bestaande docs-structuur).
- Onjuiste of verouderde beschrijvingen, commando's, configuratiesleutels en padverwijzingen zijn gecorrigeerd of verwijderd.
- `docs/stories/worklog/SF-607-worklog.md` is bijgewerkt met: welke documenten zijn gecontroleerd, welke discrepanties zijn gevonden, en welke aanpassingen zijn gedaan (of waarom bewust niets is aangepast).
- Bestaande build/tests blijven groen (er wordt immers geen code gewijzigd); geen testaanpassingen nodig.

## Aannames

- "Documentatie" betreft primair de Markdown/HTML-documentatie onder `docs/` (met nadruk op `docs/factory/`); losse `.agent-tips.md` en inline code-comments vallen buiten scope tenzij ze evident misleidend zijn — die worden dan in de worklog gemeld, niet aangepast.
- Bij twijfel tussen code en documentatie is de **code de bron van waarheid**: de documentatie wordt aangepast aan de code, nooit omgekeerd.
- Als de documentatie en code al volledig in sync zijn, is het een geldig resultaat om alleen de worklog bij te werken met die conclusie (no-op qua docs), zonder code te raken.
- De omvang van wijzigingen blijft proportioneel: alleen feitelijke onjuistheden en duidelijk ontbrekende functionaliteit worden geadresseerd, geen stijl-/herschrijfoefening van correcte documentatie.
- Wijzigingen worden via de gangbare factory-flow (branch `ai/SF-607`, PR naar `main`) aangeleverd; de refiner maakt zelf geen branches/PR's aan.

<!-- test-feedback:start -->
## Test-feedback
Test afgerond. Samenvatting van de verificatie van branch `ai/SF-607` (SF-610, story-brede test):

**Scope/diff** — ✅ `git diff main...HEAD` raakt uitsluitend `docs/factory/functional-spec.md` + `docs/stories/worklog/SF-607-worklog.md`. Geen broncode/build/config. Geen tests om te draaien (docs-only).

**Correctie 1 (poll-filter)** — ✅ Klopt. `YouTrackClient.findWorkIssues` query't per project op `updated desc` en filtert client-side op `AI-supplier`; er is inderdaad geen `Stage = Develop`-filter meer. `SF_YOUTRACK_PROJECTS` (leeg = alle projecten) bevestigd.

**Correctie 2 (AI-suppliers)** — ⚠️ Gedeeltelijk fout. De dashboard-keuzelijst (`none/mock/claude/openai/copilot/microsoft`) en `openai`→Codex CLI kloppen. Maar de toegevoegde zin groepeert **`copilot`/`microsoft` samen als "GitHub Copilot CLI"**, en dat is feitelijk onjuist:

- In `agentworker/.../agent/AiClient.kt` (`AiClientFactory.create`): `"copilot"`/`"github"` → `CopilotAiClient`, maar **`"microsoft"` → `NotImplementedAiClient`** (expliciet niet geïmplementeerd).
- `AiRouting.bucket` routeert alleen `copilot`/`github` naar de copilot-bucket; `microsoft` valt in de `else`-tak (geen model).

De documentatie beschrijft hiermee functionaliteit die de code niet heeft — precies de discrepantie die deze story moet wegnemen, niet introduceren. Dit schendt de acceptance-criterion dat de docs de aanwezige code-functionaliteit beschrijven.

Bevinding genoteerd in de worklog; geen code/docs gewijzigd. Terug naar de developer.

{"agent_tips_update":[{"category":"testing","key":"sf-supplier-microsoft-not-implemented","content":"AI-supplier 'microsoft' is GEEN werkende supplier: AiClientFactory.create (agentworker/.../agent/AiClient.kt) mapt 'microsoft' -> NotImplementedAiClient en AiRouting.bucket routeert alleen copilot/github (niet microsoft) naar de copilot-bucket. Echte CLI's: claude->ClaudeCodeAiClient, openai/codex->CodexAiClient, copilot/github->CopilotAiClient. Docs die 'microsoft' als GitHub Copilot CLI beschrijven zijn feitelijk fout."}]}
{"phase":"test-rejected"}
<!-- test-feedback:end -->

## Eindsamenvatting

I have everything needed. The story is complete and tested. Writing the final summary for the PO.

## Eindsamenvatting — SF-607: Documentatie in lijn brengen met de code

**Wat is gebouwd / gedaan**

Een nightly documentatie-audit: de volledige factory-documentatie onder `docs/factory/` is gecontroleerd tegen de actuele broncode (agent-rollen, factory-flow, build/CLI, config-/secret-sleutels, AI-suppliers, poorten). Code is hierbij leidend; de docs zijn aangepast aan de code, nooit andersom. De diff is bewust **docs-only** — geen broncode, build of config gewijzigd.

Resultaat: 2 feitelijke correcties in `docs/factory/functional-spec.md`.

1. **Poll-filter geactualiseerd.** De doc beschreef nog dat de orchestrator pollt op `Stage = Develop`. Dit veldfilter bestaat niet meer: de orchestrator pollt alle issues van de geconfigureerde projecten (`SF_YOUTRACK_PROJECTS`, leeg = alle niet-gearchiveerde projecten), filtert client-side op `AI-supplier` (niet leeg/niet `none`), en de fase-gate (`AI Phase`) bepaalt de daadwerkelijke pickup.
2. **AI-supplier-overzicht gecompleteerd.** De doc noemde alleen `mock` en `claude`. Toegevoegd: `openai` (Codex CLI) en `copilot` (GitHub Copilot CLI) als werkende suppliers, plus de volledige dashboard-keuzelijst (`none/mock/claude/openai/copilot/microsoft`), met de expliciete kanttekening dat `microsoft` (nog) niet geïmplementeerd is en geen werkende agent oplevert.

**Belangrijke keuzes**

- Bewust **niet** gewijzigd, met reden vastgelegd in de worklog: een in-code commentaar-inconsistentie in `YouTrackClient.kt` (broncode = buiten scope, alleen als bevinding gemeld); het ontbreken van de PLANNER-rol in de high-level flow-opsomming (bewuste samenvatting, de gedetailleerde keten verderop klopt); en de UX-wireframes (geen evidente fouten).
- Grote delen van de docs bleken al in sync met de code (modules/poorten, config-defaults, agent-rollen, ketenvolgorde, CLI, verplichte secrets) en zijn ongemoeid gelaten — geen stijl-/herschrijfoefening op correcte tekst.

**Testresultaat**

Story-brede test: **tested/akkoord**. Geen geautomatiseerde tests van toepassing (docs-only). Eerste testronde wees terecht een fout af — de eerste versie groepeerde `microsoft` samen met `copilot` als "GitHub Copilot CLI", terwijl `microsoft` in de code op een niet-geïmplementeerde client mapt. De developer heeft dit gecorrigeerd; de re-test bevestigde dat alle gedocumenteerde claims nu tegen de code kloppen en de scope docs-only is.

**Bewust niet gedaan**

Geen code-/build-/configwijzigingen, geen nieuwe features, geen aanpassing van `docs/stories/**` of de UX-wireframes, en geen herschrijven van reeds correcte documentatie.
