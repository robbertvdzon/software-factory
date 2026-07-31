# Screenshots

## Purpose

Show screenshots captured by the tester for a story.

## Layout

- Back button and title `Screenshots - <issueKey>`.
- Responsive grid of screenshot cards.
- Thumbnail preview.
- Filename and size in footer.
- Optional status/filter chips if screenshots are categorized later.
- Volledig-scherm-viewer boven op het grid: één screenshot per pagina, appbar met
  de naam van het zichtbare screenshot en (bij meer dan één screenshot) een
  positie-indicator, plus pijlknoppen als overlay links en rechts over de
  afbeelding.

## Data

- Filename.
- File size.
- Thumbnail URL.
- Full image URL.
- Created timestamp if available.
- Optional tester run id.

## Actions

- Open screenshot full-size.
- Blader in de volledig-scherm-viewer door de hele screenshotlijst van de story:
  horizontaal swipen, pijlknoppen links/rechts en de pijltjestoetsen. Niet
  circulair; de appbar toont de naam plus een positie-indicator `<huidige> /
  <totaal>` (bij precies één screenshot geen pijlen en geen indicator).
- Download screenshot.
- Back to story detail.

## States

- No screenshots.
- Broken thumbnail.
- Loading grid.
- Viewer: per pagina een laadindicator zolang de afbeelding laadt en dezelfde
  broken-image-fallback als het grid wanneer het laden mislukt; de rest van de
  viewer (bladeren, titel, indicator) blijft dan gewoon werken.
- Viewer: inzoomen geldt per pagina. Zolang er niet is ingezoomd blijft
  horizontaal swipen bladeren; pas na inzoomen versleep je de afbeelding zelf.
  De zoomstand wordt niet bewaard als je naar een andere pagina bladert.
- Viewer: op de eerste pagina is "vorige" uitgeschakeld en op de laatste
  "volgende" — er wordt niet naar de andere kant doorgesprongen.

## Notes

Screenshots are tester artifacts, not marketing media. Keep thumbnails large
enough to compare pages but dense enough for 15-30 images.

De pagina toont uitsluitend echte tester-screenshots: de query
`screenshotEventsForStory` filtert exact op `agent_events.kind =
'tester-screenshot'` (de enige bron, weggeschreven door
`AgentRunCompletionService.syncTesterScreenshots`). Gewone log-events
(claude-user, docker-stdout, documenter-output) die toevallig "screenshot" of
".png" in hun payload bevatten, horen hier niet bij.
