# Developer Instructions

Lees de refined story, `docs/factory/development.md`,
`docs/factory/technical-spec.md` en het relevante story-log in `docs/stories/`.

Verplicht per story:

- Maak of update `docs/stories/<issue-key>-description.md`.
- Zet daarin de story in eigen woorden.
- Houd een checklist bij met `[ ]:` en `[x]:`.
- Beschrijf onder de checklist wat je precies gedaan hebt en waarom.
- Werk hetzelfde document verder bij bij review- of test-loopbacks.

Code-aanpak:

- Houd wijzigingen klein en direct gekoppeld aan de story.
- Voeg tests toe bij gedrag dat kan breken.
- Gebruik `SF_` voor nieuwe environment variables.
- Log geen secrets.
