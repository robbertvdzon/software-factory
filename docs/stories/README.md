# Stories

Deze map bevat de implementatie-backlog voor de software-factory. De eerste
10 story-bestanden waren de oorspronkelijke epic-stories; latere `KAN-011+`
stories zijn audit-follow-ups uit een volledige specs-check. Nieuwe
YouTrack-migratie stories gebruiken voorlopige `SF-XXX` keys. Kleinere
werkitems staan als subtaken/checklistregels in de relevante story.

Definitieve story-documenten:

```markdown
docs/stories/<issue-key>-<story-titel-slug>.md
```

Voorbeeld: `docs/stories/SF-244-Merge-altijd-automatisch.md`.

De bestandsnaam wordt automatisch afgeleid van de titel van de **parent-story**
(niet van de summary-subtaak en dus niet het generieke woord `eindsamenvatting`
of `description`). De slug behoudt de oorspronkelijke hoofd-/kleine letters van
de titel, normaliseert diacritica weg (NFD) en scheidt woorden met `-`
(zie `StoryLogWriter.storySlug()`). Voorbeeld: titel
`Énorme Refactor Van De Café-Module` → `…-Enorme-Refactor-Van-De-Cafe-Module.md`.

Oudere bestanden gebruikten voorlopige keys en generieke namen
(`*-eindsamenvatting.md`, `*-description.md`); die zijn eenmalig hernoemd naar
`<issue-key>-<story-titel-slug>.md` met `git mv` (historie behouden). Gebruik in
de bestandsnaam altijd een echte omschrijving van het werk, niet het generieke
woord `description` of `eindsamenvatting`.

Tijdens uitvoering gebruikt de factory een apart worklog:

```markdown
docs/stories/worklog/<issue-key>-worklog.md
```

Daarin mogen agents checklist, plan en tussentijdse notities bijhouden. Na een
succesvolle tester-run schrijft de summarizer het definitieve story-document in
`docs/stories/` met alleen de actuele YouTrack-story en de eindsamenvatting.

Checklist-notatie in worklogs:

```markdown
[ ]: planned step
[x]: completed step
```

Onder de checklist staat wat er precies gedaan is en waarom.
