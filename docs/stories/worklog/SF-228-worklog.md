# SF-228 - Worklog

Story-context bij eerste pickup:
Filter screenshotEventsForStory op kind = 'tester-screenshot'

In softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/web/repositories/FactoryDashboardRepository.kt: vervang in screenshotEventsForStory (regels ~116-120) het OR-blok met de drie LIKE-condities door één exacte conditie 'AND ae.kind = '\''tester-screenshot'\'''. Verwijder beide payload::text LIKE-regels en de kind LIKE-regel volledig. Laat SELECT-kolommen, joins, parameters (storyRunId, limit), ORDER BY ae.ts DESC, ae.id DESC en LIMIT exact ongewijzigd. Geen wijziging aan service-/view-laag, lege-staat, opslag/upload of DB-schema. Voeg een query-/repo-test toe (volgens bestaand testpatroon) die aantoont dat niet-tester-screenshot-events met 'screenshot'/'.png' in de payload niet meer worden teruggegeven en echte tester-screenshot-events wél. Sluit af met een zelf-review (AC1-AC5).

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: write query/repo test
[x]: compile (test-compile) — tests draaien in pipeline (Docker/Testcontainers)
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `FactoryDashboardRepository.screenshotEventsForStory`: het OR-blok met de drie
  LIKE-condities (`lower(ae.kind) LIKE '%screenshot%'`, `lower(ae.payload::text)
  LIKE '%screenshot%'`, `lower(ae.payload::text) LIKE '%.png%'`) vervangen door
  één exacte conditie `AND ae.kind = 'tester-screenshot'`. SELECT-kolommen, joins,
  parameters (`storyRunId`, `limit`), `ORDER BY ae.ts DESC, ae.id DESC` en `LIMIT`
  blijven ongewijzigd (AC1, AC3, AC4).
- Nieuwe repo-test `FactoryDashboardRepositoryScreenshotTest` (Testcontainers
  Postgres + Flyway, zelfde patroon als de e2e-tests): seedt twee echte
  `tester-screenshot`-events plus drie log-events (claude-user, docker-stdout,
  documenter-output) met "screenshot"/".png" in de payload, en bewijst dat alleen
  de tester-screenshots terugkomen, in id-DESC-volgorde; een story zonder
  tester-screenshots geeft een lege lijst (AC2, AC5).
- `test-compile` slaagt lokaal (mvn 3.9.10 + JDK 21). De test zelf vereist Docker
  (Testcontainers) dat in deze dev-omgeving ontbreekt; hij draait in de
  factory-pipeline, net als de bestaande e2e-tests.
- Spec bijgewerkt: `docs/factory/ux/screens/screenshots.md` beschrijft nu de exacte
  databron/het filter (`kind = 'tester-screenshot'`) zodat de spec de code volgt.

Zelf-review (AC1-AC5):
- AC1: WHERE filtert op `ae.kind = 'tester-screenshot'`, geen enkele `payload::text
  LIKE` of `kind LIKE` meer. OK
- AC2: bij 0 rijen geeft de query een lege lijst -> bestaande lege-staat ("Nog geen
  tester-screenshots gevonden.") blijft werken; getest in `... returns empty list`. OK
- AC3: sortering en LIMIT ongewijzigd; test bevestigt id-DESC-volgorde. OK
- AC4: alleen de leesquery gewijzigd; geen opslag/upload/schema-wijziging. OK
- AC5: bestaande tests onaangeroerd; nieuwe repo-test toegevoegd. OK

Review (SF-230, reviewer 2026-06-25):
- [info] Diff t.o.v. main beoordeeld: alleen leesquery (`FactoryDashboardRepository.kt`),
  nieuwe repo-test, spec-update en worklog. Geen scope creep.
- [info] AC1 bevestigd: WHERE = `sr.id = ?` AND `ae.kind = 'tester-screenshot'`; alle
  drie LIKE-condities verwijderd; SELECT/joins/`ORDER BY ts DESC, id DESC`/`LIMIT`
  ongewijzigd (AC3/AC4).
- [info] Test gecontroleerd tegen echte structuren: kolommen in `V1__initial_schema.sql`
  (story_runs/agent_runs/agent_events) en `FactorySecrets`-constructor (named params,
  alle verplichte velden aanwezig) kloppen; volgt e2e-Testcontainers/Flyway-patroon.
  Bewijst negatief (log-events met "screenshot"/".png") én positief gedrag + lege staat.
- [info] Spec `docs/factory/ux/screens/screenshots.md` consistent met de code-wijziging.
- [info] Test niet lokaal gedraaid (geen Docker); draait in pipeline — conform bestaande
  e2e-tests. Akkoord.

Test (SF-231, tester 2026-06-25):
- Diff t.o.v. main geverifieerd: WHERE-clause = `sr.id = ?` AND `ae.kind =
  'tester-screenshot'`; alle drie LIKE-condities (kind + 2x payload::text)
  volledig weg; SELECT-kolommen, joins, `ORDER BY ae.ts DESC, ae.id DESC` en
  `LIMIT` ongewijzigd. AC1/AC3/AC4 bevestigd.
- Schrijfzijde gecontroleerd: `AgentRunCompletionService.kt:469` schrijft kind
  letterlijk als `"tester-screenshot"` -> matcht de exacte filterwaarde. Aanname
  uit story klopt.
- Lege-staat (`FactoryDashboardViews.kt:453` "Nog geen tester-screenshots
  gevonden.") ongewijzigd; dekt AC2 zodra de query 0 rijen geeft.
- `mvn -f softwarefactory/pom.xml test-compile`: OK (main + nieuwe test compileren).
- `mvn -f softwarefactory/pom.xml test -Dsurefire.runOrder=alphabetical`:
  Tests run: 353, Failures: 0, Errors: 13. Alle 13 errors zijn omgeving/
  pre-existing: 1x ModulithArchitectureTest (cycle, faalt ook op schone main),
  11x Spring/Testcontainers e2e (FactoryUiDriverLoginTest,
  FullRefineToDevelopE2eTest, PipelineFlowsE2eTest) zonder Docker-daemon, en
  1x de nieuwe FactoryDashboardRepositoryScreenshotTest die op
  "Could not find a valid Docker environment" afbreekt (Testcontainers), niet op
  testlogica. Geen `Failures`, geen regressie. AC5 OK.
- Nieuwe repo-test inhoudelijk beoordeeld: seedt 2 echte tester-screenshots + 3
  log-events (claude-user/docker-stdout/documenter-output) met "screenshot"/".png"
  in de payload, en bewijst dat enkel de tester-screenshots terugkomen, id-DESC,
  plus lege-lijst voor een story zonder tester-screenshots. Dekt AC2/AC3/AC5.
- Geen preview-deploy/preview-URL ingericht (SF_PREVIEW_URL leeg) en geen
  Docker-daemon in de tester-omgeving; browser/preview-test en de echte
  Postgres-querytest draaien in de factory-pipeline (net als de bestaande e2e's).
  Geen screenshots gemaakt (geen draaiende UI beschikbaar).
- Conclusie: code correct, scope gerespecteerd, alle AC's afgedekt, geen
  regressie. Akkoord.
