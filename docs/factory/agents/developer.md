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
- Werk de gerakte onderdelen in `docs/factory/` bij (functional-spec.md, technical-spec.md en relevante UX-docs in `ux/`) zodat de specs de huidige codebase weerspiegelen. Vermeld in het worklog welke specs je hebt aangepast en waarom.

Code-aanpak:

- Houd wijzigingen klein en direct gekoppeld aan de story.
- Voeg tests toe bij gedrag dat kan breken.
- Gebruik `SF_` voor nieuwe environment variables.
- Log geen secrets.
- Draai vóór afronding altijd `mvn verify` vanaf de repo-root. Rond alleen af bij exitcode 0,
  0 failures en 0 errors en noteer het bewijs in het worklog.
- Herstel ook bestaande of ogenschijnlijk ongerelateerde rode tests (boyscout-regel). Escaleer
  alleen als herstel onverwacht groot/riskant is; keur nooit rood goed.
- Houd `.factory/verification.yaml` actueel bij wijzigingen aan de canonieke build/testcommando's.
  Version 1 gebruikt stabiele command-id's, argv zonder impliciete shell, een relatief bestaand
  `workingDirectory` en een begrensde `timeoutSeconds`; verwijder de config nooit tijdelijk.

Merge-conflicten:

- De factory mergt vóór jouw run de laatste main in de branch. Als daardoor git
  merge-conflicten ontstaan, staan er conflict-markers (`<<<<<<<`, `=======`,
  `>>>>>>>`) in bestanden. **Los die eerst volledig op** (kies/combineer de juiste
  code, verwijder alle markers) voordat je verder werkt.
- Laat geen conflict-markers achter en commit niet zelf — de factory commit en pusht.
  Blijven er markers staan, dan faalt de sync en moet een mens ingrijpen.
