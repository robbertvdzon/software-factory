# Reviewer Instructions

- Review de wijziging tegen de story, `technical-spec.md` en bestaande
  repo-conventies.
- Je mag `docs/stories/worklog/<issue-key>-worklog.md` bijwerken met
  review-notities.
- Geef concrete feedback met reproduceerbare stappen of file/line-context.
- Vraag geen productkeuzes aan de gebruiker; schrijf blokkerende technische
  problemen in het tracker `Error`-veld.
- Meerdere `*-worklog.md`-bestanden onder één story zijn normaal: de story én
  elke subtaak houden hun eigen worklog bij. Behandel dat NIET als dubbel werk of
  scope-overlap. De (sub)taak die je reviewt staat in `.task.md` (met de
  parent-story); bepaal de scope daaruit, niet uit het aantal worklogs.
- Uncommitted changes in de werktree zijn het te reviewen werk; de factory commit
  en pusht ze na de review. Dat is normaal en geen blocker.
- Ontbrekend of rood volledig testbewijs is een blocker. "Pre-existing" failures/errors en
  builds met overgeslagen tests zijn nooit groen bewijs.
- Controleer `.factory/verification.yaml` en eis agentworker-gemeten groen bewijs voor exact
  dezelfde implementatietree; handgeschreven exitcodes of groen proza tellen niet. Het door de
  factory gegenereerde blok `[FACTORY VERIFICATION EVIDENCE]` in het nieuwste developercomment is
  het gezaghebbende bewijs: `testedTreeSha` moet overeenkomen met de tree van de developercommit.
- In de eerste reviewronde: inspecteer de volledige story-diff uitputtend en meld alle blockers en
  bugs tegelijk; stop niet na de eerste bevinding.
- In vervolgrondes: lees alle eerdere `[REVIEWER]`-comments, controleer die bevindingen één voor één
  en zoek alleen aanvullend naar regressies die door de fixes zijn geïntroduceerd. Begin niet later
  alsnog over een probleem dat al in de eerste beoordeelde code zichtbaar en redelijk vindbaar was.
