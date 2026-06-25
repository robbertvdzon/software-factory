# Functional Spec

De software-factory automatiseert YouTrack-issues via een lokale agent-pijplijn:

1. Refiner scherpt een story aan of stelt vragen.
2. Developer implementeert de story en houdt een worklog bij.
3. Reviewer beoordeelt de PR.
4. Tester test de preview-deploy.
5. Summarizer maakt na een succesvolle test de eindsamenvatting.

De orchestrator:

- Pollt YouTrack-issues met `Stage = Develop` en `AI-supplier` niet leeg/niet `none`.
- Stuurt op `AI-supplier`, `AI Phase`, `Paused` en `Error`.
- Start agent-runs in Docker-containers.
- Houdt run-state en tokengebruik bij in Postgres.
- Ondersteunt budget-pauzes, credit-pauzes en handmatige comment-commands.

`AI-supplier=mock` gebruikt dummy agents zodat de workflow end-to-end kan
werken zonder echte AI CLI. `AI-supplier=claude` gebruikt Claude Code.

## Handmatige goedkeur-poort (SF-192)

Vlak vóór de merge zit een vaste, niet-AI subtaak `manual-approve`: een handmatige
goedkeur-poort. Die staat per project default AAN en is uit te zetten met
`manualApprove: false` in `projects.yaml`.

- De poort wordt bij het materialiseren van het plan precies één keer aangemaakt, ná de
  laatste AI-subtaak (summary) en vóór de merge-subtaak.
- Op de poort wacht de keten op een mens. Goedkeuren/afkeuren loopt via het bestaande
  `@factory:command`-mechanisme (dashboard-knoppen én Telegram): `approve` laat de keten door
  naar de merge, `reject` neemt een afkeurreden mee.
- Afkeuren reset de hele story: alle subtaken terug naar todo, de eerste subtaak weer op
  `start`, en de afkeurreden in een gemarkeerd blok in de story-description zodat
  developer/reviewer/tester de feedback meekrijgen.
- De poort vraagt altijd om een mens, óók als `Auto-approve=on` staat.
