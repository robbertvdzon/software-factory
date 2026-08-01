# SF-1718 - Worklog

Story-context bij eerste pickup:
ManualApproveGateE2eTest: verse fase-assertie + erven van E2eTestBase

Pas uitsluitend softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/ManualApproveGateE2eTest.kt aan; geen productiecode en geen wijziging aan E2eTestBase, AwaitDsl of TrackerTestState.

(a) Verse fase-uitlezing: in de test 'manual-approve poort goedgekeurd zet de keten door naar de merge-subtaak' leest de assertie op r109-113 nu merge.fields.subtaskPhase uit een onveranderlijke snapshot (enforcedChild -> TrackerTestState.childrenOf -> PostgresTrackerClient.subtasksOf), gemaakt vóór het wachten, waardoor de assertie niet kan falen. Lees de fase vers uit de state met state.issue(merge.key)?.fields?.subtaskPhase, in exact dezelfde vorm als de bestaande deploy-check op r125-129. De merge-/deploy-vals blijven bestaan om de subtaak-keys op te halen. De eerste test (r42-77) gebruikt de snapshot alleen voor gate.key en heeft geen assertie op een snapshot-veld; daar is geen inhoudelijke correctie nodig. Vind je tijdens implementatie alsnog een snapshot-afhankelijke assertie, pas die dan op dezelfde vorm aan.

(b) Overerving: maak er 'class ManualApproveGateE2eTest : E2eTestBase()' van. Verwijder de eigen @SpringBootTest- en @Import(E2eTestConfig::class)-annotaties (r28-30), de eigen state/runtime-vals (r32-33), de eigen resetSharedState (r35-40) en de gedupliceerde helpers dispatchCount/awaitDispatchCount (r141-150), plus de daardoor overbodige imports. Let op: de basisklasse heeft al een @BeforeEach resetSharedState - de eigen versie moet verdwijnen, niet overschreven worden. De comment bij dispatchCount die naar E2eTestBase verwees vervalt samen met de helper; klasse-KDoc en overige verklarende commentaren blijven behouden.

Timeouts blijven functioneel gelijk (120 s), de test mag niet strenger worden dan vandaag: vervang AwaitDsl(state, Duration.ofSeconds(120)) door awaiter(Duration.ofSeconds(120)) en geef bij awaitDispatchCount(...) de 120 s expliciet mee (basisklasse-default is 60 s). De twee inline Awaitility.await('manual-approve-subtaak aangemaakt onder ...')-blokken (r64-67 en r99-102) hebben geen equivalent in de basisklasse en blijven ongewijzigd, inclusief hun 120 s.

Afronden: draai een volledige 'mvn verify' (vereist Docker/Testcontainers-Postgres) en zorg dat beide tests van deze klasse onder failsafe slagen. Toon daarnaast aan dat de herstelde assertie écht kan falen: asserteer tijdelijk op "manual-approve-needed" in plaats van null, leg de waargenomen foutmelding vast in docs/stories/worklog/SF-1718-worklog.md en de PR-beschrijving, en draai die tijdelijke wijziging terug zodat die niet in de commit komt. Controleer tot slot dat 'git diff --stat' precies dat ene testbestand (plus de worklog) toont. Is Docker niet beschikbaar, meld dat dan expliciet als blokkade in plaats van zonder volledige verify af te ronden. Voer daarna de review-stap uit op je eigen diff.

Stappenplan:
[x]: read issue and target docs
[x]: (a) verse fase-uitlezing in de merge-assertie
[x]: (b) erven van E2eTestBase, eigen duplicaten verwijderd, 120 s-timeouts expliciet
[x]: mutatiebewijs dat de herstelde assertie kan falen (en teruggedraaid)
[x]: volledige `mvn verify` groen
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- (a) De assertie in `manual-approve poort goedgekeurd zet de keten door naar de merge-subtaak`
  leest de fase nu vers uit de tracker-state (`state.issue(merge.key)?.fields?.subtaskPhase`),
  in exact dezelfde vorm als de al bestaande deploy-check. De `merge`-/`deploy`-vals blijven
  bestaan; die worden alleen nog voor hun (onveranderlijke) subtaak-key gebruikt. De eerste test
  bleek inderdaad geen snapshot-afhankelijke assertie te hebben (alleen `gate.key`), dus daar was
  geen inhoudelijke correctie nodig.
- (b) `class ManualApproveGateE2eTest : E2eTestBase()`. Verwijderd: de eigen `@SpringBootTest` +
  `@Import(E2eTestConfig::class)`, de eigen `state`/`runtime`-vals, de eigen `@BeforeEach
  resetSharedState` (de basisklasse reset óók de Telegram-dubbel en ruimt verouderde
  story-workspaces op) en de gedupliceerde `dispatchCount`/`awaitDispatchCount`-helpers, plus de
  daardoor overbodige imports (`BeforeEach`, `SpringBootTest`, `Import`). Klasse-KDoc en de
  verklarende commentaren zijn behouden; alleen het comment bij de verwijderde `dispatchCount`
  verviel samen met die helper.
- Timeouts functioneel gelijk gehouden (120 s): `AwaitDsl(state, Duration.ofSeconds(120))` →
  `awaiter(Duration.ofSeconds(120))`, en `awaitDispatchCount(story, DEVELOPER, 2,
  Duration.ofSeconds(120))` geeft de duur expliciet mee omdat de basisklasse-default 60 s is. De
  twee inline `Awaitility.await("manual-approve-subtaak aangemaakt onder ...")`-blokken zijn
  ongewijzigd gebleven (geen equivalent in de basisklasse), inclusief hun 120 s.

Bewijs dat de herstelde assertie kán falen (AC6):
- Tijdelijk `"manual-approve-needed"` verwacht i.p.v. `null`; `mvn -f softwarefactory/pom.xml
  verify -Dit.test=ManualApproveGateE2eTest -Dsurefire.skip=true` gaf toen:

  ```
  [ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
  ManualApproveGateE2eTest.manual-approve poort goedgekeurd zet de keten door naar de
  merge-subtaak:95 merge mag nog niet starten zolang de poort op een mens wacht
  ==> expected: <manual-approve-needed> but was: <null>
  org.opentest4j.AssertionFailedError
  ```

- Deze tijdelijke wijziging is teruggedraaid en zit niet in de diff.

Vangnet-bewijs:
- `mvn -B --no-transfer-progress clean verify` vanaf de repo-root: **BUILD SUCCESS**, exitcode 0,
  0 failures / 0 errors (softwarefactory 03:48 min, totaal 04:19 min).
- `TEST-nl.vdzon.softwarefactory.e2e.ManualApproveGateE2eTest.xml`: `tests="2" errors="0"
  skipped="0" failures="0" flakes="0"` — beide tests van deze klasse slagen onder failsafe.
- `git diff --stat` toont precies één gewijzigd bestand
  (`softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/ManualApproveGateE2eTest.kt`,
  7 insertions / 32 deletions); daarnaast alleen dit (nieuwe) worklog.

Specs: geen wijziging aan `docs/factory/`-specs nodig — dit is een test-only wijziging zonder
gedragsverandering in de productiecode; `docs/factory/development.md` beschrijft de
`E2eTestBase`-basis voor e2e-tests al correct, en die beschrijving klopt na deze story juist beter.

## Review (SF-1719, 01-08-2026)

- [info] Volledige story-diff t.o.v. `main` (`git diff main...HEAD --stat`) = precies
  `ManualApproveGateE2eTest.kt` (7 ins / 32 del) + dit worklog. Geen productiecode, geen wijziging
  aan `E2eTestBase`, `AwaitDsl` of `TrackerTestState`, geen `.factory/verification.yaml`. AC3 ✔
- [info] AC1 ✔ — klasse erft van `E2eTestBase()`; eigen `@SpringBootTest`, `@Import`,
  `state`/`runtime`, `@BeforeEach resetSharedState` en `dispatchCount`/`awaitDispatchCount` weg,
  imports opgeruimd (`Awaitility`/`AgentRole`/`Duration` blijven terecht in gebruik).
  De basisklasse-`resetSharedState` is niet `open` en wordt niet overschreven maar geërfd.
- [info] AC2 ✔ — r95-99 leest `state.issue(merge.key)?.fields?.subtaskPhase`
  (`TrackerTestState.issue` r68, nullable) ná `awaitSubtaskPhase(gate.key, ...)`, in dezelfde vorm
  als de deploy-check r111-115. De assertie kan nu daadwerkelijk falen.
- [info] AC4 ✔ — beide `awaiter(Duration.ofSeconds(120))`, `awaitDispatchCount(..., 120 s)` expliciet
  (basisdefault 60 s) en de twee inline `Awaitility.await(...)`-blokken op 120 s: functioneel gelijk.
- [info] AC5/AC6 ✔ — worklog legt `mvn clean verify` BUILD SUCCESS (0 failures/0 errors) en het
  failsafe-XML `tests="2" failures="0" errors="0" flakes="0"` vast, plus het mutatiebewijs
  (`expected: <manual-approve-needed> but was: <null>`), teruggedraaid en niet in de diff.
- [info] Gerichte hercontrole reviewer: `mvn -pl factory-common,softwarefactory -am test-compile`
  → exit 0 (schoon).
- [info] Spec-consistentie: `docs/factory/development.md` r90 beschrijft e2e-tests al "op basis van
  `E2eTestBase`"; deze wijziging brengt de klasse daarmee in lijn. Geen spec-update nodig.
- [suggestie] r34/r71 gebruiken nog `FactoryUiDriver(state)` waar de rest van de e2e-suite
  `loginUi()` uit de basisklasse gebruikt. Functioneel identiek en bewust buiten scope gelaten;
  meenemen bij een volgende opruimronde.

Besluit: goedgekeurd.
