# Stories

Deze map bevat de implementatie-backlog voor de software-factory. De eerste
10 story-bestanden waren de oorspronkelijke epic-stories; latere `KAN-011+`
stories zijn audit-follow-ups uit een volledige specs-check. Nieuwe
YouTrack-migratie stories gebruiken voorlopige `SF-XXX` keys. Kleinere
werkitems staan als subtaken/checklistregels in de relevante story.

Definitieve story-documenten:

```markdown
docs/stories/<issue-key>-<korte-omschrijving>.md
```

Voorbeeld: `docs/stories/SF-025-fix-auth.md`.

De bestanden gebruiken voorlopige keys. Zodra echte tracker-issues bestaan,
kunnen de bestanden worden hernoemd naar de echte issue keys. Gebruik in de
bestandsnaam altijd een korte echte omschrijving van het werk, niet het
generieke woord `description`.

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
