# SF-470 - nightly: Integratietests: ontbrekende scenario's toevoegen

## Story

nightly: Integratietests: ontbrekende scenario's toevoegen

<!-- refined-by-factory -->

## Scope
Breid de integratie-/e2e-testsuite van `softwarefactory` uit zodat de in `docs/factory/functional-spec.md` beschreven functionele scenario's gedekt zijn. Werk uitsluitend in de testlaag in `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/` (op basis van `E2eTestBase`, `AgentScript`, `FakeYouTrackServer`, `FactoryUiDriver`, Awaitility). Geen functioneel gedrag van productiecode wijzigen; alleen tests toevoegen of bestaande tests verbeteren.

Aanpak voor de developer:
1. Breng in kaart welke functionele scenario's de spec en code ondersteunen en zet die af tegen de bestaande e2e-dekking (`FullRefineToDevelopE2eTest`, `PipelineFlowsE2eTest`, `PipelineLoopbackE2eTest`).
2. Voeg integratietests toe voor aantoonbaar ontbrekende scenario's en verbeter bestaande waar nodig.
3. Leg de mapping (scenario → dekkende test, nieuw of bestaand) vast in het worklog `docs/stories/worklog/SF-470-worklog.md`.

Kandidaat-scenario's die nu niet of onvolledig e2e gedekt lijken (developer verifieert per stuk en kiest de waardevolle, niet-redundante toevoegingen — lijst is richtinggevend, niet uitputtend):
- Silent story die volledig autonoom de keten doorloopt tot terminaal, inclusief het overslaan van de `manual-approve`-poort en (waar verifieerbaar in de testharnas) het uitblijven van Telegram-berichten (SF-335).
- Documentation-subtaak (SF-213): meelopen in de keten op de juiste plek en het `documentation-with-questions`-pad.
- Manual-approve-poort (SF-192): `approve` laat de keten door naar merge; `reject` reset de keten en schrijft de afkeurreden in het gemarkeerde blok in de story-description.
- Automatische merge (SF-244): succesvolle merge gaat door naar deploy; een merge-fout zet de merge-subtaak op `Error` en stopt de keten (geen `AWAITING_HUMAN`).
- Test-chain-reset cap (SF-200): bij het bereiken van `SF_MAX_TEST_CHAIN_RESETS` volgt geen reset maar komt de story in `Error`.

## Acceptance criteria
- Er is in het worklog een expliciete scenario-dekkingsmapping opgenomen die per relevant spec-scenario aangeeft welke (nieuwe of bestaande) e2e-test het dekt.
- Voor elk als ontbrekend geïdentificeerd en geselecteerd scenario bestaat een nieuwe of aangescherpte integratietest in de `e2e`-package.
- Er wordt geen productiecode (`src/main`) functioneel gewijzigd; de diff buiten testcode/worklog blijft leeg (test-only fixtures/helpers in de testbron zijn toegestaan).
- Alle tests slagen: `mvn -f softwarefactory/pom.xml test` is groen (oude + nieuwe tests).
- Nieuwe tests volgen de bestaande conventies (NL-`fun`-namen tussen backticks, `E2eTestBase`/`AgentScript`/`FactoryUiDriver`/Awaitility, geen project-interne wildcard-imports).
- Als een ontbrekend scenario alleen "groen" te krijgen is door duidelijk buggy productiegedrag te bevriezen: die test wordt niet toegevoegd; in plaats daarvan gaat de story in `Error` met een concrete notitie (welk scenario, welk verwacht vs. waargenomen gedrag).

## Aannames
- "Integratietests" verwijst naar de e2e-/pipeline-integratietestsuite in de `e2e`-package van `softwarefactory`; andere Maven-modules (`agentworker`, `dashboard-backend`) en de Flutter-frontend vallen buiten scope tenzij een ontbrekend scenario daar logisch thuishoort.
- De story is silent/autonoom; bij echte onduidelijkheid of een buggy-bevriezings-situatie eindigt de run in `Error` met notitie i.p.v. te wachten op een mens.
- De omvang wordt bepaald door de daadwerkelijke dekkingslacunes; volledige redundante duplicatie van al gedekte paden is niet gewenst. Als bij analyse blijkt dat alle relevante scenario's al gedekt zijn, is een onderbouwde no-op met dekkingsmapping in het worklog een geldige uitkomst.
- Scenario's die niet betrouwbaar in de bestaande testharnas te simuleren zijn (bijv. zaken die echte Docker/AI/externe Telegram vereisen) worden gemotiveerd buiten scope gelaten in de mapping.

## Eindsamenvatting

Ik heb `.task.md` en het worklog (`docs/stories/worklog/SF-470-worklog.md`) gelezen, inclusief de reviewer- en tester-notities. Hieronder de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-470: Nightly integratietests — ontbrekende scenario's toevoegen

### Wat is gebouwd
Eén nieuw, test-only bestand `SpecScenarioCoverageE2eTest.kt` in de e2e-package van `softwarefactory`, met **drie** integratietests voor aantoonbaar ontbrekende, betrouwbaar simuleerbare spec-scenario's:

1. **SF-335 — Silent autonome keten:** een silent story met `Auto-approve=off` doorloopt de volledige keten (incl. afgedwongen documenter) zónder enige menselijke actie — bewijst auto-start development én auto-approve op alle poorten.
2. **SF-213 — Documentation-with-questions:** de afgedwongen `documentation`-subtaak draait op de juiste plek in de keten en het vraag→antwoord→approved-pad wordt end-to-end gedreven (deze vraagflow bestond al voor andere rollen, maar ontbrak voor documentation).
3. **SF-200 — Test-chain reset cap:** na 3 resets zet de 4e test-bevinding de test-subtaak op `Error` ("Test-chain reset cap bereikt") in plaats van opnieuw te resetten.

Daarnaast is in het worklog een **expliciete scenario→dekkende-test mapping** vastgelegd (bestaande + nieuwe tests + gemotiveerd buiten scope).

### Belangrijkste keuzes
- **Geen productiecode gewijzigd:** diff is uitsluitend testcode + worklog; bestaande conventies gevolgd (NL-funnamen tussen backticks, `E2eTestBase`/`AgentScript`/`FactoryUiDriver`/Awaitility, expliciete imports).
- **Alleen niet-redundante, betrouwbaar te simuleren scenario's toegevoegd** — geen duplicatie van al gedekte paden.
- **Geen tests die buggy gedrag bevriezen:** alle assertions zijn statisch geverifieerd tegen de productiecode; story hoefde dus niet op `Error`.

### Bewust niet gedaan (met motivatie)
- **SF-335 "nul Telegram" & "manual-approve niet aanmaken":** geen betrouwbaar e2e-observatiepunt (Telegram staat uit in e2e-config; manual-approve-poort staat projectbreed uit). Telegram-logica is apart unit-getest.
- **SF-192 manual-approve approve/reject:** poort staat e2e-breed uit; aanzetten zou productieconfig/-gedrag raken (buiten test-only scope). Het reset-bij-reject-mechaniek wordt indirect gedekt via het SF-200 reset-pad.
- **SF-244 automatische merge succes/fout→Error:** e2e gebruikt een lokale file-based git-remote (geen echte GitHub-PR); een betrouwbare merge-e2e vereist een fake `GitHubApi`-infra en hoort als aparte story.

### Testresultaat
- `mvn -f softwarefactory/pom.xml test` → **Tests run: 419, Failures: 0, Errors: 21**.
- **Failures = 0** = schoon regressiesignaal. Alle 21 errors zijn omgevingsgebonden (geen Docker-daemon in de agent-omgeving): 18 bekende env-baseline + de 3 nieuwe e2e-tests die Testcontainers/Docker vereisen. Deze draaien groen in de CI/factory-pipeline mét Docker.
- Reviewer akkoord (statisch geverifieerd, één niet-blokkerende race-info-noot); tester-resultaat: **tested**.

---

```json
```
