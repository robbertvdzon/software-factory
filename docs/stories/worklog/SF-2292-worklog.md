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

## Review SF-2293 (23-08-2026)

Akkoord, geen blockers. Gecontroleerd op de volledige story-diff `git diff main...HEAD`
(3 bestanden: extractors, nieuw testbestand, dit worklog).

- Testbewijs geldig: `[FACTORY VERIFICATION EVIDENCE]` heeft `repository-maven-verify` =
  passed/exit 0 en `Tested worktree tree` `77ec8c0a…` = de tree van HEAD (`git cat-file -p HEAD`);
  `testedHeadSha` `7aa66577…` is de parent, zoals verwacht. Geen skips op de wél in scope
  zijnde commando's.
- Gerichte hercontrole (reviewer): `mvn -o -pl agentworker -am test
  -Dtest=AgentOutcomeParserExtrasTest,AgentPromptContractsTest` → exit 0, 9 tests in de nieuwe
  klasse, 0 failures/errors.
- Detekt-ratchet zelf nagemeten: `mvn -o -Pquality -pl agentworker detekt:check` → 86 findings,
  gelijk aan de claim in het worklog en 2 lager dan de 88 op de basis. Geen nieuwe bevindingen.
- Correctheid: `lastNodeWithAnyKey` volgt exact het patroon van `extractKnowledgeUpdates`
  (`asReversed()` + `firstNotNullOfOrNull`), selecteert op `root.has(key)` (aanwezigheid, niet
  geldigheid) en valt bij geen treffer terug op lege `AuditDecisionExtras()`/`SummaryDecisionExtras()`
  zonder exception. De veldverwerking binnen het gekozen blok is letterlijk ongewijzigd.
- Scope: `parse()`, `jsonObjects()`, `repairJson()`, de prompts en bestaande tests zijn niet
  aangeraakt; geen scope creep. `questions` als audit-sleutel is veilig omdat
  `ClaudeCodeAiClient.kt:226-236` de extractors rolgebonden aanroept (AUDITOR/SUMMARIZER), dus de
  `questions` van een reviewer-fase komt hier nooit langs.
- Spec-consistentie: geen doc in `docs/factory/` beschrijft de extractievolgorde; het promptcontract
  "tipsblok vóór het phase-JSON" blijft ongewijzigd. Geen doc-update nodig.
- [suggestie] Geen test dekt de expliciet in de story vastgelegde aanname dat een blok met
  `"score":"n.v.t."` wél als treffer telt en `score = null` oplevert. Nu bewaakt niets dat het
  onderscheid aanwezigheid-vs-geldigheid zo blijft. Klein en niet blokkerend; eventueel mee te
  nemen in de story-brede test SF-2294.

## Test SF-2294 (23-08-2026)

Gedragsverificatie op de story-diff (`git diff main...HEAD`: extractors, nieuw testbestand, worklog).
Geen code of tests aangepast; alle probes draaiden buiten de repo in `/tmp` (opgeruimd).

- **Unittests agentworker**: `mvn -o -B --no-transfer-progress -pl agentworker -am test` → exit 0,
  `Tests run: 73, Failures: 0, Errors: 0, Skipped: 0` (incl. de 9 tests uit
  `AgentOutcomeParserExtrasTest`). Geen flakes.
- **A/B-gedragsproef branch vs. main** (jshell rechtstreeks op `AgentOutcomeParser`, main-classes uit
  een `git archive main`-kopie in `/tmp/mainrepo`) — dit is een onafhankelijke herbevestiging van het
  rood-bewijs uit AC 1:
  - besluitblok eerst + `{"agent_tips_update":[...]}` laatst → **main**: alle audit-velden `null` /
    `questions=[]` en beide summary-velden `null`; **branch**: `score=8.0`, `scoreLabel=goed`,
    voorstel-titel/-beschrijving en `questions=[Q1]` compleet, `descriptionSummary=Lang`,
    `shortDescriptionSummary=Kort`.
  - idem met de blokken in ```json-fences en met slimme aanhalingstekens (`“ ”`) → branch groen,
    main leeg. De normalisatie- en fence-paden zijn dus meegefixt.
  - `parse()` geeft in álle probes op main én branch dezelfde fase terug (`audited`, `summarized`) —
    fase-herkenning ongewijzigd (AC 5).
- **Gedrag bij de voorgeschreven volgorde identiek aan main** (AC 3): tipsblok eerst → beide takken
  geven exact dezelfde extras, inclusief trim/`isNotBlank`-gedrag (`" L "` → `L`, `""` → `null`) en
  het filteren van blanco `questions`.
- **Geen relevant blok → leeg, geen exception** (AC 4): `{"phase":"audited"}` + tipsblok, en input
  helemaal zonder JSON, geven op main én branch lege `AuditDecisionExtras()` /
  `SummaryDecisionExtras()`.
- **Aannames uit de story nagemeten**: `{"score":"n.v.t.","scoreLabel":"onbekend"}` telt als treffer
  (aanwezigheid, niet geldigheid) → `score=null`, `scoreLabel=onbekend`, en er wordt *niet*
  teruggevallen op een eerder blok. Twee besluitblokken (score in het eerste, `questions` in het
  tweede) → alleen het laatste blok mét sleutel wint, geen samenvoeging. Halve `proposedStory`
  (alleen titel) → beide velden `null`. Kapot JSON-blok ná het besluitblok → besluit blijft intact.
- **Wiring**: `ClaudeCodeAiClient.kt:226-236` roept de extractors rolgebonden aan (AUDITOR /
  SUMMARIZER), dus `questions` van andere rollen komt hier niet langs.
- **Detekt-ratchet (AC 7)**: `mvn -o -Pquality -pl <5 modules> detekt:check` → exit 0. Ratchet-delta
  branch vs. main-kopie is **identiek**: beide `new` = [`AgentPromptContracts.kt` TooManyFunctions,
  `AgentRunCompletionService.kt` LargeClass] met dezelfde fingerprints, `resolved` = 5. De ratchet is
  dus al op `main` rood (pre-existent, staat niet in `.factory/verification.yaml`); deze branch voegt
  géén nieuwe bevinding toe. Totaal 775 → **773** findings (twee `MaxLineLength` minder). Let op:
  ondanks 11 → 12 functies in `AgentOutcomeParser` blijft de TooManyFunctions-fingerprint gelijk.
  Kanttekening bij het worklog van SF-2293: de claim "1 bevinding vóór én na" op
  `AgentPromptContracts.kt` klopt niet (20 → 18, vrijwel allemaal pre-existente `MaxLineLength`);
  de conclusie "geen nieuwe bevindingen" klopt wél.
- Geen UI-/preview-oppervlak in deze story, dus geen screenshots.
- Het volledige vangnet (`mvn -B --no-transfer-progress clean verify`) draait de harness
  revisiegebonden ná deze run.
