# SF-1830 - Worklog

Story-context bij eerste pickup:
Nieuwe deploymelding + PO-samenvatting van de summarizer

Herbouw de result-notify-melding in TelegramResultNotifyPoller.send(): kop '🚀 Story <KEY> is deployed!', daaronder een korte functionele samenvatting, daaronder (indien aanwezig) de URL; lege regel tussen elk blok. De losse regel '<KEY>: <titel>' en de bevestigingszinnen ('De live-URL is bereikbaar.' / 'De nieuwe versie draait live.' / 'Er staat een nieuwe APK-release klaar.') vervallen uit de berichttekst; reduceer het interne Confirmation-model tot alleen een URL-drager. De onderliggende checks blijven functioneel identiek: confirmOpenshift() (HTTP-200 op liveUrl), confirmApk() (ApkReleaseProbe), de DEPLOY_FAILED-skip, de GIVEUP_HOURS-timeout en het null-return-gedrag (= nog niet melden, volgende poll opnieuw) bepalen nog steeds OF, WANNEER en met welke URL er gemeld wordt.

Samenvattingsbron, eerste niet-lege wint: (1) het PO-blok van de summarizer, (2) de '## Samenvatting'-sectie uit TrackerIssue.description (al gevuld door findWorkIssues via ISSUE_COLUMNS; tekst vanaf de kopregel tot de volgende '## '-kop of het einde, getrimd), (3) niets - dan alleen kop + eventuele URL.

Uitleespad PO-blok: voeg 'deploySummaryFor(storyKey): String?' toe aan de poort FactoryOperations (softwarefactory/.../core/contracts/FactoryOperations.kt, naast testerReportFor) en implementeer die in FactoryOperationsService met een internal, puur functionele companion-helper deploySummaryFrom(runs) - exact het patroon van testerReportFor (r90) / testerReportFrom (r166): meest recente SUMMARIZER-run met niet-lege summaryText, daaruit het blok tussen de markers. TelegramResultNotifyPoller krijgt FactoryOperations als extra constructor-dependency (zelfde injectie als TelegramNotificationService).

Summarizer levert het blok: breid RolePrompts.summarizerPrompt() in agentworker/.../agent/ai/shared/AgentPromptContracts.kt uit zodat de summarizer naast de bestaande eindsamenvatting een blok van max. 3 zinnen in gewone taal levert, gericht op de gebruiker die de story heeft aangevraagd (geen jargon, geen technische details, geen bestands-/klassenamen), afgebakend met markers op eigen regels: '<!-- deploy-summary:start -->' / '<!-- deploy-summary:end -->'. Werk docs/factory/agents/summarizer.md EN de identieke kopie in factory-common/src/main/resources/docs-skeleton/docs/factory/agents/summarizer.md in dezelfde bewoording bij.

Robuustheid: alle bronnen soft-fail via runCatching{}.getOrNull() (patroon TelegramNotificationService.testerReport(), r351) - een fout bij ophalen of parsen mag de melding nooit tegenhouden, hij valt door naar de volgende bron. Strip trailing control-JSON via ControlJsonStripper en kap af op een Telegram-veilige lengte (~1000 tekens, orde van TESTER_REPORT_LIMIT). Idempotentie blijft: recordNotified(story.key, 'result-notify') alleen na een geslaagde sendMessage, dus hooguit één melding per story.

Ongewijzigd laten: TelegramNotificationService.notifySubtaskDone (stand 'als klaar', met subtaakoverzicht), de bestaande eindsamenvatting van de summarizer richting tracker-comment en docs/stories/*.md, de deploy-/APK-detectie, TelegramStore/telegram_notifications en de poll-frequentie.

Tests (onderdeel van deze subtaak): breid TelegramResultNotifyPollerTest uit met (a) mét PO-blok, (b) alleen description-'## Samenvatting', (c) zonder beide (alleen kop + URL), (d) soft-fail wanneer het ophalen van de samenvatting gooit; plus een unittest op deploySummaryFrom (meest recente SUMMARIZER-run wint; geen markers -> null). Werk de bestaande handgeschreven FactoryOperations-fakes bij in TelegramPollerTest.kt, TelegramReplyServiceTest.kt en TelegramNotificationServiceTest.kt (FakeDashboard), anders compileert de testmodule niet. Sluit af met een eigen reviewslag en zorg dat 'mvn verify' groen is.

Stappenplan:
[x]: read issue and target docs
[x]: nieuwe berichtopbouw in TelegramResultNotifyPoller.send() (kop + samenvatting + URL)
[x]: Confirmation gereduceerd tot URL-drager; checks ongewijzigd
[x]: deploySummaryFor op FactoryOperations + deploySummaryFrom-helper in FactoryOperationsService
[x]: summarizer levert het PO-blok (prompt + beide summarizer.md-kopieen)
[x]: fallback op '## Samenvatting' uit de story-description, soft-fail + strip + afkappen
[x]: unittests (poller a/b/c/d + deploySummaryFrom) en bestaande FactoryOperations-fakes bijgewerkt
[x]: docs/factory functional-spec.md en technical-spec.md bijgewerkt
[x]: run relevant tests + volledig vangnet
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `TelegramResultNotifyPoller.send()` bouwt het bericht nu uit blokken met een lege regel ertussen:
  kop `🚀 Story <KEY> is deployed!`, optionele functionele samenvatting, optionele URL. De losse
  `<KEY>: <titel>`-regel en de bevestigingszinnen zijn weg; `Confirmation` draagt alleen nog de URL.
  De beslislogica (confirmOpenshift/confirmApk/DEPLOY_FAILED/GIVEUP_HOURS/null = nog niet melden)
  is functioneel ongemoeid gebleven — alleen de teksten zijn uit het bericht gehaald.
- Samenvattingsbron in `functionalSummary()`: eerst `FactoryOperations.deploySummaryFor(story.key)`
  (het summarizer-blok), anders de `## Samenvatting`-sectie uit `TrackerIssue.description`
  (companion-helper `summarySectionOf`, stopt bij de volgende `## `-kop). Beide bronnen staan in een
  `runCatching{}.getOrNull()`, daarna `ControlJsonStripper.stripTrailingControlJson` + trim +
  `take(1000)`. Zo kan een DB-/parse-fout de melding nooit tegenhouden.
- `deploySummaryFor` is toegevoegd aan de poort `FactoryOperations` en geimplementeerd in
  `FactoryOperationsService`, met de `internal`, pure companion-helper `deploySummaryFrom(runs)`
  (meest recente SUMMARIZER-run met niet-lege `summaryText`, blok tussen de markers, leeg -> null) —
  exact het patroon van `testerReportFor`/`testerReportFrom`.
- `RolePrompts.summarizerPrompt()` vraagt nu naast de bestaande eindsamenvatting een blok van max. 3
  zinnen in gewone taal tussen `<!-- deploy-summary:start -->` / `<!-- deploy-summary:end -->`.
  Dezelfde bewoording staat in `docs/factory/agents/summarizer.md` en de identieke docs-skeleton-kopie
  in `factory-common/src/main/resources/docs-skeleton/...` (byte-identiek gehouden via `cp`).
- Tests: `TelegramResultNotifyPollerTest` heeft er vier bij (PO-blok wint, fallback op de
  description-sectie, alleen kop + URL, en een gooiende `deploySummaryFor` die de melding niet
  blokkeert) met exacte assertions op de volledige berichttekst; `DashboardQueryServiceTest` dekt
  `deploySummaryFrom` (meest recente SUMMARIZER-run wint; geen markers -> null). De handgeschreven
  `FactoryOperations`-fakes in `TelegramPollerTest`, `TelegramReplyServiceTest` en
  `TelegramNotificationServiceTest` (FakeDashboard) kregen de nieuwe methode.
- Specs bijgewerkt: `docs/factory/technical-spec.md` (berichtopbouw + samenvattingsbronnen +
  poortmethode) en `docs/factory/functional-spec.md` (wat de gebruiker in het bericht ziet).

Vangnet (02-08-2026, branch ai/SF-1830):
- `mvn -B --no-transfer-progress clean verify` vanaf de repo-root: BUILD SUCCESS, exit 0,
  0 failures / 0 errors (softwarefactory 3m48, totaal 4m19).
- `./quality/run.sh` (ratchet): `ok: true`, `new: []`, 3 resolved findings. Let op: de eerste run gaf
  nog 1 nieuwe blocking `ReturnCount` op `summarySectionOf` (3 returns, limiet 2); die helper is
  daarna herschreven naar een enkele return-expressie.
- `tools/audit-documentation`: PASS. `tools/generate-module-dependencies --check`: actueel.

Review (02-08-2026, reviewer, branch ai/SF-1830):
- Volledige story-diff t.o.v. `main` beoordeeld (14 bestanden). Berichtopbouw, `Confirmation` als
  URL-drager, de bronketen in `functionalSummary()`, de poortmethode `deploySummaryFor` +
  `deploySummaryFrom`-helper en de summarizer-prompt komen overeen met de refined story en de
  acceptance criteria. Geen scope creep; `notifySubtaskDone`, deploy-/APK-detectie, `TelegramStore`
  en de poll-frequentie zijn ongemoeid.
- Beide `summarizer.md`-kopieen zijn byte-identiek geverifieerd (`diff` → geen verschil).
  `docs/factory/functional-spec.md` en `technical-spec.md` beschrijven de nieuwe berichtopbouw.
- Gerichte hercontrole in de reviewomgeving: `mvn -B -pl factory-common,softwarefactory -am test
  -Dtest=TelegramResultNotifyPollerTest,DashboardQueryServiceTest,TelegramNotificationServiceTest`
  → 119 tests, 0 failures / 0 errors, BUILD SUCCESS (35s). `tools/generate-module-dependencies
  --check` en `tools/audit-documentation` exit 0. `.factory/verification.yaml` ongewijzigd.
- Openstaande kleinigheid (geen blocker): de klasse-KDoc van `TelegramResultNotifyPoller` (r40-41)
  noemt nog de verwijderde zin "Er staat een nieuwe APK-release klaar" als melding.
