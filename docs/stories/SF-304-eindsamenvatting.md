# SF-304 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

## Eindsamenvatting — SF-304: Eindsamenvatting-bestandsnaam baseren op story-titel + bestaande bestanden hernoemen

**Wat is gebouwd**

Drie samenhangende wijzigingen die ervoor zorgen dat afgeronde stories als `docs/stories/<KEY>-<Titel>.md` worden weggeschreven met de échte story-titel en de originele hoofd-/kleine letters:

1. **Bugfix titelbron** — In `AgentRunCompletionService.writeFinalStoryAfterSummarizer()` wordt de issue nu opgehaald via `issueTrackerClient.getIssue(storyRun.storyKey)` (de parent-story) i.p.v. `request.storyKey` (de summary-subtaak met titel "Eindsamenvatting"). Daardoor krijgt het bestand de echte story-titel als naam i.p.v. `<KEY>-eindsamenvatting.md`.
2. **Slug met behoud van casing** — In `StoryLogWriter.storySlug()` is `.lowercase()` verwijderd en de filter-regex verruimd van `[^a-z0-9]+` naar `[^a-zA-Z0-9]+`. NFD-normalisatie, diacritica-strip (`\p{M}+`), woordscheiding met `-` en de max-8-woordenbegrenzing blijven ongewijzigd. De wijziging is byte-identiek toegepast in beide kopieën (`softwarefactory` én `agentworker`).
3. **Eenmalige rename** — 57 bestanden in `docs/stories/` (24× `*-eindsamenvatting.md` + 33× generiek `*-description.md`, zowel SF- als KAN-keys) zijn via `git mv` hernoemd naar `<KEY>-<titel-slug>.md` (alle R100, dus inhoud + historie behouden). Reeds correct genoemde bestanden en worklogs zijn ongemoeid gelaten; geen naamcollisies.

**Belangrijkste keuzes**
- De twee overige `getIssue(request.storyKey)`-aanroepen (parent-comment-context, markProcessedTrackerComments) zijn bewust buiten scope gelaten.
- YouTrack was in de dev-omgeving niet bereikbaar; parent-titels voor de eindsamenvatting-bestanden zijn daarom hersteld uit de gezaghebbende lokale bron (de titelregel in het bijbehorende worklog, met body-hint als fallback) i.p.v. uit YouTrack. Renames zijn content-preserving, dus het risico is cosmetisch.
- Twee afwijkingen zijn ter verificatie voorgelegd en door admin akkoord bevonden (comment 7-1459): `SF-192` (titel afgeleid uit worklog-proza) en `SF-10-description.md → SF-10-Eindsamenvatting.md` (de bronkop was zelf letterlijk "Eindsamenvatting").

**Wat is getest**
- `StoryLogWriterTest`: 4 tests groen, inclusief een nieuwe test die casing-behoud én diacritica-strip borgt (`Énorme … Café-Module` → `SF-99-Enorme-Refactor-Van-De-Cafe-Module.md`).
- Volledige suite: 351 tests, **0 failures**. De 13 errors zijn pre-existing/omgevingsgebonden (geen docker-daemon: e2e- en screenshot-tests) conform de vastgelegde agent-tips — geen regressies.
- Geverifieerd dat geen enkel `*-eindsamenvatting.md` of generiek `*-description.md` resteert.

**Bewust niet gedaan**
- De interne koppen/`## Story`-secties binnen de hernoemde eindsamenvatting-bestanden zijn niet bijgewerkt naar de echte titel (was "indien nodig" en valt buiten de acceptatiecriteria; puur cosmetisch in historische docs).
- De latente em-dash-inconsistentie in de Kotlin key-prefix-strip (matcht `[-:]`, niet `–—`) is gesignaleerd maar als pre-existing en buiten scope gelaten; hergebruik blijft werken omdat alleen op het `<KEY>-`-prefix wordt gematcht.

Alle acceptatiecriteria zijn gehaald; reviewer en tester hebben akkoord gegeven.
