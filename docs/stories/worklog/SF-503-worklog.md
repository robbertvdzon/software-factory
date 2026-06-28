# SF-503 - Worklog

Story-context bij eerste pickup:
ADR-naleving controleren en veilig herstellen

Zoek aantoonbaar naar ADR's langs drie sporen: vaste locatie docs/adr/, repo-brede zoekactie op bestandsnaam (*adr*) en zoekactie op decision-record-structuur (Context/Decision/Consequences-headings) in *.md. Sluit expliciet uit als ADR-bron: .factory/nightly/adr/, .task.md en de story-/worklog-bestanden in docs/stories/ over ADR-naleving zelf. Pas de beslisboom toe: geen ADR's -> no-op zonder code-/gedragswijziging; ADR's aanwezig en gevolgd -> geen wijziging; ADR's met puur structureel/conventioneel (gedrags-neutraal) herstelbare afwijking -> code in lijn brengen en per ADR kort vastleggen wat is aangepast; ADR's met afwijking die alleen via gedrags-/functiewijziging herstelbaar of inhoudelijk onduidelijk is -> GEEN codewijziging maar story in error met betreffende ADR en afwijking benoemd (silent-nightly: niet wachten). Functioneel gedrag blijft in alle gevallen exact gelijk en bestaande tests blijven slagen. Leg de bevinding (aanwezig/afwezig en welke), de doorzochte locaties en gehanteerde uitsluitingen vast in docs/stories/worklog/SF-503-worklog.md. Sluit af met een review-stap. Verwachte uitkomst gezien de huidige repo: geldige no-op met worklog-notitie.

## Story in eigen woorden (SF-510)

Controleer aantoonbaar of de codebase de vastgelegde Architecture Decision Records (ADR's)
nog volgt en herstel afwijkingen alleen als dat puur structureel/conventioneel kan, zonder
enige observeerbare gedragswijziging. Zijn er geen ADR's, dan is dit een geldige no-op: geen
code- of gedragswijziging, alleen de bevinding (inclusief doorzochte locaties en uitsluitingen)
in dit worklog vastleggen. Bij een afwijking die alleen via een gedrags-/functiewijziging te
herstellen is, of die inhoudelijk onduidelijk is: géén code aanpassen en de story in error
zetten (silent-nightly: niet wachten op een mens).

## Stappenplan

[x]: read issue and target docs
[x]: ADR-zoekactie uitvoeren (docs/adr/ + repo-breed op bestandsnaam + decision-record-structuur)
[x]: treffers beoordelen en uitsluitingen onderbouwen
[x]: spec-tak bepalen en bevinding vastleggen
[x]: review-stap (functioneel gedrag exact gelijk — geen codewijziging)
[x]: update story-log with results

## ADR-zoekactie (aantoonbaar)

Uitgevoerd op repo-staat van branch `ai/SF-503` (2026-06-28).

### Spoor 1 — Gangbare ADR-locatie

- `docs/adr/` → **bestaat niet** (`ls docs/adr/` → "No such file or directory").

### Spoor 2 — Repo-brede zoek op bestandsnaam

Commando: `find . -iname '*adr*' -not -path './.git/*'`

Treffers:
- `./docs/stories/SF-436-nightly-ADR-naleving-herstellen.md`
- `./docs/stories/SF-372-nightly-ADR-naleving-herstellen.md`
- `./.factory/nightly/adr` (directory met `job.yaml` + `story.md`)

### Spoor 3 — Decision-record-structuur in `*.md`

Commando 1: `grep -rilE '^#+\s*(Context|Decision|Consequences)\b' --include='*.md' .`
→ **geen treffers**.

Commando 2 (tekstueel "decision record"): `grep -rilE 'decision record' --include='*.md' .`
Treffers:
- `./.task.md`
- `./docs/stories/SF-436-nightly-ADR-naleving-herstellen.md`
- `./docs/stories/SF-372-nightly-ADR-naleving-herstellen.md`
- `./docs/stories/worklog/SF-372-worklog.md`
- `./docs/stories/worklog/SF-436-worklog.md`
- `./.factory/nightly/adr/story.md`

Commando 3 (Context + Decision + Consequences samen in één bestand):
→ **geen enkel `*.md` bevat alle drie de ADR-headings**.

## Beoordeling van de treffers en gehanteerde uitsluitingen

Conform de spec expliciet uitgesloten als ADR-bron:

- `.factory/nightly/adr/` (`job.yaml` + `story.md`) → de nightly-**jobdefinitie** van déze
  ADR-naleving-story, geen besluitregister.
- `.task.md` → de **taakomschrijving** van deze run.
- `docs/stories/SF-436-…md`, `docs/stories/SF-372-…md` en de bijbehorende worklogs
  (`SF-436-worklog.md`, `SF-372-worklog.md`) → **projecthistorie óver ADR-naleving**
  (eerdere stories/worklogs), geen Architecture Decision Records.

Na toepassing van deze uitsluitingen blijven er **0 echte ADR's** over. Geen enkel bestand
heeft de kenmerkende ADR-structuur (Context/Decision/Consequences), en er bestaat geen
`docs/adr/`-register.

## Conclusie en spec-tak

- **Bevinding: er zijn GEEN ADR's aanwezig in de repo.**
- Toegepaste beslis-tak: *geen ADR's → geldige no-op zonder code- of gedragswijziging.*
- Er is **geen code gewijzigd**; alleen dit worklog is bijgewerkt.
- Geen aanleiding voor de error-tak: die geldt uitsluitend bij aanwezige ADR's met een
  afwijking die enkel via gedrags-/functiewijziging herstelbaar of inhoudelijk onduidelijk is.

## Review-stap

- Diff bevat uitsluitend dit worklog-bestand; geen broncode-, config- of testwijzigingen.
- Functioneel gedrag is daarmee per definitie exact gelijk gebleven.
- Bestaande tests worden niet geraakt (geen code-delta); regressiesignaal blijft ongewijzigd.
  Er zijn dan ook geen nieuwe (unit)tests nodig: er is geen nieuw of gewijzigd gedrag om af te dekken.

## Done / rationale

- Story-log aangemaakt en bijgewerkt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- ADR-zoekactie langs drie sporen uitgevoerd en de bevinding (afwezig), doorzochte locaties en
  uitsluitingen vastgelegd — conform de acceptance criteria.
- Bevestigt de bestaande projectkennis (SF-436/SF-372): de software-factory-repo bevat nog geen
  echte ADR's; ADR-naleving-stories zijn tot die er zijn een no-op met worklog-notitie.
