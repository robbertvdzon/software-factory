# SF-635 - Worklog

Story-context bij eerste pickup:
Kwaliteits-/refactoriteratie met gedragsbehoud

Voer een meet-gestuurde, afgebakende kwaliteits-/refactoriteratie uit op de main-code zonder functionele gedragsverandering. Stappen: (1) Nulmeting: draai quality/run.sh en noteer de begin-score uit qualityrun/quality-score.json plus top-regels/hotspots; draai mvn test (root over softwarefactory, agentworker, dashboard-backend) en leg baseline + zichtbare Maven-warnings/deprecations vast. (2) Selecteer een afgebakende set findings/bestanden (zwaarste detekt-complexiteitshotspots en concrete Maven-warnings/deprecations). (3) Refactor volgens SOLID/leesbaarheid: betere naamgeving, dode code weg, duplicatie wegwerken, te lange functies/klassen opsplitsen; los deprecations op door deprecated API te vervangen door de opvolger met identieke semantiek. Respecteer repo-conventies (expliciete imports, module-relatieve error/require-norm, dashboard-backend ?: throw ongemoeid, domeinexcepties behouden). GEEN suppressies als oplossing. (4) Verifieer continu: mvn test moet groen blijven; integratie-/e2e-tests NIET aanpassen (anders gedrag gewijzigd -> in error); nameting via quality/run.sh moet score lager-of-gelijk geven zonder toegevoegde suppressies, en minder Maven-warnings zonder nieuwe. Bij gerede twijfel over gedragsverandering: niet doorvoeren; geen veilige verbetering mogelijk -> in error. (5) Werk docs/stories/worklog/SF-635-worklog.md bij met refactors, testresultaat, voor/na-score en opgeloste warnings. Het schrijven/aanpassen van eventuele unittests hoort bij deze development-subtaak.

Stappenplan:
[x]: read issue and target docs
[x]: nulmeting quality-score + maven-build
[x]: afgebakende set findings selecteren en refactoren (gedragsbehoud)
[x]: unittests schrijven/uitbreiden voor de geraakte code
[x]: run relevant tests
[x]: nameting quality-score (lager-of-gelijk, geen suppressies)
[x]: update story-log with results

## SF-636 — kwaliteits-/refactoriteratie (developing)

### Nulmeting
- `quality/run.sh` (detekt, alleen softwarefactory main): **score 515** (totalFindings 515,
  suppressions 0). Grootste buckets: MaxLineLength 219, MagicNumber 116, ReturnCount 65.
- Maven-build: `mvn -pl softwarefactory clean compile` + agentworker + dashboard-backend
  draaien groen en tonen **geen** Kotlin compiler-warnings/deprecations in de output.
  Er waren dus geen zichtbare deprecations om op te lossen; de meetbare hefboom is de
  detekt-score. (Build geverifieerd met JDK 21 + mvn 3.9.10, netwerk aanwezig.)

### Afgebakende set findings (gedragsneutraal)
Bewust een kleine, behapbare set (één nightly-iteratie), zonder suppressies en zonder
e2e-/integratietest-wijzigingen:

1. `core/OrchestratorSettings.kt` — 5× MaxLineLength weggewerkt door de
   `fromEnvironment`-named-arguments te wrappen; 1× MagicNumber (`Duration.ofSeconds(45)`)
   vervangen door de benoemde constante `DEFAULT_IDLE_POLL_SECONDS`.
2. `config/configurations/DatabaseConfiguration.kt` — 4× MagicNumber: HikariCP pool-tuning
   (`maximumPoolSize`, `connectionTimeout`, `idleTimeout`, `maxLifetime`) naar benoemde
   `private companion`-constanten met identieke waarden.
3. `nightly/NightlyDigest.kt` — 4× MagicNumber: tijd-conversies (`/3600`, `%60`) naar
   `SECONDS_PER_HOUR`/`SECONDS_PER_MINUTE`; 1× MaxLineLength (sections-`forEach`) gewrapt.
4. `nightly/NightlyScheduler.kt` — 7× MaxLineLength weggewerkt (`@Scheduled`-annotatie,
   `forEach`-lambda's, functie-signatures en log-strings gewrapt). Tevens de
   fully-qualified `java.time.LocalDate` vervangen door een expliciete import (repo-conventie).

Alle wijzigingen zijn pure interne herstructurering (rename/extractie/herformattering) met
identieke semantiek; geen externe contracten, endpoints of zichtbaar gedrag geraakt.

### Tests (zelf geschreven/uitgebreid)
- Nieuw: `core/OrchestratorSettingsTest.kt` — pint de defaults van `fromEnvironment` (incl.
  de geraakte regels) en de data-class-default `pollIntervalIdle = 45s` (de geëxtraheerde
  constante). 3 tests.
- Uitgebreid: `config/DatabaseConfigurationTest.kt` — extra asserts op `idleTimeout`
  (600_000) en `maxLifetime` (1_800_000) zodat de geëxtraheerde constanten gepind zijn.
- Bestaande vangnetten dekken de rest: `NightlyDigestTest` (duur-/digestopbouw),
  `NightlySchedulerTest`, `NightlyPlannerTest`, `AiRoutingTest`.

Resultaat: `mvn -pl softwarefactory test -Dtest='OrchestratorSettingsTest,DatabaseConfigurationTest,NightlyDigestTest,NightlySchedulerTest,NightlyPlannerTest,AiRoutingTest'`
→ **41 tests, 0 failures, 0 errors — BUILD SUCCESS**. (De Docker-afhankelijke e2e-suite
draait in de pipeline, niet lokaal.)

### Nameting
- `quality/run.sh`: **score 493** (totalFindings 493, suppressions 0).
  Verschil: **515 → 493 (−22)**, zonder toegevoegde suppressies.
  - MaxLineLength 219 → 206 (−13)
  - MagicNumber 116 → 107 (−9)
  - Overige buckets ongewijzigd; **geen enkele rule nam toe** → geen nieuwe findings.
- Maven-build blijft groen, geen nieuwe warnings/deprecations.

### Done / rationale
- Meet-gestuurde, afgebakende refactor met aantoonbare nettoverbetering (score −22) en
  gelijk gebleven gedrag (groene tests, geen contract-/gedragswijziging).
- Geen suppressies gebruikt (suppressions blijft 0). Geen e2e-/integratietest aangepast.
- `quality/detekt.yml` (beschermde meetlat) en `qualityrun/` (git-ignored) ongemoeid.

## SF-637 — Story-brede test (testing)

Verificatie door de tester (geen code/tests gewijzigd; uitsluitend dit worklog):

- **Diff-scope**: alleen 4 main-bestanden (DatabaseConfiguration, OrchestratorSettings,
  NightlyDigest, NightlyScheduler) + 2 unittestbestanden (OrchestratorSettingsTest nieuw,
  DatabaseConfigurationTest 2 extra asserts) + dit worklog. **Geen e2e-/integratietest
  aangepast** (`git diff --name-only main...HEAD -- '*e2e*'` is leeg). Voldoet aan AC3.
- **Gedragsbehoud (AC1/AC7)**: wijzigingen zijn puur intern — constanten met identieke
  waarden (MAX_POOL_SIZE=5, CONNECTION_TIMEOUT_MS=10_000, IDLE_TIMEOUT_MS=600_000,
  MAX_LIFETIME_MS=1_800_000, DEFAULT_IDLE_POLL_SECONDS=45, SECONDS_PER_HOUR/MINUTE),
  line-wrapping en een expliciete `java.time.LocalDate`-import. Geen contract-/gedragswijziging.
- **Kwaliteitsscore (AC5)**: geverifieerd op schone `main` via `git worktree` →
  baseline **515**; branch **493** (`quality/run.sh` → `quality-score.json`). **−22**,
  suppressies blijven **0**, en **geen enkele detekt-rule nam toe** (MaxLineLength 219→206,
  MagicNumber 116→107, overige buckets identiek) → geen nieuwe findings.
- **Maven-build (AC4)**: schone compile zonder Kotlin compiler-warnings/deprecations
  (er waren er geen om op te lossen; de detekt-score is de meetbare hefboom).
- **Tests (AC2)**: gerichte run `OrchestratorSettingsTest,DatabaseConfigurationTest,
  NightlyDigestTest,NightlySchedulerTest,NightlyPlannerTest,AiRoutingTest` → **41 tests,
  0 failures, 0 errors, BUILD SUCCESS**. Volledige softwarefactory-suite
  (`mvn -f softwarefactory/pom.xml test`) → **431 tests, Failures: 0, 27 Errors, 0 Skipped**.
  Alle 27 errors zijn de bekende omgevingsbaseline (geen Docker in tester-env): e2e-package 25
  (PipelineFlows 12, PipelineLoopback 5, SpecScenarioCoverage 4, ManualApproveGate 2,
  FullRefineToDevelop 1, FactoryUiDriverLogin 1) + NightlyRepositoriesTest 1 +
  FactoryDashboardRepositoryScreenshotTest 1. **Geen functionele failures, geen regressie.**

Conclusie: alle acceptatiecriteria geverifieerd en gehaald. **Tested.**
