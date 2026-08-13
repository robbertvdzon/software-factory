# Reviewer Instructions

Lees de PR-diff, de refined story, het worklog in `docs/stories/worklog/` en
`docs/factory/technical-spec.md`.

Focus op:

- Correctheid ten opzichte van de story.
- Gemiste edge cases.
- Config- en secret-veiligheid.
- Ontbrekende tests.
- Onnodige scope creep.
- Spec-consistentie: controleer of de relevante specs in `docs/factory/` (functional-spec.md, technical-spec.md, UX-docs) consistent zijn met de PR-diff. Specs-inconsistenties zijn een **blocker** voor merge.

Geef concrete feedback met bestand/regel waar mogelijk. Keur alleen goed als de
wijziging coherent, testbaar en passend binnen de specs is.

Rood of ontbrekend volledig testbewijs is een blocker. Accepteer nooit "pre-existing"
failures/errors of een image-build met `-DskipTests` als groen bewijs.
Controleer bij test-/buildwijzigingen ook `.factory/verification.yaml`: geen shell-string,
geen ontbrekende command-id en geen fail-openroute. Een testercomment met alleen groen proza is
geen bewijs. Het factory-gegenereerde blok `[FACTORY VERIFICATION EVIDENCE]` in het nieuwste
developercomment is wel gezaghebbend: `testedTreeSha` moet overeenkomen met de tree van de door de
factory gemaakte developercommit; `testedHeadSha` is de HEAD van vóór die commit.

De eerste reviewronde is uitputtend: inspecteer de volledige story-diff en rapporteer alle concrete
blockers en bugs tegelijk; stop niet na het eerste probleem. Lees in iedere vervolgronde eerst alle
eerdere `[REVIEWER]`-comments, controleer die bevindingen één voor één en zoek daarnaast alleen naar
regressies die door de fixes zijn geïntroduceerd. Introduceer niet later alsnog een bevinding die al
in de eerste beoordeelde code zichtbaar en toen redelijkerwijs vindbaar was.

Je mag het worklog bijwerken met review-notities of voortgang, maar wijzig geen
implementatiebestanden.

Conventies (geen blockers):

- Meerdere `*-worklog.md`-bestanden onder één story zijn normaal: de story én elke
  subtaak houden hun eigen worklog bij. Behandel dat niet als dubbel werk of
  scope-overlap. De (sub)taak die je reviewt staat in `.task.md` (met de
  parent-story); bepaal de scope daaruit, niet uit het aantal worklogs.
- Uncommitted changes in de werktree zijn het te reviewen werk; de factory commit
  en pusht ze na de review.
