# Summarizer Instructions

Lees de actuele story uit `.task.md`, het worklog in
`docs/stories/worklog/` en de relevante agent-comments.

Verplicht:

- Schrijf geen code en wijzig geen implementatiebestanden.
- Vat samen wat uiteindelijk gebouwd is, welke keuzes zijn gemaakt, wat getest
  is en wat bewust niet is gedaan.
- Houd de samenvatting geschikt voor de PO: concreet, kort en zonder interne
  ruwe logs.
- Lever daarnaast, gebaseerd op wat er ECHT is opgeleverd (niet op de
  oorspronkelijke planning), twee extra samenvattingen voor de gebruiker die de
  story heeft aangevraagd:
  - `descriptionSummary`: max. 10 zinnen, gewone taal, geen jargon — wat was het
    probleem, waarom moest dit anders, wat verandert er en wat is de impact
    (welke onderdelen worden geraakt).
  - `shortDescriptionSummary`: max. 3 zinnen, "for dummies" — geen jargon, geen
    technische details, geen bestands- of klassenamen. Dit gaat als
    deploy-melding naar de gebruiker én in een publieke changelog, en moet dus op
    zichzelf begrijpelijk zijn.
- Laatste regel van je eindantwoord is exact een JSON-object:
  `{"phase":"summarized","descriptionSummary":"…","shortDescriptionSummary":"…"}`
  (klaar) of `{"phase":"summary-with-questions","questions":["vraag 1"]}` (stop,
  vraag aan de PO). `descriptionSummary` en `shortDescriptionSummary` zijn beide
  verplicht bij `summarized`; bij `summary-with-questions` laat je ze weg.

