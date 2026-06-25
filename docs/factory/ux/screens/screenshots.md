# Screenshots

## Purpose

Show screenshots captured by the tester for a story.

## Layout

- Back button and title `Screenshots - <issueKey>`.
- Responsive grid of screenshot cards.
- Thumbnail preview.
- Filename and size in footer.
- Optional status/filter chips if screenshots are categorized later.

## Data

- Filename.
- File size.
- Thumbnail URL.
- Full image URL.
- Created timestamp if available.
- Optional tester run id.

## Actions

- Open screenshot full-size.
- Download screenshot.
- Back to story detail.

## States

- No screenshots.
- Broken thumbnail.
- Loading grid.

## Notes

Screenshots are tester artifacts, not marketing media. Keep thumbnails large
enough to compare pages but dense enough for 15-30 images.

De pagina toont uitsluitend echte tester-screenshots: de query
`screenshotEventsForStory` filtert exact op `agent_events.kind =
'tester-screenshot'` (de enige bron, weggeschreven door
`AgentRunCompletionService.syncTesterScreenshots`). Gewone log-events
(claude-user, docker-stdout, documenter-output) die toevallig "screenshot" of
".png" in hun payload bevatten, horen hier niet bij.
