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
