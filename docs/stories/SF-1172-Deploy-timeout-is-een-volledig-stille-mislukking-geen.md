# SF-1172 - Deploy-timeout is een volledig stille mislukking (geen error, geen Telegram-melding)

## Story

Deploy-timeout is een volledig stille mislukking (geen error, geen Telegram-melding)

<!-- refined-by-factory -->

## Scope

`DeploySubtaskHandler.failWithTimeout()` (softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/pipeline/service/DeploySubtaskHandler.kt, regel 190-203) zet bij een deploy-timeout alleen `TrackerField.SUBTASK_PHASE` op `deploy-failed`, maar vult nooit `TrackerField.ERROR`. Hierdoor blijft een deploy-timeout volledig stil: `TelegramResultNotifyPoller.processStory()` slaat `SubtaskPhase.DEPLOY_FAILED` bewust over met de aanname dat er al een ERROR-melding is verstuurd, terwijl dat niet zo is. `TelegramNotificationService` stuurt namelijk alleen een `NotifyCategory.ERROR`-bericht als `issue.fields.error` niet leeg is (regel 166-168).

Deze story fixt dat gat, uitsluitend in `failWithTimeout()` — dit is het enige punt waar `SubtaskPhase.DEPLOY_FAILED` wordt gezet zonder `TrackerField.ERROR` (geverifieerd: `pollRestRestart` en `pollOpenshiftWatch` roepen alleen deze functie aan voor de timeout-afhandeling; er is geen ander pad dat `DEPLOY_FAILED` zet).

Wijziging:
- `failWithTimeout()` roept `updateIssueFields` aan met zowel `TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOY_FAILED.trackerValue` als `TrackerField.ERROR to <duidelijke boodschap>`, in dezelfde update-call.
- De foutboodschap benoemt expliciet het type timeout, de duur en de subtaak-key, bijvoorbeeld: `"[ORCHESTRATOR] Deploy-timeout voor ${subtask.key} na ${timeoutMinutes} minuten, geen bevestiging via ArgoCD/rest-restart."` (consistent met het bestaande `[ORCHESTRATOR]`-prefixpatroon elders in dit bestand, bv. regel 106/132/147).
- Geen wijziging aan `TelegramResultNotifyPoller` nodig: die berust terecht op het bestaande ERROR-notificatiekanaal (`TelegramNotificationService`, `NotifyCategory.ERROR`), dat na deze fix daadwerkelijk afgaat zodra `issue.fields.error` niet-leeg is.

## Acceptance criteria

1. Bij een rest-restart-deploy die de geconfigureerde `timeoutMinutes` overschrijdt zonder geslaagde versie-bevestiging, staat na afloop zowel `TrackerField.SUBTASK_PHASE = deploy-failed` als `TrackerField.ERROR` (niet-leeg, met duidelijke timeout-boodschap) op de subtaak.
2. Idem voor een openshift-watch-deploy (inclusief het ArgoCD-pad) die de timeout overschrijdt: zowel `SUBTASK_PHASE = deploy-failed` als een niet-lege `TrackerField.ERROR`.
3. De foutboodschap bevat minimaal: dat het om een deploy-timeout gaat, de timeoutduur (minuten), en is herkenbaar als afkomstig van de orchestrator (conform bestaand `[ORCHESTRATOR]`-prefixpatroon in dit bestand).
4. Er is geen ander codepad gevonden/gecreëerd waar `SubtaskPhase.DEPLOY_FAILED` wordt gezet zonder `TrackerField.ERROR`; dit is bevestigd voor de bestaande `pollOpenshiftWatch`/`pollRestRestart`/`pollArgoCd`-paden (die lopen allemaal via `failWithTimeout()` voor de timeout-afhandeling).
5. De bestaande tests `rest-restart timeout sets DEPLOY_FAILED` en `openshift-watch timeout sets DEPLOY_FAILED` (softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/pipeline/DeploySubtaskHandlerTest.kt, regel 291 resp. 311) worden uitgebreid met een assertie dat `TrackerField.ERROR` in dezelfde update-call een niet-lege waarde krijgt.
6. Een nieuwe of aangepaste test bevestigt end-to-end dat een deploy-timeout (via het gezette ERROR-veld) daadwerkelijk een Telegram-ERROR-melding triggert — hetzij door `TelegramNotificationService`'s bestaande event-detectielogica (regel 166-168) direct te testen met een issue waarvan `fields.error` gevuld is door de fix, hetzij door een gerichte unit-/integratietest die dit pad dekt. Geen wijziging nodig aan `TelegramResultNotifyPoller`, aangezien die voor `DEPLOY_FAILED` terecht op dit kanaal leunt.
7. Bestaande gedrag blijft ongewijzigd voor het geslaagde deploy-pad (approve/DEPLOY_APPROVED) en voor andere foutscenario's in `DeploySubtaskHandler` (401, connectiefout) die al wel `TrackerField.ERROR` zetten.

## Aannames

- De foutboodschap-tekst is vrij te kiezen zolang die duidelijk, in het Nederlands (consistent met de rest van dit bestand) en `[ORCHESTRATOR]`-geprefixt is; geen exacte woordkeuze vereist.
- Punt 6 (Telegram-end-to-end-bevestiging) mag op unit-testniveau worden aangetoond (bewijzen dat `TelegramNotificationService` het gevulde ERROR-veld oppikt) — een volledige e2e-test met een live Telegram-mock is niet vereist, tenzij de tester/developer al bestaande e2e-infrastructuur hiervoor heeft.
- De check op "andere plekken waar DEPLOY_FAILED gezet wordt zonder ERROR" (issue-punt 2) is voor dit bestand al uitgevoerd tijdens refinement: er is geen ander codepad gevonden. De developer hoeft dit dus niet opnieuw grondig te onderzoeken, maar mag dit als bevestiging beschouwen.
- De losstaande opmerking in de oorspronkelijke omschrijving over het verifiëren of de lokale factory op de juiste commit draait, valt buiten de scope van deze code-fix en wordt niet als acceptatiecriterium meegenomen.

## Eindsamenvatting

Alle benodigde context (worklog, review, testrapport, diff-stat) is verzameld. Hier is de eindsamenvatting.

## Eindsamenvatting SF-1172: Deploy-timeout was een stille mislukking

**Probleem:** Bij een deploy-timeout zette `DeploySubtaskHandler.failWithTimeout()` alleen de subtaakfase op `deploy-failed`, maar vulde nooit `TrackerField.ERROR`. Daardoor bleef `TelegramNotificationService` stil — die stuurt alleen een ERROR-melding als het foutveld niet leeg is — en kwam een mislukte deploy nooit onder de aandacht.

**Wat is gebouwd:**
- `failWithTimeout()` (`DeploySubtaskHandler.kt`) zet nu in dezelfde `updateIssueFields`-call zowel `SUBTASK_PHASE = deploy-failed` als een niet-lege `TrackerField.ERROR`, met boodschap `"[ORCHESTRATOR] Deploy-timeout voor <subtask-key> na <timeoutMinutes> minuten, geen bevestiging via ArgoCD/rest-restart."` — conform het bestaande `[ORCHESTRATOR]`-prefixpatroon in dit bestand.
- `IssueProcessResult.Errored` geeft nu de echte foutboodschap terug in plaats van de vaste placeholder-string `"deploy-timeout"`.
- Dit is het enige codepad dat `DEPLOY_FAILED` zet: zowel de rest-restart- als de openshift-watch/ArgoCD-flow lopen via deze functie, dus is de fix met één wijziging dekkend voor beide paden. Er is bevestigd dat geen ander codepad `DEPLOY_FAILED` zet zonder `ERROR`.
- `TelegramResultNotifyPoller` is bewust ongewijzigd gelaten: die skipt `DEPLOY_FAILED` omdat hij ervan uitgaat dat het ERROR-kanaal al een melding heeft gestuurd — dankzij deze fix klopt die aanname nu.

**Getest:**
- Bestaande tests `rest-restart timeout sets DEPLOY_FAILED` en `openshift-watch timeout sets DEPLOY_FAILED` uitgebreid met een assertie dat `TrackerField.ERROR` niet-leeg is in dezelfde update-call.
- Nieuwe test in `TelegramNotificationServiceTest.kt` bevestigt end-to-end dat een subtaak met gevuld foutveld daadwerkelijk een `NotifyCategory.ERROR`-melding oplevert.
- Targeted tests (`DeploySubtaskHandlerTest`, `TelegramNotificationServiceTest`): 37/37 groen, door zowel developer, reviewer als tester onafhankelijk bevestigd.
- Volledige `mvn verify` liep groen; één eerder rode e2e-test bleek een reeds bestaande omgevingsflaky, losstaand van deze wijziging.

**Bewust niet gedaan:**
- Geen wijziging aan `TelegramResultNotifyPoller` — die leunt terecht op het bestaande ERROR-kanaal.
- Geen aanpassing van `docs/factory/*` of `.factory/verification.yaml` — er verandert geen extern gedrag, API of canoniek testcommando.

**Resultaat:** review en test zijn beide akkoord gegaan zonder gevonden bugs of regressies; bestaand gedrag voor geslaagde deploys en andere foutscenario's (401, connectiefout) blijft ongewijzigd.
