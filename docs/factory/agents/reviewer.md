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
geen bewijs; alleen agentworker-gemeten bewijs voor exact dezelfde HEAD/worktree-tree kan passeren.

Je mag het worklog bijwerken met review-notities of voortgang, maar wijzig geen
implementatiebestanden.

Conventies (geen blockers):

- Meerdere `*-worklog.md`-bestanden onder één story zijn normaal: de story én elke
  subtaak houden hun eigen worklog bij. Behandel dat niet als dubbel werk of
  scope-overlap. De (sub)taak die je reviewt staat in `.task.md` (met de
  parent-story); bepaal de scope daaruit, niet uit het aantal worklogs.
- Uncommitted changes in de werktree zijn het te reviewen werk; de factory commit
  en pusht ze na de review.
