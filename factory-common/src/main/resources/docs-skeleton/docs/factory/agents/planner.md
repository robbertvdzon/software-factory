# Planner Instructions

Lees de gerefinede story uit `.task.md`, relevante user-comments en
`docs/factory/technical-spec.md`.

Verplicht:

- Schrijf geen code. Maak een implementatieplan in de story-body en **declareer**
  de subtaken in de JSON-output — de factory maakt ze aan, jij niet.
- Beschrijf de aanpak op gedragsniveau; benoem geraakte modules en risico's.
- Subtask-types: `development` / `review` / `test` / `manual` / `summary`. De
  factory voegt zelf altijd `documentation`, `manual-approve` (indien geconfigureerd),
  `merge` en `deploy` toe; declareer die niet.
- Het schrijven van (unit)tests is ontwikkelwerk en hoort bij de
  `development`-subtaak. Maak nooit een `test`-subtaak om tests te schrijven; de
  `test`-subtaak is uitsluitend voor de tester, die alleen verifieert.
- Houd het aantal subtaken minimaal. De standaard is precies drie: één
  `development` (al het ontwikkelwerk samen), één story-brede `test` en één
  `summary`. Splits alleen op bij aantoonbaar complexe stories, en maak geen
  aparte `review`-subtaak tenzij de gebruiker daar expliciet om vraagt.
- De proza en de JSON moeten exact overeenkomen: zelfde aantal, types en opdeling.
- Stel alleen blokkerende vragen als het plan niet te maken is zonder antwoord.
- Eindig met exact één JSON-object, bijvoorbeeld
  `{"phase":"planned","subtasks":[{"type":"development","title":"...","description":"..."},{"type":"test","title":"Story-brede test"},{"type":"summary","title":"Eindsamenvatting"}]}`
  of, bij een blokkerende vraag,
  `{"phase":"planned-with-questions","questions":["vraag 1"]}`.
