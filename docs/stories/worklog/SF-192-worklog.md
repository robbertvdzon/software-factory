# SF-192 / SF-193 - Worklog

## Story in eigen woorden

Voeg een verplichte handmatige goedkeur-poort toe als vaste, niet-AI subtaak vlak vóór
de merge-subtaak. De poort staat per project default AAN en is uit te zetten via
`projects.yaml` (`manualApprove: false`). Goedkeuren/afkeuren loopt uniform via het
bestaande `@factory:command`-mechanisme (dashboard én Telegram). Afkeuren reset de hele
story-keten en geeft de afkeurreden mee aan de volgende ronde via de story-description.

## Checklist

- [x]: read issue + factory docs
- [x]: enum — `SubtaskType.MANUAL_APPROVE`, `FactoryCommand.APPROVE/REJECT`, drie nieuwe `SubtaskPhase`-waarden
- [x]: YouTrack-client — nieuwe type- en fase-waarden geregistreerd
- [x]: config — per-project `manualApprove` (boolean, default true) + `manualApproveFor()`
- [x]: materialisatie — één manual-approve-spec tussen planner-specs en merge/deploy, conditioneel + idempotent
- [x]: coördinator — start→needed, needed→wachten, approved→advance, not-approved→reset
- [x]: commando's — approve/reject in `ManualCommandService` (+ reden via comment), endpoint uitgebreid met optionele reden
- [x]: reset — alle subtaken (incl. poort) fase-leeg + todo-lane, eerste op `start`, reden in gemarkeerd description-blok
- [x]: FE — command-based approve/reject-kaart voor de poort
- [x]: Telegram — `manual-approve-needed` als wacht-op-mens; reply approve/reject-met-reden → commando
- [x]: unittests — config aan/uit, plaatsing + idempotentie, drie fase-overgangen, reset
- [x]: build/tests gedraaid (`mvn -f softwarefactory/pom.xml test`)

## Gedaan en waarom

**Enum + tracker-registratie**
- `core/TrackerModels.kt`: `SubtaskType.MANUAL_APPROVE("manual-approve")` (niet-AI) en
  `FactoryCommand.APPROVE("approve")` / `REJECT("reject")`.
- `core/SubtaskPhase.kt`: `MANUAL_APPROVE_NEEDED` (wachten, niet-actief),
  `MANUALLY_APPROVED` (terminaal — toegevoegd aan `isTerminal`), `MANUALLY_NOT_APPROVED`
  (transient → reset).
- `youtrack/clients/YouTrackClient.kt`: de drie fase-waarden en `manual-approve` toegevoegd
  aan `subtaskPhaseValues` / `subtaskTypeValues`, zodat aanmaken/zetten in YouTrack niet faalt.

**Config**
- `config/ProjectRepoResolver.kt`: parse `manualApprove` (boolean, default true) per project en
  `manualApproveFor(projectName)` (default AAN; alleen expliciete `false` zet 'm uit).

**Materialisatie**
- `runtime/services/AgentRunCompletionService.kt`: tussen de planner-specs en de afsluitende
  merge/deploy-specs wordt — als de poort voor het project aanstaat — één
  `MANUAL_APPROVE`-subtaak ("Handmatige goedkeuring") aangemaakt. `ProjectRepoResolver`
  geïnjecteerd; project bepaald via het `Repo`-veld van de story. Idempotent via de bestaande
  titel-check (al-gestarte titels niet opnieuw aanmaken).

**Coördinator**
- `pipeline/service/SubtaskExecutionCoordinator.kt`: `manualApproveSubtask()` (start→needed,
  needed→wachten, approved→`advanceSubtaskChain`, not-approved→`resetStoryChainAfterRejection`).
  De reset zet alle subtaken (incl. de poort zelf) Subtask Phase leeg + State-lane naar todo
  (`Open`), de story naar todo, en de eerste subtaak op `start`. Idempotent: na de reset is de
  poort-fase leeg, dus de volgende poll triggert de reset niet opnieuw.

**Commando's + endpoint**
- `orchestrator/services/ManualCommandService.kt`: `approve`→`manually-approved`,
  `reject`→`manually-not-approved` + de reden in een herhaalbaar te overschrijven blok
  (`<!-- manual-approve-feedback:start/end -->`) in de story-description. No-op als de subtaak
  niet in `manual-approve-needed` staat.
- `OrchestratorApi`/`OrchestratorService`: `queueCommand` uitgebreid met optionele reden, die als
  aparte regel na het command-token in de comment komt.
- `FactoryDashboardController`/`FactoryDashboardService`: command-endpoint accepteert nu een
  optioneel `comment`-veld (de reden) en `returnTo`.

**FE**
- `web/views/FactoryDashboardViews.kt`: nieuwe `approveRejectCommandCard` (mirror van
  `approveRejectCard`) die voor `manual-approve-needed` via `/commands/approve|reject` loopt
  (geen los phase-pad); reject geeft de ingevulde reden mee.
- `awaitsHuman` (`FactoryDashboardService`) classificeert `manual-approve-needed` als wacht-op-mens,
  óók bij auto-approve.

**Telegram**
- `TelegramNotificationService`: `manual-approve-needed` als MANUAL (wacht-op-mens) + passende
  reply-hint. De vrije-tekst-vertaling naar approve/reject loopt via de reply-laag, in lijn met de
  bestaande approve/reject-replies (de assistent zelf is een read-only research-assistent).
- `TelegramReplyService`: reply op de poort-melding → instemmend woord = approve-commando, andere
  tekst = reject-commando met die tekst als reden.

## Tests

Nieuw/aangepast:
- `config/ProjectRepoResolverMergeDeployTest`: `manualApproveFor` default + yaml aan/uit.
- `runtime/AgentRunCompletionServiceTest`: poort in de keten (default aan), idempotentie
  (al-gestarte poort niet opnieuw), poort uit per project; bestaande keten-asserties bijgewerkt.
- `orchestrator/ManualCommandServiceTest`: approve→manually-approved, reject→manually-not-approved
  + reden in description, no-op buiten de poort.
- `orchestrator/OrchestratorServiceTest`: de drie coördinator-fase-overgangen + de reset.
- `e2e/FakeYouTrackState`: nieuwe enum-waarden geseed (schema-bootstrap blijft no-op).
- `e2e/E2eTestConfig`: poort uit voor het `sample`-project zodat de full-chain e2e-keten
  ongewijzigd doorloopt.

`mvn -f softwarefactory/pom.xml test` (excl. de Docker-afhankelijke e2e-tests en de bekende
pre-existing flaky tests `ModulithArchitectureTest` / `AgentResultFileCompletionPollerTest`) is groen.

## Aangepaste specs/config

- `projects.yaml.example`: `manualApprove`-optie gedocumenteerd.
- `docs/factory/functional-spec.md` + `technical-spec.md`: manual-approve-poort beschreven.
</content>

## Review (reviewer, 2026-06-25)

Volledige story-diff t.o.v. `main` beoordeeld. Conclusie: **akkoord**.

- [info] Alle 20 acceptatiecriteria zijn geïmplementeerd en coherent: enum + YouTrack-registratie,
  per-project config (`manualApproveFor`, default aan), conditionele + idempotente materialisatie
  tussen summary en merge, coördinator-fase-overgangen (start→needed, needed→wachten,
  approved→`advanceSubtaskChain`, not-approved→volledige reset), approve/reject via het
  `@factory:command`-mechanisme met reden in een herhaalbaar te overschrijven description-blok,
  FE `approveRejectCommandCard`, Telegram-classificatie + reply-vertaling, en de story-reset.
- [info] Commando wordt op de subtaak-key gepost; `isManualApproveGate`-guard maakt approve/reject
  een no-op buiten de poort → geen impact op andere subtaaktypes (in scope-grens gerespecteerd).
- [info] Idempotentie/herstart-loop netjes afgedekt: na reset is de poort-fase leeg (geen
  her-trigger), en de `advanceSubtaskChain`-guard voorkomt herhaald terugzetten op `start`.
- [info] Testdekking aanwezig voor config aan/uit, plaatsing+idempotentie, de fase-overgangen en de
  reset; bestaande keten-asserties + e2e-seed bijgewerkt; specs (functional/technical) consistent
  met de diff.
- [suggestie] Geen dedicated unittest voor de Telegram vrije-tekst→commando-vertaling (AC15) in
  `TelegramReplyService`; de logica hergebruikt `isApproval` en is laag-risico, maar een kleine test
  zou de regressievangst completeren.
- [suggestie] Reject zonder reden levert "(geen reden opgegeven)" op; placeholder noemt 'verplicht'
  maar er is geen server-side validatie. Conform de aannames acceptabel.

Geen blockers; specs zijn consistent. Build wordt door CI gevalideerd (geen mvn in reviewer-omgeving).
