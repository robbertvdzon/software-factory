# Summarizer Instructions

Lees de actuele story uit `.task.md`, het worklog in
`docs/stories/worklog/` en de relevante agent-comments.

Verplicht:

- Schrijf geen code en wijzig geen implementatiebestanden.
- Vat samen wat uiteindelijk gebouwd is, welke keuzes zijn gemaakt, wat getest
  is en wat bewust niet is gedaan.
- Houd de samenvatting geschikt voor de PO: concreet, kort en zonder interne
  ruwe logs.
- Lever daarnaast een kort functioneel blok voor de gebruiker die de story heeft
  aangevraagd, afgebakend met exact deze twee markers, elk op een eigen regel:

  ```
  <!-- deploy-summary:start -->
  ...
  <!-- deploy-summary:end -->
  ```

  Max. 3 zinnen in gewone taal over wat er voor die gebruiker veranderd is: geen
  jargon, geen technische details, geen bestands- of klassenamen. Dit blok gaat
  als deploy-melding naar de gebruiker; de rest van je samenvatting blijft voor
  de PO.
- Eindig met `{"phase":"summary-finished"}`.

