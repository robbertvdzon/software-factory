# SF-244 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

Ik heb `.task.md`, het worklog van SF-244 en de diff sinds `main` doorgenomen. Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-244: Merge altijd automatisch; verwijder awaiting-human merge-gate

### Wat is gebouwd
De MERGE-subtaak mergt voortaan **altijd automatisch** zodra hij aan de beurt is (fase START), ná de handmatige goedkeuring-poort en de voorgaande subtaken. De aparte, configureerbare handmatige merge-poort is volledig vervallen.

Concreet:
- **`MergeSubtaskHandler`** roept bij fase START onvoorwaardelijk `performAutomaticMerge` aan. De tak die voorheen `AWAITING_HUMAN` zette is verdwenen. De overbodig geworden afhankelijkheden (constructor-param `projectRepoResolver`, projectnaam-lookup) zijn opgeruimd.
- **`ProjectRepoResolver`**: de hele `MergeConfig` sealed class, parsing, default, `mergeConfigFor`, het bijbehorende veld, de constructor-param en het inlezen van het `merge:`-blok zijn verwijderd — geen dode verwijzingen meer.
- **`projects.yaml.example`**: `merge:`-blokken en `merge.mode`-documentatie weg bij beide projecten; uitleg herschreven naar "merge altijd automatisch".
- **Specs**: `technical-spec.md` en `functional-spec.md` bijgewerkt (auto-merge, Error-pad i.p.v. AWAITING_HUMAN, verwijderde `merge.mode`).

### Gemaakte keuzes
- **Geen DB-migratie**: eventueel al opgeslagen MERGE-subtaken met fase `AWAITING_HUMAN`/`MANUAL_ACTION_DONE` vallen nu in de `else`-tak → `Skipped` (no-op). Bewust geaccepteerd; betreft alleen lopende runs.
- **Fout-gedrag ongewijzigd**: een merge-conflict of GitHub-fout leidt tot het bestaande Errored-gedrag (duidelijke fout op de subtaak, keten stopt) — niet meer tot awaiting-human.
- **Succespad ongewijzigd**: MERGING → MERGE_APPROVED → door naar DEPLOY.

### Wat is getest
- Scope-tests groen: `MergeSubtaskHandlerTest` + `ProjectRepoResolverMergeDeployTest` → 12 tests, 0 failures.
- Nieuwe test bevestigt dat START altijd MERGING→MERGE_APPROVED doorloopt en **nooit** `AWAITING_HUMAN` zet (AC6); Errored-pad bij merge-fout gedekt (AC4); MERGE_APPROVED→advanceChain naar DEPLOY (AC5).
- Volledige module-suite: 350 tests, **0 failures**, 13 errors — alle pre-existing/omgevingsgebonden (1× ModulithArchitectureTest, 11× Docker-afhankelijke e2e-tests, 1× screenshot-test; geverifieerd als identiek falend op schone `main`). Geen regressies.

### Bewust niet gedaan
- Telegram merge-ready-aanbod-flow (`TelegramReplyService` / `dashboardService.mergeReady`) en het algemene gebruik van `SubtaskPhase.AWAITING_HUMAN` voor de Handmatige goedkeuring-subtaak blijven ongemoeid.
- Geen opruiming van eventueel onbereikbaar geworden Telegram-code (buiten scope).
- Geen DB-migratie van bestaande awaiting-human merge-subtaken.

### Status acceptatiecriteria
AC1 t/m AC6 alle bevestigd door reviewer en tester. Story is groen en consistent met de specs.

> **Let op (News Feed)**: het `personal-feed`-project mergt hierdoor nu ook automatisch na de goedkeuringspoort — dit is bewust gewenst gedrag, maar goed om als PO te bevestigen.

---
