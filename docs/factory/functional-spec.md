# Functional Spec

De software-factory automatiseert YouTrack-issues via een lokale agent-pijplijn:

1. Refiner scherpt een story aan of stelt vragen.
2. Developer implementeert de story en houdt een worklog bij.
3. Reviewer beoordeelt de PR.
4. Tester test de preview-deploy.
5. Summarizer maakt na een succesvolle test de eindsamenvatting.
6. Documenter werkt de relevante documentatie bij obv de story.

De orchestrator:

- Pollt YouTrack-issues met `Stage = Develop` en `AI-supplier` niet leeg/niet `none`.
- Stuurt op `AI-supplier`, `AI Phase`, `Paused` en `Error`.
- Start agent-runs in Docker-containers.
- Houdt run-state en tokengebruik bij in Postgres.
- Ondersteunt budget-pauzes, credit-pauzes en handmatige comment-commands.

`AI-supplier=mock` gebruikt dummy agents zodat de workflow end-to-end kan
werken zonder echte AI CLI. `AI-supplier=claude` gebruikt Claude Code.

## Silent — autonoom verwerken (SF-335)

Op story-niveau bestaat een enum-boolean veld `Silent` (default `false`, gemodelleerd analoog aan
`Paused`: waarden `false`/`true`). Bij `Silent=true` wordt de story volledig autonoom verwerkt,
bedoeld voor nachtelijke "improve"-stories (documentatie, test-coverage, code-kwaliteit, security)
die functioneel niets aanpassen en autonoom afgemaakt mogen worden zolang alle tests slagen.

Effect van `Silent=true` (subtaken erven de waarde van de parent-story via parent-lookup, net als
`Auto-approve`; ze hebben geen eigen `Silent`-veld nodig):

- **Silent impliceert auto-approve.** Alle fases worden automatisch doorgezet; de auto-approve-conditie
  evalueert als `(Auto-approve || Silent)`.
- **Geen handmatige goedkeur-poort.** Bij een silent parent-story wordt de `manual-approve`-subtaak niet
  aangemaakt; de merge- en deploy-subtaken blijven onveranderd bestaan.
- **Onduidelijkheden → error i.p.v. wachten.** Elke `*-with-questions`-uitkomst (story:
  `refined`/`planned`; subtaak: `developed`/`reviewed`/`tested`/`summary`/`documentation`) zet de
  story/subtaak in `Error` met de vragen als error-tekst, in plaats van te wachten op een mens.
- **Error-categorisatie.** Zo'n uit vragen voortkomende fout is in de error-tekst gemarkeerd als
  `[CLARIFICATION]` (niet-retrybaar), onderscheidbaar van een technische fout. Verdere afhandeling
  (retry/digest/monitor) valt buiten deze story.
- **Nul Telegram.** Voor een silent story (en subtaken met een silent parent) gaat er geen enkel
  Telegram-bericht uit, inclusief de error-melding.

Niet-silent stories/subtaken behouden in alle paden hun bestaande gedrag (backwards compatible).
Buiten scope (latere stories): de nachtelijke trigger die improve-stories start, de ochtend-digest en
een monitor-agent die technische errors retryt/fixt.

## Documentatie-stap (SF-213)

Elke story krijgt een vaste, factory-afgedwongen subtaak `documentation` (titel
"Werk documentatie bij"), uitgevoerd door de AI-rol DOCUMENTER. De subtaak wordt bij het
materialiseren van het plan automatisch aangemaakt, ná de planner-subtaken (dus ná `summary`)
en vóór de manual-approve-poort. De ketenvolgorde wordt daarmee:
`development → review → test → summary → documentation → manual-approve → merge → deploy`.

- De documenter werkt alle relevante documentatie bij (README's, `docs/`, runbook/changelogs,
  API-docs e.d.) zodat die klopt met wat in de story is gedaan, en bepaalt zelf welke docs geraakt
  zijn obv de story en de diff. Er is geen vaste lijst van te wijzigen bestanden.
- De levenscyclus spiegelt die van `summary`/`test`:
  `documenting → documented → (documentation-with-questions ↔ documentation-questions-answered) →
  documentation-approved`, met `documentation-approved` als terminale fase. Er is géén
  `documentation-rejected`/loopback-tak.
- Bij `Auto-approve=on` loopt de subtaak vanzelf door (zoals review/test/summary); zonder
  auto-approve vraagt 'ie — net als de andere AI-stappen — om goedkeuring vóór doorgaan.
- De subtaak is altijd aan (niet per project uit te zetten, anders dan de manual-approve-poort).
  Een eventueel door de planner meegestuurde `documentation`-spec wordt eruit gefilterd, zodat er
  nooit een dubbele documentatie-subtaak ontstaat (zelfde patroon als merge/deploy).

## Handmatige goedkeur-poort (SF-192)

Vlak vóór de merge zit een vaste, niet-AI subtaak `manual-approve`: een handmatige
goedkeur-poort. Die staat per project default AAN en is uit te zetten met
`manualApprove: false` in `projects.yaml`.

- De poort wordt bij het materialiseren van het plan precies één keer aangemaakt, ná de
  documentatie-stap (SF-213) en vóór de merge-subtaak.
- Op de poort wacht de keten op een mens. Goedkeuren/afkeuren loopt via het bestaande
  `@factory:command`-mechanisme (dashboard-knoppen én Telegram): `approve` laat de keten door
  naar de merge, `reject` neemt een afkeurreden mee.
- Afkeuren reset de hele story: alle subtaken terug naar todo, de eerste subtaak weer op
  `start`, en de afkeurreden in een gemarkeerd blok in de story-description zodat
  developer/reviewer/tester de feedback meekrijgen.
- De poort vraagt altijd om een mens, óók als `Auto-approve=on` staat.

## Merge altijd automatisch (SF-244)

Na de goedkeur-poort merget de `merge`-subtaak altijd automatisch: zodra hij aan de beurt is
(fase START) merget de factory de PR zelf via de GitHub API. Er is geen configureerbare
handmatige merge-poort en geen `merge.mode` in `projects.yaml` meer.

- Lukt de merge, dan gaat de keten ongewijzigd door naar de `deploy`-subtaak.
- Een merge-conflict of GitHub-fout zet de merge-subtaak op `Error` en stopt de keten
  (handmatige triage); de subtaak komt niet meer op `AWAITING_HUMAN`.
- De handmatige controle vóór de merge zit volledig in de voorafgaande `manual-approve`-poort.

## Test-bevinding reset de keten (SF-200)

De test-subtaak test alleen en oordeelt; de tester voert zelf geen gerichte fix meer uit.

- Bij een bevinding (`test-rejected`) start de tester géén developer-loopback. In plaats daarvan
  wordt de hele subtaak-keten gereset op exact dezelfde manier als bij een handmatige reject via de
  goedkeur-poort: alle subtaken terug naar todo, de eerste subtaak weer op `start`, op dezelfde
  story-branch.
- De testreden van de laatste tester-run komt in een eigen, herhaalbaar te overschrijven gemarkeerd
  blok (`<!-- test-feedback:start -->`) in de story-description, zodat developer/reviewer/tester die
  feedback bij de herstart meekrijgen. Een volgende bevinding vervangt het blok (stapelt niet).
- Een cap (`SF_MAX_TEST_CHAIN_RESETS`, default 3) voorkomt oneindig herstarten. Zolang de cap niet
  bereikt is, reset een bevinding de keten opnieuw. Bij het bereiken van de cap volgt geen reset maar
  komt de story in `Error` (handmatige triage nodig). De cap telt de TESTER-runs op de gedeelde
  story-run en kent — anders dan de developer-loopback-cap — géén resume-increment: enkel `Error`
  legen herstart niets (de volgende poll loopt direct opnieuw in de cap). Werkende herstelpaden zijn
  `Paused = true` + parkeren, of `re-implement` op de story (verse story-run → teller reset).

## Telegram-melding bij afgeronde test-subtaak (SF-206)

Wanneer een **test**-subtaak terminaal wordt bij actieve `Auto-approve`, breidt de bestaande
'subtaak klaar'-Telegram-melding (`TelegramNotificationService.notifySubtaskDone`) zich uit met
test-specifieke context. Voor alle andere subtaaktypen blijft de melding ongewijzigd.

- **Testrapport** — de samenvatting van de laatste TESTER-agent-run op de parent-story
  (`FactoryDashboardService.testerReportFor`), afgekapt op ~1200 tekens.
- **Preview-/test-URL** — voor projecten mét preview (`previewUrlTemplate` gezet, zoals News Feed)
  staat de preview-link (dezelfde als de 'Test op preview'-knop, via
  `FactoryDashboardService.previewUrlFor`) als klikbare regel in het bericht; projecten zonder
  preview (bv. softwarefactory zelf) laten die regel weg.
- **Screenshots** — de tester-screenshots (YouTrack-attachments met prefix
  `factory-tester-screenshot__` op de parent-story) worden als foto's in hetzelfde projectkanaal
  verstuurd via `TelegramClient.sendPhoto`. Maximaal 10 als foto; de rest komt als link(s) in de
  tekst.
- **Volgorde & idempotentie** — eerst de tekstmelding (met rapport + preview-link), dan wordt de
  bestaande `TelegramStore`-signature vastgelegd, daarna pas de foto's. Zo triggert een gefaalde
  `sendPhoto` (return false) geen herverzending van de tekstmelding.
- **Robuust degraderen** — een ontbrekend rapport, een ontbrekende preview-URL of een gefaalde
  screenshot-download blokkeert de rest niet; tracker-calls, attachment-download en `sendPhoto`
  zitten in `runCatching`/return-false.

## Nightly scheduler — nachtelijke jobs automatisch draaien (SF-350)

Naast de handmatige Nightly-knop draait de factory de per-project gedeclareerde nachtelijke jobs
(`.factory/nightly/<job>/job.yaml`) ook automatisch, instelbaar op `/settings`.

- **Instellingen** (`/settings` → Nightly scheduler): een master-switch `enabled`, een `start_time`
  en een `summary_time` (beide `HH:MM`, lokale NL-tijd). Persistent in `nightly_settings`.
- **Automatische run** — staat de master-switch aan en is de (naar UTC omgerekende) start-tijd
  bereikt, dan maakt de scheduler precies één run per kalenderdag aan met per project een queue van
  de jobs die zowel `enabled:true` in job.yaml hebben als onder de master-switch vallen. Projecten
  draaien parallel; binnen een project draaien jobs strikt sequentieel. Stories worden exact als de
  Nightly-knop aangemaakt (silent=true, start=true) en vallen onder dezelfde credit/budget-pauze.
- **Voortgang & restart** — de hele run-status leeft in de DB; een rest-restart midden in een run
  pikt 'm op zonder dubbele stories. Een job is `done` zodra zijn story terminaal is en `failed`
  zodra het error-veld van de story of een subtaak is gezet; een `failed` job blokkeert de rest van
  de nacht niet.
- **Digest** — na de summary-tijd stuurt de factory exact één digest naar Telegram (en bewaart 'm in
  de UI), gegroepeerd per project met per job duur, kosten ($) en story-link, plus totale duur en
  kosten van de run. Een lege run levert een korte "geen jobs"-digest.
- **`/nightly`** toont bovenaan de status van de huidige/laatste run (per project gescheiden met
  done/lopend/pending jobs); daaronder blijven de handmatige job-lijst en Nightly-knop ongewijzigd.
