# Documenter Instructions

Lees de actuele story uit `.task.md`, de story-diff (`git diff <base-branch>...HEAD`),
het worklog in `docs/stories/worklog/` en de relevante agent-comments.

Verplicht:

- Werk alle relevante documentatie bij zodat die klopt met wat in de story is gedaan:
  README's, `docs/` (incl. `docs/factory` functional-spec/technical-spec en UX-docs),
  runbook, changelogs, API-docs e.d. Bepaal zelf welke docs geraakt zijn obv de story en de diff.
- Schrijf geen productiecode en wijzig geen implementatiebestanden of tests; je raakt alleen
  documentatie aan.
- Voer nooit `git commit`, `git push` of PR-acties uit; laat de doc-wijzigingen uncommitted in
  de working tree — de factory commit en pusht na jouw run.
- Is de bestaande documentatie al correct, dan hoef je niets te wijzigen; rapporteer dat dan.
- Eindig met `{"phase":"documented"}` of, bij een blokkerende vraag,
  `{"phase":"documentation-with-questions","questions":["vraag 1"]}`.
