# SF-1172 - Worklog

Story-context bij eerste pickup:
Deploy-timeout vult TrackerField.ERROR zodat Telegram-melding afgaat

In DeploySubtaskHandler.kt failWithTimeout() (regel ~198-201) de updateIssueFields-call uitbreiden met TrackerField.ERROR naast TrackerField.SUBTASK_PHASE=DEPLOY_FAILED, in dezelfde call. Boodschap volgens bestaand [ORCHESTRATOR]-prefixpatroon, met vermelding van timeout, timeoutMinutes en subtask-key. Geen wijziging aan TelegramResultNotifyPoller nodig. Bestaande tests 'rest-restart timeout sets DEPLOY_FAILED' en 'openshift-watch timeout sets DEPLOY_FAILED' (DeploySubtaskHandlerTest.kt) uitbreiden met assertie dat TrackerField.ERROR niet-leeg is in dezelfde update-call. Nieuwe/aangepaste test in TelegramNotificationServiceTest.kt toevoegen die bevestigt dat een issue met gevuld fields.error een NotifyCategory.ERROR-event oplevert.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `DeploySubtaskHandler.failWithTimeout()` (softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/pipeline/service/DeploySubtaskHandler.kt)
  vult nu `TrackerField.ERROR` in dezelfde `updateIssueFields`-call als `TrackerField.SUBTASK_PHASE = DEPLOY_FAILED`. Boodschap:
  `"[ORCHESTRATOR] Deploy-timeout voor ${subtask.key} na $timeoutMinutes minuten, geen bevestiging via ArgoCD/rest-restart."`
  (consistent met het bestaande `[ORCHESTRATOR]`-prefixpatroon elders in dit bestand). Dit is het enige codepad dat
  `DEPLOY_FAILED` zet (zowel rest-restart als openshift-watch/ArgoCD lopen via deze functie), dus zowel AC1 als AC2 zijn gedekt.
  `IssueProcessResult.Errored` geeft nu de echte foutboodschap terug i.p.v. de vaste string `"deploy-timeout"`
  (geen andere code/tests refereerden aan die letterlijke waarde).
- Test `rest-restart timeout sets DEPLOY_FAILED` en `openshift-watch timeout sets DEPLOY_FAILED`
  (softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/pipeline/DeploySubtaskHandlerTest.kt) uitgebreid met een assertie
  dat `TrackerField.ERROR` in dezelfde update-call een niet-lege waarde krijgt.
- Nieuwe test `SF-1179 - deploy-timeout met gevuld ERROR-veld triggert een ERROR-melding`
  (softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/telegram/TelegramNotificationServiceTest.kt) bevestigt dat een
  subtaak met `fields.error` gevuld (zoals na de fix) via `TelegramNotificationService.classify()` een `NotifyCategory.ERROR`-bericht
  (`⚠️ Fout in de Software Factory`) met de foutboodschap oplevert. Geen wijziging nodig aan `TelegramResultNotifyPoller`: die
  berust terecht op dit bestaande ERROR-kanaal, dat nu daadwerkelijk afgaat.
- Geen ander codepad gevonden waar `SubtaskPhase.DEPLOY_FAILED` gezet wordt zonder `TrackerField.ERROR` (bevestigd:
  `pollRestRestart`/`pollOpenshiftWatch`/`pollArgoCd` lopen allemaal via `failWithTimeout()` voor de timeout-afhandeling).
- Bewijs: `mvn -f softwarefactory/pom.xml test -Dtest=DeploySubtaskHandlerTest,TelegramNotificationServiceTest` -> BUILD SUCCESS
  (37 tests, 0 failures/errors). Volledige `mvn verify` vanaf de repo-root -> BUILD SUCCESS (Reactor: factory-contracts,
  factory-common, softwarefactory, agentworker, softwarefactory-dashboard-backend allemaal SUCCESS). Een eerste run had één
  rode e2e-test (`SpecScenarioCoverageE2eTest.documentation-subtaak stelt een vraag die de gebruiker beantwoordt`, timeout
  wachtend op subtask-phase); herbevestigd als bestaande omgevingsflaky (niet in scope van deze wijziging, ongewijzigd bestand)
  door 'm zowel geïsoleerd (`-Dit.test=SpecScenarioCoverageE2eTest`) als in een volledige herhaalde `mvn verify` groen te
  krijgen. Geen wijzigingen aan `.factory/verification.yaml` nodig (geen canonieke build/testcommando's aangepast).
- Geen specs in `docs/factory/` aangepast: de wijziging is een interne bugfix binnen een reeds gedocumenteerd pad
  (`DeploySubtaskHandler`/`TelegramNotificationService`-gedrag was al correct beschreven; er verandert geen extern gedrag,
  API of config).

## Review (SF-1179)

- Diff beperkt tot `DeploySubtaskHandler.failWithTimeout()` + bijbehorende tests + worklog, conform scope.
- `failWithTimeout()` zet nu `TrackerField.ERROR` (niet-leeg, `[ORCHESTRATOR]`-prefix, met subtask-key en timeoutMinutes) in dezelfde `updateIssueFields`-call als `SUBTASK_PHASE = DEPLOY_FAILED`. `IssueProcessResult.Errored` geeft nu de echte boodschap terug i.p.v. vaste string.
- Bevestigd: `pollRestRestart`/`pollOpenshiftWatch`/ArgoCD-pad lopen allemaal via `failWithTimeout()`; `TelegramResultNotifyPoller.kt:87` is de enige andere plek die `DEPLOY_FAILED` leest (niet zet) en is terecht ongewijzigd.
- Testdekking: `DeploySubtaskHandlerTest` (rest-restart + openshift-watch timeout) assert nu expliciet niet-leeg `TrackerField.ERROR` in dezelfde update-call; nieuwe `TelegramNotificationServiceTest`-test bevestigt dat een gevuld `fields.error` via `classify()` een `NotifyCategory.ERROR`-melding oplevert. Dekt AC1, AC2, AC5, AC6.
- Geen wijziging aan `.factory/verification.yaml` of `docs/factory/*` nodig/gedaan; geen spec-inconsistentie gevonden (geen bestaande spec beschrijft dit interne pad).
- Zelf geverifieerd (targeted, niet volledige suite): `mvn -o test -Dtest=DeploySubtaskHandlerTest,TelegramNotificationServiceTest` → BUILD SUCCESS, 37/37 groen, geen failures/errors. Komt overeen met developer-claim in issue comment 1500.
- Geen bugs, regressies of scope creep gevonden. Akkoord.

## Test (SF-1180)

- Diff geverifieerd tegen story-scope: uitsluitend `DeploySubtaskHandler.failWithTimeout()` + bijbehorende tests + worklog gewijzigd.
- Code gecontroleerd: `failWithTimeout()` (regel ~190-204) zet nu `TrackerField.ERROR` (niet-leeg, `[ORCHESTRATOR]`-prefix, met subtask-key + timeoutMinutes) in dezelfde `updateIssueFields`-call als `SUBTASK_PHASE = DEPLOY_FAILED`. Bevestigd dat zowel `pollRestRestart` als `pollOpenshiftWatch` via deze functie lopen (regel 208, 258-ish). `TelegramNotificationService.classify()` (regel 165-168) leest `issue.fields.error` vóórdat naar de fase-specifieke logica wordt gekeken, dus een gevuld ERROR-veld triggert nu daadwerkelijk een `NotifyCategory.ERROR`-melding voor een DEPLOY_FAILED-subtaak. `TelegramResultNotifyPoller.processStory()` blijft terecht ongewijzigd (skipt DEPLOY_FAILED met de aanname dat het ERROR-kanaal het al dekt — nu waar).
- Andere ERROR-zettende paden (401, connectiefout, regel 107/137/153) ongewijzigd — AC7 (bestaand gedrag) intact.
- Targeted tests gedraaid: `mvn -pl softwarefactory -am test -Dtest=DeploySubtaskHandlerTest,TelegramNotificationServiceTest -Dsurefire.failIfNoSpecifiedTests=false` → BUILD SUCCESS, 37/37 groen (Failures 0, Errors 0). Dekt AC1, AC2, AC5, AC6 met expliciete asserties op niet-lege `TrackerField.ERROR` en op de Telegram-ERROR-melding.
- Volledig vangnet (`mvn verify`) niet zelf herdraaid; dat voert de harness na deze run automatisch uit conform `.factory/verification.yaml`.
- Geen bugs of regressies gevonden. Akkoord.
