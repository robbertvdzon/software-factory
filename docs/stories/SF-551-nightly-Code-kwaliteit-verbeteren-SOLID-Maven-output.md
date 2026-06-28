# SF-551 - nightly: Code-kwaliteit verbeteren (SOLID, Maven-output)

## Story

nightly: Code-kwaliteit verbeteren (SOLID, Maven-output)

<!-- refined-by-factory -->

## Scope

Puur gedragsneutraal kwaliteits-/refactorwerk aan de Kotlin-broncode van de software-factory.

In scope:
- **SOLID, leesbaarheid en onderhoudbaarheid** van de main-broncode (primair `softwarefactory/src/main/kotlin`; eventueel `agentworker/src/main/kotlin` en `dashboard-backend/src/main/kotlin`). Concreet: betere naamgeving, dode code verwijderen, duplicatie wegwerken, te lange/complexe functies opsplitsen, verantwoordelijkheden ontvlechten.
- **Maven build-output opschonen**: warnings en deprecations die bij `mvn test` (de drie modules / root-aggregator) verschijnen oplossen waar dat gedragsneutraal kan.
- Per refactor-as geldt: alleen wijzigingen doorvoeren die herleidbaar gedragsneutraal zijn. Module-relatieve normen respecteren (bijv. `requireNotNull`/`error`/`require` als dominante norm in `softwarefactory`, maar `?: throw` blijft de norm binnen `dashboard-backend`).

Buiten scope / niet aanraken:
- De Flutter-frontend (`dashboard-frontend/`).
- E2e-/integratietests en testcode als vangnet — niet wijzigen.
- De detekt-ruleset (`quality/detekt.yml`) en `quality/run.sh` (beschermd meetpad; aanpassen zou de score gamen).
- Functioneel gedrag, publieke API-contracten en bewust gekozen domeinexcepties (bijv. `YouTrackApiException`, `GitHubClientException`, `ResponseStatusException`).

Een no-op met onderbouwende worklog-notitie (niets veilig/gedragsneutraal te verbeteren bij effort medium) is een geldig eindresultaat.

## Acceptance criteria

1. Alle bestaande tests blijven slagen: `mvn -f softwarefactory/pom.xml test` (en, indien geraakt, `agentworker`/`dashboard-backend`). Geen enkele test is aangepast om de refactor groen te krijgen.
2. Geen e2e-/integratietest is gewijzigd. Zou een wijziging dat vereisen, dan is het per definitie een gedragsverandering → story in error i.p.v. doorzetten.
3. Functioneel gedrag is aantoonbaar ongewijzigd: elke doorgevoerde wijziging is in de worklog herleidbaar tot een gedragsneutrale transformatie (rename/extract/dedup/dead-code-removal/deprecation-fix).
4. De kwaliteitsmeetlat verslechtert niet: na afronding is de detekt-SCORE uit `quality/run.sh` (`qualityrun/quality-score.json`) gelijk of lager dan de uitgangsstand. Onderdrukkingen (`@Suppress`/`@SuppressWarnings`/`detekt:disable`/`ktlint-disable`) tellen mee — een bevinding "wegzwijgen" levert netto niets op en is niet toegestaan.
5. Opgeloste Maven-warnings/deprecations zijn in de worklog benoemd (welke warning, hoe opgelost) of expliciet als "geen veilig op te lossen warnings" gemarkeerd.
6. De diff raakt geen beschermde paden (`quality/detekt.yml`, `quality/run.sh`, e2e-tests, Flutter-frontend) en geen `docs/stories/...` behalve de eigen worklog.
7. Er is een eigen review-stap uitgevoerd en `docs/stories/worklog/SF-551-worklog.md` is bijgewerkt met scan-bevindingen per as, doorgevoerde wijzigingen (met gedragsneutraliteits-argument), testresultaten en de detekt-score voor/na.

## Aannames

- De story draait silent/autonoom (nightly): bij echte twijfel of een wijziging gedrag verandert wordt die niet doorgevoerd; blijft er een inhoudelijke blokkade, dan gaat de story in error i.p.v. te wachten op een mens.
- "Gedragsneutraal" wordt strikt geïnterpreteerd: bij twijfel niet doen. Scope-grootte wordt afgestemd op effort medium — niet de hele codebase herschrijven, maar een afgebakende, verdedigbare set verbeteringen.
- De uitgangswaarde van de detekt-score wordt aan het begin van de implementatie vastgelegd door `quality/run.sh` te draaien, zodat criterium 4 toetsbaar is (er is nu nog geen `qualityrun/`-baseline gecommit).
- Normen worden uit de codebase zelf afgeleid (dominante variant per module/as), conform de conventies in `docs/factory/development.md`; bestaande bewuste uitzonderingen blijven ongemoeid.

## Eindsamenvatting

## Eindsamenvatting — SF-551: nightly Code-kwaliteit verbeteren (SOLID, Maven-output)

**Wat is gebouwd**
Gedragsneutrale kwaliteitsrefactor van de Kotlin-broncode van de software-factory. Twee gerichte verbeteringen in de main-broncode van `softwarefactory`:

1. **`SecretsEnvLoader.stripSurroundingQuotes()`** — de complexe quote-conditie opgesplitst in twee leesbare lokale variabelen (`doubleQuoted` / `singleQuoted`). Lost de detekt-`ComplexCondition`-bevinding op.
2. **`ClaudeAssistantClient.jsonObjectCandidates()`** — een single-line `if … ; …` in een `when`-tak meerregelig uitgeschreven. Verwijdert een detekt-`EmptyElseBlock` (parser-artefact).

Beide zijn herleidbaar tot gedragsneutrale transformaties (extract-variable / whitespace-reformat); identieke logica, takken en returnwaarden.

**Welke keuzes zijn gemaakt**
- Bewust **kleinschalig** gehouden (effort medium, nightly = bij twijfel niet doen). De grote detekt-categorieën (MaxLineLength, MagicNumber, ReturnCount, LongParameterList) zijn niet aangeraakt: niet betrouwbaar gedragsneutraal-goedkoop te fixen zonder grote churn/risico.
- `UnusedParameter` op `AiRouting.kt` (`role`) bewust ongemoeid — hoort bij de publieke `resolve()`-API-vorm.
- Géén onderdrukkingen (`@Suppress` e.d.) gebruikt; suppressions blijven 0.

**Resultaat / meetlat**
- Detekt-score: **508 → 506** (criterium 4: meetlat verslechtert niet). ✔
- Maven build is **warning-vrij** over alle 3 modules → geen op te lossen warnings/deprecations. ✔

**Wat is getest**
- `SecretsEnvLoaderTest`: 14/14 groen.
- Volledige suite `mvn -f softwarefactory/pom.xml test`: **0 Failures**. De 25 Errors zijn allemaal omgevingsgebonden (Testcontainers/Docker ontbreekt op de runner) en op een schone `main` identiek reproduceerbaar → geen regressie.
- Geen enkele test gewijzigd; geen e2e-/integratietest aangeraakt. Geverifieerd door reviewer (SF-552) én tester (SF-553), beide akkoord.

**Wat bewust niet is gedaan**
- Geen aanpak van de grote, riskante detekt-categorieën.
- Geen wijziging aan beschermde paden (`quality/detekt.yml`, `quality/run.sh`, e2e-tests, Flutter-frontend) of specs (geen gedragswijziging).

Alle 7 acceptatiecriteria zijn voldaan.

```json
```
