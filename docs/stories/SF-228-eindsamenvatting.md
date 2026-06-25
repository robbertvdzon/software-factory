# SF-228 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

Hieronder de eindsamenvatting voor de PO. Ik heb `.task.md`, het worklog (`SF-228-worklog.md`) en de diff t.o.v. `main` gelezen.

---

## Eindsamenvatting — SF-228: Screenshots-pagina toont alleen echte tester-screenshots

### Wat was het probleem
Op de screenshots-pagina (`/stories/{key}/screenshots`) verschenen nep-PNG-kaartjes met JSON-payloads. De leesquery `screenshotEventsForStory` filterde te breed: via `LIKE '%screenshot%'` en `LIKE '%.png%'` matchten ook gewone agent-log-events (claude-user, docker-stdout, documenter-output) die toevallig die woorden in hun payload hadden.

### Wat is gebouwd
- **Leesquery aangescherpt** (`FactoryDashboardRepository.kt`): het OR-blok met drie LIKE-condities (`lower(ae.kind) LIKE '%screenshot%'`, twee maal `lower(ae.payload::text) LIKE …`) is vervangen door één exacte conditie `AND ae.kind = 'tester-screenshot'` — de enige bron waarmee echte screenshots worden weggeschreven (`AgentRunCompletionService.syncTesterScreenshots`).
- SELECT-kolommen, joins, parameters (`storyRunId`, `limit`), `ORDER BY ae.ts DESC, ae.id DESC` en `LIMIT` zijn **ongewijzigd** gebleven.
- **Nieuwe repo-test** `FactoryDashboardRepositoryScreenshotTest` (Testcontainers Postgres + Flyway, zelfde patroon als de bestaande e2e-tests): seedt 2 echte tester-screenshots + 3 log-events met "screenshot"/".png" in de payload en bewijst dat alléén de tester-screenshots terugkomen (id-DESC), plus een lege lijst voor een story zonder tester-screenshots.
- **Spec bijgewerkt**: `docs/factory/ux/screens/screenshots.md` beschrijft nu expliciet de databron/het filter (`kind = 'tester-screenshot'`).

### Gemaakte keuzes
- **Exacte gelijkheid i.p.v. case-insensitive vergelijking**: de schrijfzijde (`AgentRunCompletionService.kt:469`) zet `kind` letterlijk op `"tester-screenshot"`, dus een exacte match volstaat en is robuuster dan LIKE.
- **Alleen de leeszijde aangeraakt**: geen wijziging aan opslag/upload, attachments, DB-schema of UI-rendering. De bestaande lege-staat ("Nog geen tester-screenshots gevonden.") dekt automatisch het geval van 0 rijen.

### Wat is getest
- `mvn test-compile`: OK (main + nieuwe test compileren).
- `mvn test`: 353 tests, **0 failures**. De 13 errors zijn omgevings-/pre-existing (Modulith-cyclustest faalt ook op schone main; 11 Spring/Testcontainers e2e-tests en de nieuwe repo-test breken af op het ontbreken van een Docker-daemon in de dev-omgeving, niet op testlogica). De Docker-afhankelijke tests draaien in de factory-pipeline.
- Reviewer en tester hebben de diff, AC1–AC5, de schrijfzijde en de teststructuur (kolommen, `FactorySecrets`-constructor) geverifieerd en akkoord gegeven. Alle acceptatiecriteria afgedekt, geen scope creep, geen regressie.

### Bewust niet gedaan
- Geen aanpassing aan opslag/upload van screenshots, attachments-logica, DB-migratie of UI-rendering van de kaartjes.
- De Docker-afhankelijke querytest is niet lokaal gedraaid (geen Docker-daemon beschikbaar); deze draait in de pipeline, conform de bestaande e2e-tests.
- Geen preview-deploy/screenshots gemaakt (geen draaiende UI / `SF_PREVIEW_URL` beschikbaar in de tester-omgeving).

---
