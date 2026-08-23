# SF-2292 - Worklog

## Story in eigen woorden

`AgentOutcomeParser.extractAuditExtras` en `extractSummaryExtras` pakten het *laatste JSON-blok dat
parseert*. Zet een agent zijn `{"agent_tips_update":[...]}`-blok als laatste (de prompt schrijft
"eerst tips, dan phase" voor maar dwingt niets af), dan wordt de fase nog wel herkend, maar zijn
`score`, `scoreLabel`, `proposedStory*`, `questions`, `descriptionSummary` en
`shortDescriptionSummary` stil leeg — geen story, geen changelogregel, geen waarschuwing.

Oplossing: beide extractors sleutelbewust maken volgens het patroon van de buurfunctie
`extractKnowledgeUpdates` (van achter naar voren het eerste blok dat minstens één relevante sleutel
*bevat*), plus een nieuw testbestand dat het gedrag vastlegt.

## Checklist

[x]: story, `docs/factory/development.md` en bestaande parsercode gelezen
[x]: `AgentOutcomeParserExtrasTest.kt` geschreven en EERST rood gedraaid
[x]: rood resultaat van de twee omgekeerde-volgorde-tests vastgelegd (zie hieronder)
[x]: `extractAuditExtras` + `extractSummaryExtras` sleutelbewust gemaakt
[x]: nieuwe tests groen
[x]: volledig vangnet `mvn -B --no-transfer-progress clean verify` groen
[x]: detekt-ratchet gecontroleerd tegen een HEAD-worktree

## Rood-bewijs vóór de aanpassing

`mvn -pl agentworker -am test -Dtest=AgentOutcomeParserExtrasTest` → `Tests run: 9, Failures: 3`:

- `AgentOutcomeParserExtrasTest.audit-extras komen door als het tipsblok als laatste blok staat`
  → `AssertionFailedError: omgekeerde volgorde: besluitblok eerst, tipsblok laatst ==> expected: <7.5> but was: <null>`
- `AgentOutcomeParserExtrasTest.summary-extras komen door als het tipsblok als laatste blok staat`
  → `AssertionFailedError: omgekeerde volgorde: besluitblok eerst, tipsblok laatst ==> expected: <Lange samenvatting> but was: <null>`

De derde failure in die eerste run was de `}`-in-description-test, die ik in eerste opzet per
ongeluk óók een afsluitend tipsblok had gegeven — daarmee testte hij de volgorde in plaats van de
string-bewuste `jsonObjects()`. Het tipsblok is uit die test gehaald; hij is sindsdien (zoals de
story voorspelt, commit `1817b43`) meteen groen en fungeert puur als regressievangnet.

## Wat ik gedaan heb en waarom

- `agentworker/.../ai/shared/AgentPromptContracts.kt`:
  - één private helper `lastNodeWithAnyKey(text, keys)` toegevoegd die
    `jsonObjects(normalize(text)).asReversed()` aflopen en het eerste blok teruggeeft waarvoor
    `keys.any { root.has(it) }` geldt. `has(...)` = *aanwezigheid*, niet geldigheid, dus een blok met
    `"score": "n.v.t."` telt als treffer en levert (net als nu) `score = null`.
  - `AUDIT_EXTRA_KEYS` (`score`, `scoreLabel`, `proposedStory`, `questions`) en
    `SUMMARY_EXTRA_KEYS` (`descriptionSummary`, `shortDescriptionSummary`) als private vals.
  - Beide extractors gebruiken die helper; geen treffer → onveranderd lege
    `AuditDecisionExtras()` / `SummaryDecisionExtras()`, geen exception. De veldverwerking binnen het
    gekozen blok is letterlijk ongewijzigd (`isNumber` voor score, trim + `takeIf { isNotBlank() }`,
    proposedStory alleen bij titel én beschrijving). Eén helper i.p.v. twee losse lussen houdt het
    functieaantal in het object laag (detekt `TooManyFunctions`) zonder suppressie.
  - `parse()`, `jsonObjects()`, `repairJson()`, de prompts en bestaande tests zijn niet aangeraakt.
- Nieuw: `agentworker/src/test/kotlin/nl/vdzon/softwarefactory/agent/AgentOutcomeParserExtrasTest.kt`
  (9 tests) met per extractor: voorgeschreven volgorde, omgekeerde volgorde, proza ná het
  besluitblok, en de lege run (alles `null` / lege lijst); plus de `}`/geciteerde-JSON-test op de
  audit-`proposedStory`-description.

## Bewijs

- `mvn -B --no-transfer-progress clean verify` (repo-root): **BUILD SUCCESS**, exitcode 0, alle
  modules groen (softwarefactory-e2e incl. Testcontainers 4:43 min, totaal 5:20 min), 0 failures /
  0 errors.
- Detekt-ratchet: `mvn -Pquality -pl agentworker detekt:check` geeft **86** findings in de working
  tree tegen **88** in een `git worktree add --detach /tmp/base HEAD`-kopie met dezelfde run. Het
  enige verschil is `MaxLineLength` 37 → 35 (de twee lange extractor-regels zijn korter geworden).
  Geen nieuwe bevindingen, ook niet op `AgentPromptContracts.kt` (1 bevinding vóór én na).

## Specs

Geen aanpassingen in `docs/factory/` nodig: dit is interne parser-robuustheid zonder gedrags- of
contractwijziging naar buiten (het gedocumenteerde promptcontract "tipsblok vóór het phase-JSON"
blijft exact zoals het is; de parser is er nu alleen niet meer afhankelijk van).
