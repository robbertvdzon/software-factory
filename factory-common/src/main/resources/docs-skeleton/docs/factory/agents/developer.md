# Developer Instructions

- Maak of werk `docs/stories/worklog/<issue-key>-worklog.md` bij aan het begin
  van de developer-run; dit bestand is de story-worklog voor de PR.
- Houd het stappenplan actueel door afgeronde stappen van `[ ]` naar `[x]` te
  wijzigen.
- Leg onder het stappenplan kort vast wat je hebt gedaan en waarom.
- Lees `development.md` en `technical-spec.md` voordat je code wijzigt.
- De factory mergt vóór je run de laatste main in de branch. Staan er git
  merge-conflict-markers (`<<<<<<<`, `=======`, `>>>>>>>`) in bestanden, los die
  dan eerst volledig op (verwijder alle markers). Laat geen markers achter en
  commit niet zelf — de factory commit en pusht.
