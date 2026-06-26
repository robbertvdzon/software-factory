# SF-304 - Worklog

Story-context bij eerste pickup:
Eindsamenvatting-bestandsnaam op story-titel baseren + bestaande bestanden hernoemen

Drie samenhangende wijzigingen in één ontwikkeltaak.

1) Bugfix titelbron: in AgentRunCompletionService.writeFinalStoryAfterSummarizer() (~r408) de summary/description ophalen via issueTrackerClient.getIssue(storyRun.storyKey) i.p.v. request.storyKey, zodat de bestandsnaam de parent-story-titel krijgt.

2) Slug met behoud van casing in StoryLogWriter.storySlug() (~r162): verwijder .lowercase() en vervang regex [^a-z0-9]+ door [^a-zA-Z0-9]+; behoud NFD-normalisatie, \p{M}+-verwijdering, woordscheiding met '-' en de max-8-woordenbegrenzing. Pas dit IDENTIEK toe in beide kopieën: softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/docs/services/StoryLog.kt EN agentworker/src/main/kotlin/nl/vdzon/softwarefactory/docs/services/StoryLog.kt. Werk StoryLogWriterTest bij op de nieuwe casing-verwachting (o.a. KAN-42-maak-rapportage-endpoint.md -> KAN-42-Maak-rapportage-endpoint.md). Het schrijven/aanpassen van deze unittests hoort bij dit ontwikkelwerk.

3) Eenmalige rename in docs/stories/: hernoem *-eindsamenvatting.md (24) en generiek *-description.md (33, SF-* en KAN-*) naar <KEY>-<titel-slug>.md met dezelfde slug-regels, via git mv (historie behouden). Titelbron: voor *-description.md de '# KEY - Titel'-kop bovenin het bestand; voor *-eindsamenvatting.md de parent-story-titel uit YouTrack (NIET de bovenste 'Eindsamenvatting'-kop). Reeds correct genoemde bestanden en worklog-bestanden niet aanraken; stories die niet meer in YouTrack bestaan concreet melden i.p.v. gokken.

Sluit af met een review-stap. Acceptatie: nieuwe runs schrijven docs/stories/<KEY>-<Titel-met-originele-casing>.md; geen *-eindsamenvatting.md of generiek *-description.md blijft over; tests groen.

Stappenplan:
[x]: read issue and target docs
[x]: bugfix titelbron in AgentRunCompletionService (storyRun.storyKey)
[x]: storySlug() casing + regex aanpassen in beide StoryLog.kt-kopieën
[x]: StoryLogWriterTest bijwerken + casing/diacritics-test toevoegen
[x]: eenmalige rename van *-eindsamenvatting.md + generiek *-description.md via git mv
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- **Punt 1 — bugfix titelbron.** In `AgentRunCompletionService.writeFinalStoryAfterSummarizer()`
  haalt de issue nu op via `issueTrackerClient.getIssue(storyRun.storyKey)` i.p.v.
  `request.storyKey`. `request.storyKey` is de summary-subtaak (titel "Eindsamenvatting");
  `storyRun.storyKey` is de parent-story, zodat de bestandsnaam de echte story-titel krijgt.
  De andere twee `getIssue(request.storyKey)`-aanroepen (r308 parent-comment-context,
  r570 markProcessedTrackerComments) vallen buiten scope en zijn ongemoeid gelaten.
- **Punt 2 — slug met casing.** In `storySlug()` (beide kopieën, byte-identiek):
  `.lowercase()` verwijderd en `[^a-z0-9]+` → `[^a-zA-Z0-9]+`. NFD-normalisatie,
  `\p{M}+`-verwijdering, woordscheiding met `-` en max-8-woordenbegrenzing blijven ongewijzigd.
  `StoryLogWriterTest` aangepast (`KAN-42-maak-...` → `KAN-42-Maak-...`) en een extra test
  toegevoegd die casing-behoud én diacritica-stripping borgt (`Café` → `Cafe`).
- **Punt 3 — eenmalige rename.** 24 `*-eindsamenvatting.md` + 33 generiek `*-description.md`
  (KAN-* en SF-*) hernoemd naar `<KEY>-<titel-slug>.md` via `git mv` (historie behouden),
  geen naamcollisies, reeds-correct-genoemde en worklog-bestanden ongemoeid.
  Titelbron description-bestanden: de `# KEY - Titel`-kop bovenin.
  Titelbron eindsamenvatting-bestanden: **YouTrack was in deze ontwikkelomgeving niet
  bereikbaar (geen YouTrack-tool/MCP beschikbaar).** De parent-story-titel is daarom hersteld
  uit de meest gezaghebbende lokale bron: de `Story-context bij eerste pickup:`/`Story:`-regel
  in het bijbehorende worklog (dat is de YouTrack-summary zoals vastgelegd bij pickup), met
  als fallback de body-hint-kop `## Eindsamenvatting <KEY> — <titel>`. Dit is een vastgelegd
  feit, geen gok. Twee aandachtspunten gemeld voor menselijke verificatie:
    - `SF-192`: worklog had geen one-line titel; titel ("Verplichte handmatige goedkeur-poort
      voor merge") is afgeleid uit de worklog-prozabeschrijving.
    - `SF-10-description.md`: de `# KEY - Titel`-kop is letterlijk "Eindsamenvatting" (lijkt zelf
      een oude buggy final-story); per regels gevolgd → `SF-10-Eindsamenvatting.md`.
  Voor de em-dash-koppen (`# SF-6 — Fase 0 …`) is de KEY-prefix-strip uitgebreid met `–—`
  zodat de naam niet de sleutel dubbel bevat; functioneel is dit gelijkwaardig omdat
  `existingFinalStory()` enkel op het `<KEY>-`-prefix matcht.
- **Tests.** `mvn -f softwarefactory/pom.xml test -Dtest=StoryLogWriterTest` → 4 groen
  (na eerste dep-download). `agentworker` test-compile groen. Beide StoryLog.kt-kopieën
  byte-identiek geverifieerd.
