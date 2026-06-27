# SF-385 - Worklog

Story-context bij eerste pickup:
Inconsistente patronen gladstrijken (gedrags-neutraal)

Scan softwarefactory/ en agentworker/ op afwijkingen t.o.v. de dominante meerderheidsnorm langs naamgeving, structuur, error-handling, logging en code-conventies. Pas alleen gegarandeerd gedrags-neutrale conformeringen toe; expliciet buiten scope (=gedrag): observeerbare output, API/HTTP-contracten, DB-schema/Flyway, env-var-namen (SF_*), YouTrack-velden/enums en publieke contracten. Bij twijfel: niet doen of in error. Integratietests NIET aanpassen; mee-lopende unit-tests bij interne hernoemingen mogen mee. Houd de diff klein en uitsluitend consistentie-wijzigingen.

## Stappenplan

- [x]: read issue and target docs
- [x]: scan codebase op gedrags-neutrale inconsistenties met duidelijke meerderheidsnorm
- [x]: gekozen conformering(en) toepassen
- [x]: relevante tests / build draaien
- [x]: story-log bijwerken met resultaten

## SF-386: aangepakte inconsistentie

### Wildcard-imports → expliciete imports (import-stijl)

**Norm (meerderheid):** de codebase importeert vrijwel overal expliciet per type
(~1068 expliciete `import`-regels). Slechts 3 main-bestanden gebruikten nog een
project-interne wildcard-import (`import nl.vdzon.softwarefactory.<pkg>.*`). De
expliciete-import-stijl is dus de duidelijke meerderheidsnorm; in twee van de drie
bestanden stond zelfs al een expliciete import náást de wildcard (bv.
`import ...core.TrackerCommentParser`), wat de norm bevestigt.

**Geconformeerd (5 wildcard-imports in 3 bestanden → expliciet):**

1. `softwarefactory/.../youtrack/services/AgentCommentContext.kt`
   - Weg: `import nl.vdzon.softwarefactory.youtrack.*` (was ongebruikt),
     `import nl.vdzon.softwarefactory.core.*`
   - Toegevoegd (expliciet, core): `AgentRole`, `TrackerComment`,
     `TrackerCommentParser`, `TrackerIssue`.
2. `softwarefactory/.../youtrack/clients/YouTrackClient.kt`
   - Weg: `import nl.vdzon.softwarefactory.youtrack.*`,
     `import nl.vdzon.softwarefactory.core.*`
   - Toegevoegd (expliciet): `youtrack.YouTrackApi` + core-types
     `AgentRole`, `SubtaskSpec`, `TrackerAttachment`, `TrackerComment`,
     `TrackerCommentParser`, `TrackerField`, `TrackerFieldUpdate`, `TrackerIssue`,
     `TrackerIssueFields`, `TrackerProject`, `YouTrackApiException`.
3. `agentworker/.../agentworker/cli/AgentCli.kt`
   - Weg: `import nl.vdzon.softwarefactory.agent.*`
   - Toegevoegd (expliciet, agent): `AgentContext`, `AgentEvent`, `AgentOutcome`,
     `AiClientFactory`.

**Waarom gedrags-neutraal:** dit is puur een import-vorm. Er is geen regel code,
geen signatuur, geen string, log-niveau of -tekst gewijzigd. De expliciete imports
bevatten exact de symbolen die de wildcard eerder aanleverde (geverifieerd door
de compiler: alle gebruikte referenties resolven nog). Expliciete imports kunnen
naamresolutie hooguit *strikter* (eenduidiger) maken, nooit ander gedrag opleveren.
Geen publiek contract, DB-schema, env-var of YouTrack-veld geraakt.

## Verificatie

- `mvn -f softwarefactory/pom.xml test-compile` — groen (compiler bevestigt dat de
  expliciete imports volledig en correct zijn; geen unresolved references).
- `mvn -f agentworker/pom.xml test-compile` — groen.
- `mvn -f softwarefactory/pom.xml test -Dtest='YouTrackClientTest,AgentCommentContextTest'`
  — groen (de twee aangeraakte softwarefactory-bestanden).
- `mvn -f agentworker/pom.xml test` — groen (volledige agentworker-suite, dekt
  `AgentCli`).
- Geen integratietest aangepast; geen testbestand gewijzigd.

Bekende, niet door deze story veroorzaakte main-failures (zie agent-tips:
`ModulithArchitectureTest`, `AgentResultFileCompletionPollerTest` onder volledige
run) zijn bewust niet getriggerd; de wijziging is import-only en raakt geen
module-grenzen of pollers.

## Niet aangepakt (bewust, om gedragsrisico te vermijden)

- `LoggerFactory.getLogger(ProjectRepoResolver::class.java)` t.o.v. de 32×
  `getLogger(javaClass)`: dit is géén inconsistentie. De `::class.java`-variant staat
  in een `companion object`, waar `javaClass` naar de Companion-klasse zou verwijzen
  en dus de logger-naam zou veranderen → niet gedrags-neutraal. Contextueel correct,
  niet aangeraakt.
- Overige stijlverschillen (collection-constructors, elvis-`?: return` vs `?: throw`,
  `get/find/read`-naamgeving) bleken contextueel betekenisvol of zonder duidelijke
  meerderheidsnorm; conform de scope ("verzin geen nieuwe norm") niet aangeraakt.

## Specs

Geen `docs/factory`-spec geraakt: dit is een interne import-stijl-conformering die de
beschreven functionaliteit, stack of conventies niet wijzigt. De `SF_`-prefix-conventie
en overige technische specs blijven ongewijzigd van toepassing.

## Review (SF-386, reviewer)

Volledige story-diff `main...HEAD` beoordeeld: 3 implementatiebestanden (alleen import-blok)
+ worklog. Statisch geverifieerd:

- `AgentCommentContext.kt`: gebruikt enkel `TrackerIssue`, `AgentRole`, `TrackerComment`,
  `TrackerCommentParser` — alle vier expliciet geïmporteerd; `youtrack.*` was inderdaad
  ongebruikt. ✓
- `YouTrackClient.kt`: `youtrack.*` leverde alleen `YouTrackApi` (enige type in pkg-root),
  `core.*` de 11 `Tracker*`/`AgentRole`/`SubtaskSpec`/`YouTrackApiException`-types — alle
  expliciet teruggeplaatst. De `*CustomField`-namen zijn string-literals (`"\$type" to ...`),
  geen types; `FactorySecrets`/`ProjectRepoResolver`/`CallMetrics` hadden al eigen imports;
  lokaal gedefinieerde types (`YouTrackResponse`, `FieldSpec`, …) ongewijzigd. ✓
- `AgentCli.kt`: `agent.*` → `AgentContext/AgentEvent/AgentOutcome/AiClientFactory`;
  `AgentRole` komt los uit `youtrack.AgentRole` (eigen, reeds bestaande import) en is dus
  niet door de wildcard geraakt. ✓

[info] Geen gedragswijziging: enkel import-vorm, geen signatuur/string/logging gewijzigd.
[info] Geen testbestanden of integratietests aangeraakt; geen API/DB/env-var/YouTrack-veld.
[suggestie] (out of scope, niet blokkerend) `AgentRole` bestaat zowel als `core.AgentRole`
  als `youtrack.AgentRole` — een pre-existing inconsistentie buiten deze story; terecht niet
  meegenomen (geen duidelijke gedrags-neutrale conformering, kan publieke namen raken).

Conform scope, AC's en specs. Akkoord.
