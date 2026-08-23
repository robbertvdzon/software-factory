# SF-2292 - [Audit] Parsertests voor het audit- en samenvattingsbesluit: een afsluitend agent_tips_update-blok mag het besluit niet wegvagen

## Story

[Audit] Parsertests voor het audit- en samenvattingsbesluit: een afsluitend agent_tips_update-blok mag het besluit niet wegvagen

<!-- refined-by-factory -->

## Scope

`AgentOutcomeParser.extractAuditExtras` (`agentworker/src/main/kotlin/nl/vdzon/softwarefactory/agent/ai/shared/AgentPromptContracts.kt:479-497`) en `extractSummaryExtras` (`:498-506`) pakken nu het *laatste blok dat parseert*. De systeemprompt schrijft voor dat `{"agent_tips_update":[...]}` vóór het phase-JSON komt (`tipsPrompt()`, ~`:381`), maar niets dwingt dat af. Staat het tipsblok toch als laatste, dan wordt de fase nog wel herkend (`parse()` filtert op blokken mét `phase`, `:419-424`) maar zijn `score`, `scoreLabel`, `proposedStoryTitle`, `proposedStoryDescription`, `questions`, `descriptionSummary` en `shortDescriptionSummary` allemaal leeg. Dat verlies is volledig stil: `AuditGatewayAdapter.proposeStoryIfAny` valt op `?: return null` en `AgentRunCompletionService.writeFinalStoryAfterSummarizer` slaat beide schrijfacties over — geen fout, geen waarschuwing, geen story, geen changelogregel. Beide extractors hebben nul tests.

Twee wijzigingen:

1. **Maak beide extractors sleutelbewust**, exact volgens het bestaande patroon van de buurfunctie `extractKnowledgeUpdates` (`:458-477`): zoek van achter naar voren het eerste JSON-blok dat daadwerkelijk minstens één van de relevante sleutels bevát, in plaats van het eerste blok dat parseert.
   - `extractAuditExtras`: relevante sleutels `score`, `scoreLabel`, `proposedStory`, `questions`.
   - `extractSummaryExtras`: relevante sleutels `descriptionSummary`, `shortDescriptionSummary`.
   - Wordt geen enkel blok met een relevante sleutel gevonden, dan blijft het gedrag exact als nu: een lege `AuditDecisionExtras()` respectievelijk `SummaryDecisionExtras()`. Geen exception.
   - Binnen het gevonden blok blijft de veldverwerking ongewijzigd (o.a. `score` alleen bij `isNumber`, trim + `takeIf { isNotBlank() }`, en de regel dat een `proposedStory` alleen telt bij titel *én* beschrijving).
   - Verwachte omvang: enkele regels per functie. Geen wijziging aan `parse()`, `jsonObjects()`, `repairJson()` of aan de prompts.

2. **Nieuw testbestand** `agentworker/src/test/kotlin/nl/vdzon/softwarefactory/agent/AgentOutcomeParserExtrasTest.kt` (package `nl.vdzon.softwarefactory.agent`, JUnit 5 + `org.junit.jupiter.api.Assertions`, conform `AgentPromptContractsTest.kt`), met voor `extractAuditExtras` én `extractSummaryExtras` minimaal deze gevallen:
   - voorgeschreven volgorde (tipsblok eerst, besluitblok laatst) → alle velden correct;
   - omgekeerde volgorde (besluitblok eerst, `{"agent_tips_update":[...]}` laatst) → alle velden nog steeds correct;
   - losse prozatekst ná het besluitblok (geen JSON) → velden nog steeds correct;
   - een `proposedStory`-description die zelf een `}` of een geciteerd JSON-fragment bevat → komt heel door (regressievangnet in de klasse van commit `1817b43`);
   - een run zonder score/voorstel/samenvattingen (alleen `{"phase":...}` + tipsblok) → alle velden `null` c.q. lege lijst, geen exception.

## Acceptance criteria

1. De twee 'omgekeerde volgorde'-tests (audit én summary) falen aantoonbaar op de huidige implementatie; dit wordt daadwerkelijk gecontroleerd (test eerst rood, dan pas de extractor aanpassen) en het rode resultaat wordt in het worklog vastgelegd.
2. Na de aanpassing slagen alle tests in `AgentOutcomeParserExtrasTest.kt`.
3. Bij de voorgeschreven volgorde verandert er niets aan het gedrag van beide extractors.
4. Als geen enkel JSON-blok een relevante sleutel bevat, geven de extractors nog steeds een lege `AuditDecisionExtras()` / `SummaryDecisionExtras()` terug zonder exception.
5. `parse()` en de fase-herkenning blijven ongewijzigd; er verandert niets aan de prompts (`AgentPromptBuilder`/`tipsPrompt()`) of aan bestaande tests.
6. `mvn verify` is groen.
7. De detekt-ratchet blijft groen (geen nieuwe blocking findings).

## Aannames

- **Bewust buiten scope**, om de story klein te houden: dat `CopilotAiClient` en `CodexAiClient` deze twee extractors helemaal niet aanroepen (geverifieerd: alleen `ClaudeCodeAiClient.kt:227` en `:232` roepen ze aan); de nog ongedekte uitleverkant van de changelog (`DashboardQueryService`, bridge-operatie `changelog.for`, `ChangelogController`); en de bestaande dekkingsillusies elders.
- De 'omgekeerde volgorde' wordt in de tests gesimuleerd op tekstniveau (een string met beide JSON-blokken in de betreffende volgorde), rechtstreeks tegen `AgentOutcomeParser`; er is geen AI-client of run nodig.
- De `}`-in-description-test is naar verwachting **al groen** vóór de aanpassing: `jsonObjects()` (`:621-654`) is sinds commit `1817b43` string-bewust (`inString`/`escaped`). Die test is dus een regressievangnet, geen tweede rode test; alleen de omgekeerde-volgorde-tests hoeven rood te starten.
- 'Sleutel bevat' betekent: het veld is aanwezig in het JSON-object (bijv. via `has(...)`/`path(...).isMissingNode`), niet dat de waarde ook geldig of niet-leeg is. Een besluitblok met `"score": "n.v.t."` telt dus als treffer en levert `score = null` op — dat is bewust hetzelfde gedrag als nu bij de `isNumber`-check.
- Er wordt één blok gekozen per extractor (het laatste blok mét een relevante sleutel); velden worden niet over meerdere blokken samengevoegd. Dat matcht `extractKnowledgeUpdates` en de promptregel 'nooit meer dan 1 `proposedStory` per run'.
- Geen documentatie-updates nodig: dit is interne parser-robuustheid zonder gedrags- of contractwijziging naar buiten.

## Eindsamenvatting

Ik heb `.task.md`, het worklog `docs/stories/worklog/SF-2292-worklog.md` en de volledige story-diff (`git diff main...HEAD`) gecontroleerd. De diff bevat exact drie bestanden en komt overeen met wat er in het worklog beschreven staat.

## Eindsamenvatting SF-2292 (voor de PO)

**Wat het probleem was**
De agent-uitvoerparser (`AgentOutcomeParser`) pakte voor het audit- en samenvattingsbesluit simpelweg *het laatste JSON-blok dat parseerde*. De promptregel schrijft voor dat een agent zijn `{"agent_tips_update":[...]}`-blok vóór het phase-JSON zet, maar niets dwong dat af. Zette een agent het tipsblok toch als laatste, dan werd de fase nog wel herkend, maar waren `score`, `scoreLabel`, de voorgestelde story (titel/beschrijving), `questions`, `descriptionSummary` en `shortDescriptionSummary` allemaal stil leeg — geen story, geen changelogregel, geen foutmelding of waarschuwing. Beide functies hadden nul tests.

**Wat er gebouwd is**
- `AgentPromptContracts.kt`: één nieuwe private helper `lastNodeWithAnyKey(text, keys)` die van achter naar voren het eerste JSON-blok teruggeeft dat minstens één relevante sleutel *bevat*. Dit volgt exact het bestaande patroon van de buurfunctie `extractKnowledgeUpdates` (`asReversed()` + `firstNotNullOfOrNull`). Twee sleutel-lijsten als private vals: audit (`score`, `scoreLabel`, `proposedStory`, `questions`) en summary (`descriptionSummary`, `shortDescriptionSummary`).
- `extractAuditExtras` en `extractSummaryExtras` gebruiken die helper. Netto +19/-4 regels in de productiecode.
- Nieuw testbestand `AgentOutcomeParserExtrasTest.kt` (9 tests, JUnit 5).

**Gemaakte keuzes**
- *Aanwezigheid, niet geldigheid*: een blok met `"score": "n.v.t."` telt als treffer en levert `score = null` op — bewust hetzelfde gedrag als voorheen, en niet terugvallen op een ouder blok.
- *Eén blok wint*, geen samenvoeging over meerdere blokken; dat matcht `extractKnowledgeUpdates` en de promptregel "nooit meer dan 1 `proposedStory` per run".
- *Geen treffer → onveranderd leeg resultaat*, geen exception.
- *Eén gedeelde helper* in plaats van twee losse lussen, om het functieaantal in het object laag te houden (detekt `TooManyFunctions`) zonder suppressie.
- `parse()`, `jsonObjects()`, `repairJson()`, de prompts en alle bestaande tests zijn niet aangeraakt.

**Wat er getest is**
- AC 1 aantoonbaar gehaald: de tests draaiden **eerst rood** op de oude implementatie (`Tests run: 9, Failures: 3`; de twee omgekeerde-volgorde-tests faalden met `expected: <7.5> but was: <null>` resp. `expected: <Lange samenvatting> but was: <null>`). Vastgelegd in het worklog.
- Na de aanpassing: alle 9 nieuwe tests groen; `mvn -pl agentworker -am test` → 73 tests, 0 failures/errors.
- `mvn -B clean verify` op repo-root: **BUILD SUCCESS**, alle modules inclusief de e2e-Testcontainers-suite.
- Tester deed een onafhankelijke A/B-gedragsproef (branch vs. een kopie van `main`) rechtstreeks op de parser: omgekeerde volgorde geeft op main lege velden en op de branch complete velden — ook binnen ```json-fences en met slimme aanhalingstekens. `parse()` geeft in álle probes op beide takken dezelfde fase terug (AC 5 bevestigd). Voorgeschreven volgorde geeft op beide takken identieke resultaten (AC 3).
- Detekt-ratchet: geen nieuwe blocking findings; de branch heeft er zelfs twee `MaxLineLength` minder (775 → 773). Kanttekening van de tester: de ratchet-delta bevat twee `new`-items (`TooManyFunctions`, `LargeClass`), maar die zijn **identiek aanwezig op `main`** — pre-existent, niet door deze story veroorzaakt.

**Bewust niet gedaan**
- De prompts (`tipsPrompt()`) blijven ongewijzigd; het contract "tipsblok vóór het phase-JSON" blijft staan, de parser is er alleen niet meer afhankelijk van.
- Geen documentatie-updates in `docs/factory/`: interne parser-robuustheid zonder gedrags- of contractwijziging naar buiten.
- Buiten scope gehouden (uit de refined story): dat `CopilotAiClient`/`CodexAiClient` deze extractors helemaal niet aanroepen, en de nog ongedekte uitleverkant van de changelog (`DashboardQueryService`, `changelog.for`, `ChangelogController`).
- Openstaande, niet-blokkerende suggestie van de reviewer: er is nu een test voor de `"score":"n.v.t."`-aanname toegevoegd door de tester als *probe*, maar niet als vast testbestand-geval — dit is niet permanent afgedekt.
