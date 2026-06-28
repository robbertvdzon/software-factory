# SF-436 - Worklog

Story-context bij eerste pickup:
ADR-naleving verifiëren en bevinding vastleggen

Voer een aantoonbare ADR-zoekactie uit: controleer docs/adr/, doe een repo-brede zoek op bestandsnaam (find -iname '*adr*') én op decision-record-structuur in *.md (headings als ADR/Status/Context/Decision/Consequences). Sluit .factory/nightly/adr/ en .task.md expliciet uit als ADR-bron en benoem waarom de treffers (nightly-jobdefinitie en SF-372 story/worklog) geen echte ADR's zijn. Beslis volgens de spec-takken: (a) geen ADR's -> no-op, geen code-/gedragswijziging; (b) ADR's aanwezig en gevolgd -> geen wijziging; (c) ADR's aanwezig met veilig, puur structureel/conventioneel herstelbare afwijking (geen observeerbaar gedrag) -> code in lijn brengen en per ADR kort vastleggen wat is aangepast; (d) ADR's aanwezig met afwijking die alleen via gedrags-/functiewijziging herstelbaar of inhoudelijk onduidelijk is -> geen codewijziging, story in error met benoeming van ADR en afwijking (silent-nightly, niet wachten op interactie). Leg in docs/stories/worklog/SF-436-worklog.md de doorzochte locaties, treffers, uitsluitingen en conclusie vast en vink het stappenplan af. Verwachte uitkomst op huidige repo-staat: no-op met worklog-notitie. Voer zelf de review-stap uit; functioneel gedrag moet exact gelijk blijven.

## Story in eigen woorden (SF-443)

Toets aantoonbaar of de codebase de vastgelegde Architecture Decision Records (ADR's)
nog volgt. Herstel afwijkingen alleen als dat puur structureel/conventioneel kan, zonder
enige observeerbare gedragswijziging. Zijn er geen ADR's, dan is dit een geldige no-op:
geen code- of gedragswijziging, alleen de bevinding (inclusief doorzochte locaties) in dit
worklog vastleggen. Bij een afwijking die alleen via gedrags-/functiewijziging te herstellen
is, of die onduidelijk is: géén code aanpassen en de story in error zetten (silent-nightly).

## Stappenplan

[x]: read issue and target docs
[x]: ADR-zoekactie uitvoeren (docs/adr/ + repo-breed op bestandsnaam + decision-record-structuur)
[x]: treffers beoordelen en uitsluitingen onderbouwen
[x]: spec-tak bepalen en bevinding vastleggen
[x]: review-stap (functioneel gedrag exact gelijk — geen codewijziging)
[x]: update story-log with results

## ADR-zoekactie (aantoonbaar)

Uitgevoerd op repo-staat van branch `ai/SF-436` (2026-06-28).

### 1. Gangbare ADR-locatie

- `docs/adr/` → **bestaat niet** (`ls docs/adr/` → not found).

### 2. Repo-brede zoek op bestandsnaam

Commando: `find . -path ./.git -prune -o -iname '*adr*' -print`

Treffers:
- `./docs/stories/SF-372-nightly-ADR-naleving-herstellen.md`
- `./.factory/nightly/adr` (directory)

### 3. Repo-brede zoek op decision-record-structuur in `*.md`

Commando: `grep -rilE '(architecture decision record|decision record|^#+\s*ADR|^\s*##?\s*(Status|Context|Decision|Consequences)\s*$)' --include='*.md' .`

Treffers:
- `.task.md`
- `docs/stories/SF-372-nightly-ADR-naleving-herstellen.md`
- `docs/stories/worklog/SF-372-worklog.md`
- `.factory/nightly/adr/story.md`

Aanvullende controle op klassieke ADR-structuur (een `*.md` met tegelijk `Context` + `Decision`
+ `Consequences`-headings): **0 treffers**.

## Beoordeling van de treffers

| Treffer | Echte ADR? | Reden |
|---|---|---|
| `.factory/nightly/adr/` (`job.yaml`, `story.md`) | Nee — **uitgesloten per spec** | Dit is de nightly-jobdefinitie van déze story (de opdracht om ADR-naleving te checken), geen besluitregister. `story.md` begint met `# ADR-naleving herstellen` en beschrijft de taak, niet een architectuurbesluit. |
| `.task.md` | Nee — **uitgesloten per spec** | De taakomschrijving van deze run; matcht alleen omdat ze de woorden "ADR/decision/Context/Decision" bevat. |
| `docs/stories/SF-372-nightly-ADR-naleving-herstellen.md` | Nee | Een eerdere **story** (issue-document) óver ADR-naleving, geen ADR. Bevat geen besluit met Status/Context/Decision/Consequences-structuur; de woorden "Architecture Decision Record"/"decision record" staan in de scope-tekst die naar (niet-bestaande) ADR's verwijst. |
| `docs/stories/worklog/SF-372-worklog.md` | Nee | Worklog van diezelfde eerdere story; documenteert dezelfde no-op-bevinding (geen ADR's aanwezig). Geen besluitregister. |

Conclusie van de beoordeling: na uitsluiting van `.factory/nightly/adr/` en `.task.md` blijven er
geen treffers over die een echt Architecture Decision Record zijn. De resterende treffers (SF-372
story + worklog) zijn projecthistorie óver ADR-naleving, geen ADR's om tegen te toetsen.

## Conclusie

**Spec-tak (a): er zijn geen ADR's aanwezig in de repo.** Daarmee is dit een geldige no-op:

- Geen code- of gedragswijziging doorgevoerd.
- Niets om tegen te toetsen, dus geen afwijking en geen reden voor error.
- Story wordt succesvol afgerond; enige output is deze worklog-notitie.

Mocht er later wél een `docs/adr/` (of vergelijkbaar besluitregister) worden toegevoegd, dan
volgt een toekomstige run alsnog takken (b)/(c)/(d) volgens de spec.

## Done / rationale

- Aantoonbare ADR-zoekactie uitgevoerd op drie sporen (vaste locatie, bestandsnaam, inhoudstructuur)
  en hierboven met commando's + treffers vastgelegd.
- Treffers beoordeeld; `.factory/nightly/adr/` en `.task.md` per spec uitgesloten, SF-372 story/worklog
  onderbouwd als géén echte ADR's.
- Bevinding: geen ADR's aanwezig → no-op. **Geen enkel codebestand aangepast**, dus functioneel gedrag
  is per definitie exact gelijk gebleven en bestaande tests blijven onveranderd. Er is daarom geen
  nieuw testsignaal nodig of zinvol voor deze story (geen productiecode geraakt); het bestaande
  `mvn test`-signaal blijft ongewijzigd.
- Geen specs in `docs/factory/` geraakt: er is geen gedrag of conventie gewijzigd, dus de specs
  weerspiegelen de codebase nog steeds correct.
