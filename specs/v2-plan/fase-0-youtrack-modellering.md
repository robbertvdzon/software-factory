# Fase 0 — YouTrack-modellering (fundament)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.

## Doel

Subtaken als first-class issues kunnen **herkennen, koppelen en aanmaken**, zodat
de latere fases erop kunnen bouwen. Nog géén gedragsverandering in de flow.

## Wijzigingen

- **Nieuw YouTrack-veld `Subtask Type`** met waarden `development` / `review` /
  `test` / `manual`.
- **Parent-koppeling** vastleggen: gebruik de YouTrack parent/subtask-link, of
  een expliciet `Parent`-veld. Een subtask moet z'n story kunnen terugvinden.
- `TrackerField` uitbreiden met `SUBTASK_TYPE` (en evt. `PARENT_KEY`)
  — `youtrack/TrackerModels.kt`.
- `TrackerIssueFields` + de YouTrack-parser uitbreiden met deze velden.
- Een **`IssueType`** (STORY/SUBTASK) afleiden: een issue is een subtask als het
  een parent-link en/of een `Subtask Type` heeft.
- **`YouTrackApi.createSubtask(parentKey, type, title, description)`** toevoegen en
  implementeren in `YouTrackClient`. Zet daarbij de **`WORK_TAG`** zodat de
  poller de subtask oppikt.

## Aandachtspunten

- `findWorkIssues()` retourneert na deze fase automatisch zowel stories als
  subtaken (zelfde tag) — dat is gewenst; de router (fase 1) gaat splitsen.
- `createSubtask` moet de juiste velden zetten zodat een subtask later door de
  router herkend wordt als SUBTASK.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/youtrack/TrackerModels.kt`
- `.../youtrack/YouTrackApi.kt`
- `.../youtrack/clients/YouTrackClient.kt`
- `.../youtrack/parsers/` (issue-parsing)

## Test

- Unit: parser herkent subtask vs story correct op basis van parent/`Subtask Type`.
- Integratie: `createSubtask` maakt een sub-issue met parent-link, `Subtask Type`
  en WORK_TAG (tegen YouTrack-sandbox of mock).

## Klaar wanneer

Subtaken kunnen programmatorisch worden aangemaakt en door de bestaande
poll-query worden teruggevonden, zonder dat de huidige story-flow verandert.
