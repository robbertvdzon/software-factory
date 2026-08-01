# SF-1560 - [Audit] Mislukte tracker-lees niet meer behandelen als 'geen parent' vóór agent-dispatch en deploy-approve

## Story

[Audit] Mislukte tracker-lees niet meer behandelen als 'geen parent' vóór agent-dispatch en deploy-approve

<!-- refined-by-factory -->

## Samenvatting

Als het ophalen van de bovenliggende story mislukt, gaat de factory nu vrolijk verder alsof die story niet bestaat. Dat pakt op twee plekken slecht uit.

Bij het starten van een agent wordt de pauze-knop en de foutstatus van de story dan overgeslagen: er start een betaalde agent voor werk dat je net had gepauzeerd of dat al met een fout geparkeerd stond. Bij de deploy-stap wordt de deploy zonder één echte actie op "goedgekeurd" gezet, waarna de story als gedeployed gerapporteerd wordt terwijl er niets is uitgerold.

In beide gevallen moet de factory de subtaak gewoon overslaan en het bij de volgende ronde opnieuw proberen. Dat vraagt geen handmatige actie: de orchestrator komt elke poll terug, dus een tijdelijke storing lost zichzelf op.

## Scope

Twee bestanden in module `softwarefactory`, plus tests.

**1. `pipeline/service/SubtaskExecutionCoordinator.kt` — `dispatchSubtask` (rond r362-371).**
- Vervang `runCatching { issueTrackerClient.getIssue(parentKey) }.getOrNull()` door de `getOrElse`-variant die logt (`logger.warn`, met `parentKey` en de throwable) en `IssueProcessResult.Skipped(subtask.key, "parent-unavailable")` teruggeeft.
- `getIssue` heeft een non-null returntype, dus `parent` is na deze wijziging altijd non-null. Ruim de daardoor dode nullability op in het vervolg van dezelfde methode: `parent?.fields?.aiSupplier`, `parent?.fields?.repo` (inclusief de nu onjuist geworden fallback-comment "valt terug op het eigen Repo-veld als de parent (nog) niet leesbaar is" en de `?: projectRepoResolver.resolve(subtask.fields.repo)`-tak), en `budgetIssue = parent ?: subtask` → `budgetIssue = parent`.
- De bestaande `parentKey == null`-tak erboven (Errored, "subtask zonder parent") blijft ongewijzigd: een subtaak zonder parent is iets anders dan een onleesbare parent.

**2. `pipeline/service/DeploySubtaskHandler.kt` — `process` (rond r134-140).**
- Zelfde patroon: `getOrElse` met `logger.warn` (de klasse heeft al een `logger` op r61) en `IssueProcessResult.Skipped(subtask.key, "deploy-parent-unavailable")`.
- De bestaande `parentKey == null` → `Skipped(…, "deploy-no-parent")`-tak blijft ongewijzigd.
- Daarmee is `projectName` (`parent.fields.repo`) niet langer `null` als gevolg van een leesfout.

**Vorm.** Volg de bestaande huisstijl letterlijk; referentie-implementaties staan in `StoryRefinementCoordinator.kt:145-148`, `SubtaskExecutionCoordinator.kt:213` en `ManualCommandService.kt:405`. Logregels in het Nederlands, met SLF4J-placeholders (`{}`), throwable als laatste argument.

**Tests** (module `softwarefactory`, `src/test/.../pipeline/`):
- Deploy: uitbreiding van `DeploySubtaskHandlerTest.kt`. De fake daar bouwt `getIssue` op een `parentIssue()`-lambda (r101) — laat die gooien.
- Dispatch: nieuwe/uitgebreide test rond `SubtaskExecutionCoordinator`. `FakeTrackerApi.getIssue` doet `issues.first { it.key == issueKey }`, dus de parent weglaten uit de issues-lijst terwijl `parentKey` wél gezet is levert de exception-seam zonder nieuwe fake-code. Instapunten: `testsupport/OrchestratorTestHarness.kt` en `orchestrator/OrchestratorPrAndLoopbackTest.kt`.

**Expliciet buiten scope**
- De `DeployConfig.Skip()`-default in `ProjectConfiguration.deployTargetsFor` (`factory-common/.../config/ProjectConfiguration.kt:330-336`) blijft ongewijzigd — die is bewust voor een echt ongeconfigureerd project.
- `needsWatch`, `matchedTargets` en `matchedDeployTargetsFor` blijven ongewijzigd.
- De overige ~40 `runCatching { … }.getOrNull()`-aanroepen in `src/main` (dashboardweergaven, notificaties, best-effort verrijking) blijven ongemoeid.
- Geen retry/backoff, geen nieuwe fase, geen `TrackerField.ERROR`-schrijfactie, geen Telegram-melding: skippen is de hele oplossing.

## Acceptance criteria

1. Faalt `getIssue(parentKey)` in `dispatchSubtask` met een exception, dan geeft de methode `IssueProcessResult.Skipped(subtask.key, "parent-unavailable")` terug en vindt er géén `dispatcher.dispatch(...)` plaats.
2. Bij die mislukte lees wordt één `warn`-logregel geschreven met de parent-key en de onderliggende exception.
3. Faalt `getIssue(parentKey)` in `DeploySubtaskHandler.process` met een exception, dan geeft de methode `IssueProcessResult.Skipped(subtask.key, "deploy-parent-unavailable")` terug; er wordt géén `SUBTASK_PHASE`-update naar `DEPLOY_APPROVED` geschreven, geen `advanceChain` aangeroepen en geen deploy-doel getriggerd. Dit geldt óók voor fase `START` (het pad dat vandaag de foutieve approve doet).
4. Ook in `DeploySubtaskHandler` wordt bij die mislukte lees één `warn`-logregel met parent-key en exception geschreven.
5. Slaagt de lees, dan is het gedrag op beide plekken exact ongewijzigd — inclusief de bestaande pauze-, fout-, supplier- en repo-afhandeling, de `DEPLOY_APPROVED`-tak bij een leeg `watchTargets`, en de `parentKey == null`-takken (`Errored` resp. `"deploy-no-parent"`).
6. `budgetIssue` in de dispatch-context is bij een geslaagde lees de parent-story (functioneel gelijk aan vandaag; de `?: subtask`-fallback is verwijderd omdat die onbereikbaar is geworden).
7. Nieuwe tests dekken punt 1 en 3; `mvn -pl softwarefactory test` slaagt volledig, inclusief alle bestaande tests.
8. Geen wijziging in `factory-common`, geen wijziging aan `deployTargetsFor`/`needsWatch`, geen wijziging aan andere `.getOrNull()`-aanroepen.

## Aannames

- "Skippen" betekent uitsluitend: deze poll niets doen. De subtaak behoudt zijn huidige fase en wordt bij de volgende orchestrator-poll opnieuw aangeboden; dat is het bestaande zelfherstel en er is geen extra bewakings- of alarmeringsmechanisme nodig.
- Een mislukte lees is geen gebruikerszichtbare fout: er wordt géén `TrackerField.ERROR` gezet en geen Telegram-melding gestuurd. Alleen een warn-log.
- Er komt geen limiet op het aantal opeenvolgende skips; een permanent onleesbare parent blijft dus per poll een warn-regel produceren. Dat is bewust — een structurele trackerstoring hoort zichtbaar te blijven in de logs, niet stilgezet te worden.
- De skip-redenen `"parent-unavailable"` en `"deploy-parent-unavailable"` zijn vrije diagnostische strings (net als de bestaande `"parent-paused"`, `"deploy-no-parent"`); er wordt nergens op deze waarden gematcht buiten logging/tests.
- Het opruimen van de dode `?.`-nullability en de bijbehorende comment in `dispatchSubtask` valt binnen deze story, omdat de code anders een fallback beschrijft die niet meer kan optreden.

## Eindsamenvatting

Ik heb `.task.md`, het worklog `docs/stories/worklog/SF-1560-worklog.md` en de volledige diff (`main...HEAD`, 3 commits: developer, reviewer, tester) doorgenomen.

# SF-1560 — Mislukte tracker-lees niet meer behandelen als 'geen parent'

## Wat er speelde
Op twee plekken werd een mislukte lees van de parent-story (`runCatching { getIssue(parentKey) }.getOrNull()`) stil afgedaan als "er is geen parent". Gevolgen: bij het starten van een agent werden de pauze-knop en de foutstatus van de story overgeslagen (er startte alsnog een betaalde agent voor gepauzeerd of geparkeerd werk), en bij de deploy-stap werd de subtaak zonder één echte actie op *deploy-approved* gezet, waarna de story als gedeployed werd gerapporteerd terwijl er niets was uitgerold.

## Wat is gebouwd
Twee wijzigingen in module `softwarefactory` (src/main), beide klein en volgens de bestaande huisstijl:

- **`SubtaskExecutionCoordinator.dispatchSubtask`** — mislukte parent-lees geeft nu één Nederlandstalige `warn`-logregel (met parent-key en de exception) en `Skipped(subtask.key, "parent-unavailable")`, vóór élke pauze-, fout- en supplier-check en vóór `dispatcher.dispatch`.
- **`DeploySubtaskHandler.process`** — zelfde patroon met reden `"deploy-parent-unavailable"`. Daarmee kan `projectName` niet meer door een leesfout leeg raken en kan de `START`-tak niet langer ten onrechte `DEPLOY_APPROVED` schrijven.

Skippen is de hele oplossing: de orchestrator komt elke poll terug, dus een tijdelijke trackerstoring herstelt zichzelf zonder handmatige actie.

## Keuzes
- **Dode nullability opgeruimd.** Omdat `getIssue` non-null teruggeeft, is `parent` na de early return altijd gevuld. De `?.`-vormen, de `budgetIssue = parent ?: subtask`-fallback en de `targetRepo`-terugval op het eigen Repo-veld (inclusief de nu onjuiste comment) zijn verwijderd. Bijwerking, door de story voorgeschreven en door reviewer én tester expliciet vastgelegd: is de parent wél leesbaar maar het Repo-veld leeg, dan valt `targetRepo` niet meer terug op het subtask-Repo-veld. In de praktijk is dat veld in dat geval doorgaans ook leeg.
- **`parentKey == null` blijft ongewijzigd** (`Errored` bij dispatch, `"deploy-no-parent"` bij deploy): een subtaak zónder parent is iets anders dan een onleesbare parent.
- **Geen alarmering.** Geen `TrackerField.ERROR`, geen Telegram-melding, geen retry/backoff en geen limiet op opeenvolgende skips — een structurele trackerstoring blijft bewust zichtbaar als warn-regel per poll.
- **Testfakes eerlijk gemaakt.** `FakeTrackerApi` kreeg een optionele `parentIssue`-parameter. Tien bestaande tests leunden onbedoeld op de oude "onleesbare parent == geen parent"-coulance en zijn nu expliciet van een leesbare parent voorzien.

## Wat is getest
- Twee nieuwe gerichte tests: `DeploySubtaskHandlerTest` (fase `START` met onleesbare parent → Skipped, géén enkele veldupdate dus ook geen `DEPLOY_APPROVED`, geen `advanceChain`, geen deploy-doel getriggerd) en `OrchestratorPrAndLoopbackTest` (onleesbare parent → Skipped, geen dispatch). Beide asserteren ook exact één WARN-regel met parent-key én onderliggende exception.
- Volledige build door de tester: `mvn -pl softwarefactory -am test` → 685 tests groen, en `-am verify` → 685 unit + 74 e2e (Testcontainers-Postgres), 0 failures / 0 errors. Geen flakes deze ronde.
- Reviewer liep alle acceptatiecriteria (1 t/m 8) na tegen de diff en draaide daarnaast gericht de overige `FakeTrackerApi`-gebruikers buiten de diff (56 + 59 tests groen). Akkoord, geen blockers.

## Bewust niet gedaan
- `DeployConfig.Skip()`-default in `ProjectConfiguration.deployTargetsFor` ongemoeid (bedoeld voor een écht ongeconfigureerd project).
- `needsWatch`, `matchedTargets` en `matchedDeployTargetsFor` ongewijzigd.
- De overige ~40 `runCatching { … }.getOrNull()`-aanroepen in `src/main` (dashboardweergaven, notificaties, best-effort verrijking) niet aangeraakt.
- Geen wijzigingen in `factory-common` en geen documentatiewijziging in `docs/factory/` — geen spec beschrijft de oude fallback (gecontroleerd).

## Openstaand aandachtspunt (geen blocker)
De reviewer suggereerde de Logback-`ListAppender` in beide nieuwe tests in een `@AfterEach` los te koppelen; onschuldig zolang de suite serieel draait, maar relevant als er ooit parallelle testuitvoering wordt aangezet.

Volgende subtaken in de story: documentatie (SF-1655), merge (SF-1656) en deploy (SF-1657).

```json
```
