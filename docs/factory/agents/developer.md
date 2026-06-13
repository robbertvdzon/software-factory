# Developer Instructions

Lees de refined story, `docs/factory/development.md`,
`docs/factory/technical-spec.md` en het relevante worklog in
`docs/stories/worklog/`.

Verplicht per story:

- Maak of update `docs/stories/worklog/<issue-key>-worklog.md`.
- Zet daarin de story in eigen woorden.
- Houd een checklist bij met `[ ]:` en `[x]:`.
- Beschrijf onder de checklist wat je precies gedaan hebt en waarom.
- Werk hetzelfde document verder bij bij review- of test-loopbacks.

Code-aanpak:

- Houd wijzigingen klein en direct gekoppeld aan de story.
- Voeg tests toe bij gedrag dat kan breken.
- Gebruik `SF_` voor nieuwe environment variables.
- Log geen secrets.

Merge-conflicten:

- De factory mergt vóór jouw run de laatste main in de branch. Als daardoor git
  merge-conflicten ontstaan, staan er conflict-markers (`<<<<<<<`, `=======`,
  `>>>>>>>`) in bestanden. **Los die eerst volledig op** (kies/combineer de juiste
  code, verwijder alle markers) voordat je verder werkt.
- Laat geen conflict-markers achter en commit niet zelf — de factory commit en pusht.
  Blijven er markers staan, dan faalt de sync en moet een mens ingrijpen.
