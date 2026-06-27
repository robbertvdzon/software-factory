# SF-1 - Eindsamenvatting

## Story

Eindsamenvatting

## Eindsamenvatting

Ik heb `.task.md`, het volledige worklog (SF-2 t/m SF-8) en de bestaande eindsamenvatting gelezen. Hieronder de compacte eindsamenvatting voor de PO.

---

## SF-1 — Add integration test — Eindsamenvatting

### Wat is gebouwd
Een **end-to-end integratietest** die de volledige Software-Factory-pijplijn in één test aanstuurt: van een story met label `ai-refinement`, via de vraag-en-antwoord-flow over de web-UI, tot álle subtaken approved zijn. De **echte** Spring-applicatie draait (orchestrator-poller, completion-poller, web-controllers, state-machine); alleen de drie buitenranden zijn vervangen door test-dubbels.

Opgeleverd (uitsluitend test-sources, package `…e2e`):
- **`FakeYouTrackServer` + `FakeYouTrackState`** — stateful mini-YouTrack over echte HTTP; de échte `YouTrackClient` praat er normaal mee (issues, tags, fields, comments, "eyes" processed-marker).
- **`TestAgentRuntime` + `AgentScript`** — scripted agent-runtime i.p.v. Docker+LLM: schrijft deterministisch een `agent-result.json` dat de echte completion-poller oppakt.
- **`E2eTestConfig`** — `@Primary`-overrides voor config/secrets, agent-runtime en YouTrack, op een Testcontainer-Postgres.
- **`FactoryUiDriver` + `AwaitDsl`** — speelt "de gebruiker" (login + echte POST-endpoints) en de async-kern (Awaitility-polling).
- **`FullRefineToDevelopE2eTest`** — het volledige scenario, plus losse harness-tests per bouwstap (`FakeYouTrackServerTest`, `TestAgentRuntimePollerTest`, `FactoryUiDriverLoginTest`).
- Test-scope deps in `pom.xml` (awaitility, testcontainers) + `src/test/resources/application.yml`.

### Belangrijkste keuzes
- **Stateful mock over HTTP** i.p.v. een in-memory fake-bean, zodat ook de HTTP-serialisatie en het echte completion-pad gedekt worden.
- **Scripted runtime schrijft een echt `agent-result.json`** → het productie-completion-pad loopt er ongewijzigd overheen; geen mock van completion nodig.
- **`Auto-approve=on`** in de test: de orchestrator zet de goedkeuringsstappen zelf, zodat de test enkel de écht menselijke acties stuurt (twee vragen beantwoorden + "start developing").
- **Incrementele bouwvolgorde** (SF-2 t/m SF-6): elke bouwstap is los testbaar.

### Wat is getest
- De unit/poller/orchestrator-suite draait groen: **175 tests, 0 failures** met `mvn test`.
- Een tijdens het testen gevonden **bug is opgelost**: `TestAgentRuntime` serialiseerde afgeleide getters (`isSuccessful`/`totalTokens`) van het productiemodel, waardoor de echte poller (`FAIL_ON_UNKNOWN_PROPERTIES`) de JSON niet kon teruglezen (0 completions). Fix: die velden worden nu gestript vóór het wegschrijven (test-local, geen productiecode). `TestAgentRuntimePollerTest` daarna 2/2 groen.

### Bewust niet gedaan / aandachtspunten
- **Cucumber-laag (fase 2 uit het plan)** bewust buiten scope gelaten.
- **Geen productiecode gewijzigd** voor SF-1; alleen test-sources, test-deps en worklog. Geen echte secrets (alleen dummy test-credentials).
- **De twee Docker-afhankelijke e2e-tests** (`FactoryUiDriverLoginTest`, `FullRefineToDevelopE2eTest`) konden in de test-omgeving **niet groen bevestigd** worden — Testcontainers-Postgres vereist een Docker-daemon die in de runner ontbrak. Dit is de kerndeliverable en moet in een Docker-enabled CI/preview groen draaien.
- **Branch-scope:** de branch `ai/SF-1` draagt ook niet-SF-1 productiecode mee (SF-10/11/12). Dat zit in de PR-diff t.o.v. `master`, maar valt buiten deze story.

---

Twee punten vragen een PO-beslissing vóór merge: de CI-Docker-voorwaarde voor de e2e-tests en de branch-/PR-scope (meeliftende SF-10/11/12-commits).
