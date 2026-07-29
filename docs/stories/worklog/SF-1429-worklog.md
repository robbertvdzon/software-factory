# SF-1429 - Worklog

Story-context bij eerste pickup:
E2e-test: subtaak-klaar Telegram-melding via echte pijplijn (NotifyMode=na-elke-stap)

Voeg in E2eTestConfig een @Primary test-only TelegramClient-testdouble toe (subclass-patroon zoals TelegramNotificationServiceTest.RecordingTelegramClient: enabled=true, vaste defaultChatId, sendMessage legt tekst + oplopend message-id vast, gedeelde static met reset() die door E2eTestBase.resetSharedState() wordt aangeroepen). Voeg een nieuw e2e-testbestand toe in softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/ (bv. TelegramSubtaskDoneE2eTest.kt), gebaseerd op E2eTestBase: maakt een story met unieke key, zet NotifyMode op NotifyMode.EVERY_STEP.trackerValue ('na-elke-stap') via state.setEnumField, auto-approve aan, drijft de story via de echte orchestrator-poll/scheduling (geen directe aanroep van TelegramNotificationService) tot een subtaak een terminale fase bereikt (bv. development-approved), wacht via Awaitility tot de test-Telegram-client precies één bericht heeft, en verifieert dat dit bericht de subtaak-key, subtaak-titel en het '✅ Klaar'-patroon bevat. Geen wijziging aan productiecode. Bevestig dat de volledige testsuite (mvn verify) blijft slagen en dat bestaande e2e-tests ongewijzigd gedrag houden.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `E2eTestConfig` (`softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/E2eTestConfig.kt`) kreeg een
  `@Primary` `telegramClient()`-bean die een nieuwe top-level `RecordingTelegramClient` (subclass van
  `TelegramClient`, `enabled=true`, vaste `defaultChatId`, `sendMessage` legt tekst vast + oplopend
  message-id) teruggeeft — exact het subclass-patroon van
  `TelegramNotificationServiceTest.RecordingTelegramClient`. De instantie is een gedeelde static
  (`E2eTestConfig.RECORDING_TELEGRAM_CLIENT`), `reset()` wordt aangeroepen vanuit
  `E2eTestBase.resetSharedState()` (net als de andere gedeelde test-doubles). `E2eTestBase` kreeg een
  `protected val telegram`-accessor.
- Nieuw e2e-testbestand `TelegramSubtaskDoneE2eTest.kt`: story met `NotifyMode = na-elke-stap`
  (`NotifyMode.EVERY_STEP.trackerValue`) en auto-approve aan, plant bewust maar één AI-subtaak
  (`review`) en drijft die via de echte Spring-app/Testcontainers-Postgres/`OrchestratorPoller`-poll tot
  `review-approved` — geen directe aanroep van `TelegramNotificationService`. Daarna wacht de test via
  Awaitility tot de test-Telegram-client een bericht met de issue-regel `"${key}: ${summary}"` van de
  review-subtaak bevat, en verifieert dat er **precies één** zo'n bericht is (bevat het `"✅ Klaar"`-patroon).
  Ontdekt tijdens het uitwerken: `SubtaskPhase.isTerminal` bevat `DEVELOPMENT_APPROVED` NIET (development
  is in de normale keten een tussenstap vóór review/test/summary, dus levert geen eigen 'klaar'-Telegram-
  melding op) — vandaar de keuze voor een `review`-only planning (`REVIEW_APPROVED` is wél terminaal).
  Ook ontdekt: zodra `review-approved` bereikt is, dispatcht de echte pijplijn binnen enkele
  honderden milliseconden al door naar de factory-afgedwongen afsluiters (documentation/merge/deploy,
  waarbij merge in de e2e-harness faalt zonder PR-nummer) — die genereren *hun eigen* Telegram-
  meldingen. De "precies één bericht"-assertie is daarom gescoped op de issue-regel van de
  review-subtaak zelf (conform de story-aanname: "precies één bericht voor de terminale
  subtaak-gebeurtenis", niet een globale telling over de hele testrun) i.p.v. op de totale
  berichtenlijst van de client.
- Geen wijziging aan productiecode; alleen test-only bestanden aangeraakt.
- `mvn verify -Dit.test=TelegramSubtaskDoneE2eTest -Dsurefire.skip=true`: groen (1 test, 0 failures, 0
  errors, ~8s).
- Volledige `mvn verify` vanaf de repo-root: **BUILD SUCCESS**, exitcode 0, 0 failures, 0 errors over
  alle modules (incl. de bestaande e2e-/Testcontainers-tests, die ongewijzigd gedrag houden).
- Geen wijziging aan `.factory/verification.yaml` nodig (canonieke build/testcommando's `mvn verify` zijn
  ongewijzigd).
- Geen wijziging aan `docs/factory/functional-spec.md`/`technical-spec.md`/`ux/`-docs: puur test-only
  scope, geen nieuw applicatiegedrag om te documenteren.

## Review SF-1454 (reviewer, 2026-07-29)

- Volledige story-diff (`git diff main...HEAD`) bekeken: alleen `E2eTestConfig.kt`,
  `E2eTestBase.kt`, nieuw `TelegramSubtaskDoneE2eTest.kt` + worklog. Geen productiecode geraakt —
  scope klopt.
- `RecordingTelegramClient` in `E2eTestConfig.kt` volgt exact het subclass-patroon van
  `TelegramNotificationServiceTest.RecordingTelegramClient` (enabled=true, vaste defaultChatId,
  oplopend message-id). `TelegramClient` is `@Component` en dus (via de Kotlin-`spring`-
  compilerplugin in `softwarefactory/pom.xml`) open — subclassing compileert.
- `"NotifyMode"` is al een bekende testveld-mapping in `TrackerTestState.fieldFor` (ook gebruikt
  door `SpecScenarioCoverageE2eTest`); `createStory` → later `setEnumField(..,"NotifyMode",..)` is
  hetzelfde gevestigde patroon als in die bestaande test, geen nieuw risico.
  geen risico op een race met de story-pickup.
- Geverifieerd dat `OrchestratorPoller.runOnce()` `telegramNotificationService.notifyPending()`
  binnen de echte `@Scheduled`-pollcyclus aanroept (niet vanuit de test) — AC3 klopt.
  `SubtaskPhase.isTerminal` bevestigd: `REVIEW_APPROVED` is terminaal, `DEVELOPMENT_APPROVED` niet
  — de keuze voor een `review`-only planning is correct en overeenkomstig de agent-tip.
- Scoping van de "precies één bericht"-assertie op de issue-regel van de review-subtaak (i.p.v. de
  volledige messages-lijst) is goed onderbouwd tegen de gedocumenteerde cascade
  (documentation/merge/deploy-afsluiters die vlak na review-approved hun eigen meldingen sturen).
- Gerichte sanity-check (geen volledige testsuite, zoals reviewer-regels voorschrijven):
  `mvn -pl factory-common,softwarefactory -am test-compile` vanuit de repo-root — schoon, geen
  compile-fouten in de nieuwe/gewijzigde bestanden.
- Geen wijziging nodig aan `.factory/verification.yaml` of `docs/factory/*`: test-only, canonieke
  commando's (`repository-maven-verify` dekt `softwarefactory/` al) ongewijzigd, geen nieuw
  applicatiegedrag.
- Oordeel: akkoord, geen blockers/bugs gevonden.
