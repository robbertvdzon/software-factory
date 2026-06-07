# Fase 7 — Oude pad opruimen + PR-comment-route

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

Het oude monolithische story-niveau dev/review/test-pad verwijderen nu de nieuwe
weg werkt, en de PR-comment-feedback in het subtask-model trekken.

## Wijzigingen

- Verwijder uit de router het oude lineaire story-niveau dev/review/test-pad en de
  bijbehorende fasen/overgangen die nu vervangen zijn door de **interne
  loopback** (fase 5):
  - `REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER`, `TESTED_WITH_FEEDBACK_FOR_DEVELOPER`;
  - de oude story-niveau `DEVELOPED`/`REVIEWING`/`TESTING`/`REVIEW_FINISHED`/
    `TESTED_SUCCESSFULLY`-keten (die hoort nu op subtask-niveau thuis).
- **PR-comment-route:** laat late `@factory` PR-comments (de bestaande
  `monitorPullRequests`-loop) een nieuwe **`development`-subtask** op de story
  aanmaken (via `createSubtask`), i.p.v. de story-phase terug te zetten naar
  developer.
- Dode code, ongebruikte fasen en niet meer gebruikte velden opruimen (o.a. de
  laatste resten van `AI Level` als die ergens bleven hangen).
- **`../specs.md`** (de oude v1-spec) bijwerken of expliciet als verouderd
  markeren met een verwijzing naar dit v2-plan.

## Aandachtspunten

- Doe dit pas als fase 2–6 in gebruik en getest zijn; dit is bewust de laatste
  stap.
- Controleer dat er geen lopende issues nog op de oude phase-waarden staan
  (migratiepad / handmatige check) — let op de overgang naar de twee gesplitste
  phase-velden.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/services/OrchestratorService.kt`
- `.../orchestrator/AiPhase.kt` (oude fasen verwijderen)
- `.../github/` + `monitorPullRequests` (PR-comment-route)
- `specs/specs.md` (verouderd markeren / bijwerken)

## Test

- Volledige story-flow draait end-to-end via het nieuwe pad zonder de oude
  fasen.
- Een PR-comment leidt tot een nieuwe development-subtask, niet tot een
  story-phase-reset.

## Klaar wanneer

Het oude pad is verwijderd, de PR-comment-feedback loopt via subtaken, en de
codebase + specs bevatten geen dode v1-flow-resten meer.
