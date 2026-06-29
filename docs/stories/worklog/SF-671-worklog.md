# SF-671 - Worklog

Story-context bij eerste pickup:
ADR-naleving controleren en bevinding vastleggen

Zoek aantoonbaar naar ADR's langs meerdere sporen: vaste locatie docs/adr/, repo-brede zoekactie op bestandsnaam (*adr*) en op decision-record-structuur (Context/Decision/Consequences-headings in *.md). Hanteer de uitsluitingen: .factory/nightly/adr/ (jobdefinitie van deze story), .task.md en de ADR-naleving-story-/worklog-bestanden in docs/stories/. Beslisboom: (a) geen ADR's -> geen code-/gedragswijziging, bevinding+doorzochte locaties+uitsluitingen vastleggen in docs/stories/worklog/SF-671-worklog.md en story succesvol afronden; (b) ADR's aanwezig en gevolgd -> geen wijziging, bevinding in worklog; (c) ADR's aanwezig met veilig puur structureel/conventioneel herstelbare afwijking (geen observeerbaar gedrag, geen API-/testverandering) -> code in lijn brengen en per ADR kort vastleggen; (d) ADR's met afwijking die alleen via gedrags-/functiewijziging herstelbaar of onduidelijk is -> geen codewijziging, story in error met ADR en afwijking benoemd (silent-nightly). Verwachte uitkomst op huidige repo: no-op met worklog-notitie (docs/adr/ ontbreekt; enige *adr*-treffers zijn de jobdefinitie en eerdere stories SF-372/436/503/587). Functioneel gedrag blijft exact gelijk.

## Story in eigen woorden

Controleer of de codebase nog voldoet aan de vastgelegde Architecture Decision
Records (ADR's). Zoek langs meerdere sporen naar ADR's, sluit de bekende
niet-ADR-bronnen uit en handel de beslisboom af. In deze repo wordt geen
echt besluitregister verwacht, dus de uitkomst is naar verwachting een no-op
met enkel een worklog-notitie. Géén gedragswijziging; alle bestaande tests
moeten onveranderd blijven slagen.

## Checklist

[x]: read issue and target docs
[x]: zoek naar ADR's langs drie sporen (docs/adr/, bestandsnaam *adr*, decision-record-structuur)
[x]: pas uitsluitingen toe (.factory/nightly/adr/, .task.md, ADR-naleving-stories/worklogs)
[x]: beslisboom afhandelen -> tak (a) geen ADR's -> no-op
[x]: bevinding + doorzochte locaties + uitsluitingen vastleggen in dit worklog
[x]: bevestigen dat er geen code-/gedragswijziging nodig is

## ADR-zoekactie (bewijs)

Uitgevoerd op de checkout van branch `ai/SF-671` (2026-06-29):

1. **Vaste locatie `docs/adr/`** — `ls docs/adr/` → bestaat niet
   (`No such file or directory`). Geen ADR-map aanwezig.

2. **Repo-brede zoekactie op bestandsnaam (`*adr*`, case-insensitive)** —
   `find . -iname '*adr*'` (excl. `.git`) levert uitsluitend:
   - `./.factory/nightly/adr/` (job.yaml + story.md) — **uitgesloten**: nightly-jobdefinitie van déze story.
   - `./docs/stories/SF-372-nightly-ADR-naleving-herstellen.md` — **uitgesloten**: projecthistorie.
   - `./docs/stories/SF-436-nightly-ADR-naleving-herstellen.md` — **uitgesloten**: projecthistorie.
   - `./docs/stories/SF-503-nightly-ADR-naleving-herstellen.md` — **uitgesloten**: projecthistorie.
   - `./docs/stories/SF-587-nightly-ADR-naleving-herstellen.md` — **uitgesloten**: projecthistorie.
   Geen treffers buiten de uitgesloten bronnen.

3. **Zoekactie op decision-record-structuur in `*.md`** —
   - `grep -rliE '^#{1,6}\s*(context|decision|consequences)\b' --include='*.md'`
     (excl. `.git`) → **0 treffers**. Geen enkel markdown-bestand heeft de
     karakteristieke ADR-koppenstructuur (Context/Decision/Consequences).
   - Aanvullend `grep -rliE 'decision record' --include='*.md'` levert alleen
     de al uitgesloten bronnen op: `.task.md` (taakomschrijving — uitgesloten),
     de vier `SF-*-nightly-ADR-naleving-herstellen.md` stories, hun worklogs
     (`SF-372/436/503/587-worklog.md`) en `.factory/nightly/adr/story.md`.
     Allemaal expliciet uitgesloten ADR-naleving-historie/jobdefinitie, geen
     besluitregister.

### Gehanteerde uitsluitingen
- `.factory/nightly/adr/` — nightly-jobdefinitie van deze story.
- `.task.md` — de taakomschrijving zelf.
- `docs/stories/SF-372|436|503|587-*.md` en bijbehorende
  `docs/stories/worklog/SF-*-worklog.md` — projecthistorie óver ADR-naleving,
  geen besluitregister.

## Bevinding

**Geen ADR's aanwezig** in de repository. Na uitsluiting van de bekende
niet-ADR-bronnen blijven er langs alle drie de sporen nul echte ADR's over.
Dit komt overeen met de aanname in de story en met de eerdere runs
(SF-372/436/503/587).

→ Beslisboom **tak (a)**: geen ADR's → **geen code- of gedragswijziging**.
Story wordt als geldige no-op succesvol afgerond; alleen dit worklog is
bijgewerkt.

## Done / rationale

- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- Drie-sporen ADR-zoekactie uitgevoerd en het bewijs (commando's + resultaten)
  hierboven vastgelegd, inclusief de doorzochte locaties en de gehanteerde
  uitsluitingen — conform de acceptance criteria.
- Geen ADR's gevonden buiten de uitgesloten bronnen → no-op. Bewust **geen**
  code-, API- of gedragswijziging aangebracht, zodat functioneel gedrag exact
  gelijk blijft en bestaande tests onveranderd slagen.
- Geen `docs/factory/`-specs aangepast: er is geen functionele of technische
  wijziging die de specs raakt (de afwezigheid van een besluitregister is al
  bekend en vergt geen spec-update).
- Tests: er is geen productiecode gewijzigd, dus er is geen nieuw testgedrag om
  af te dekken. Het story-brede regressiesignaal
  (`mvn -f softwarefactory/pom.xml test`) wordt in de aparte test-subtaak
  (SF-679) gedraaid.
