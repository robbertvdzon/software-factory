# SF-551 - Worklog

Story-context bij eerste pickup:
Gedragsneutrale kwaliteits-/refactorverbeteringen (SOLID + Maven-output)

Doel: puur gedragsneutraal kwaliteits-/refactorwerk aan de Kotlin-main-broncode van de
software-factory (primair `softwarefactory/src/main/kotlin`). Detekt-score mag niet
verslechteren, geen tests aanpassen, geen beschermde paden raken, geen onderdrukkingen
(`@Suppress` e.d.) als "oplossing". Een onderbouwde no-op is een geldig eindresultaat.

## Stappenplan
[x]: read issue and target docs
[x]: baseline vastleggen (detekt-score + Maven-warnings)
[x]: scannen per refactor-as en gedragsneutrale wijzigingen doorvoeren
[x]: relevante tests draaien
[x]: eigen review-stap
[x]: story-log bijwerken met resultaten

## Baseline (uitgangsstand)

### Detekt (`quality/run.sh` → `qualityrun/quality-score.json`)
- **Score voor: 508** (totalFindings 508, suppressions 0).
- Verdeling (top): MaxLineLength 214, MagicNumber 116, ReturnCount 65, LongParameterList 31,
  TooManyFunctions 21, CyclomaticComplexMethod 17, TooGenericExceptionCaught 14, LongMethod 10,
  SpreadOperator 6, NestedBlockDepth 6, LoopWithTooManyJumpStatements 3, LargeClass 2,
  UnusedParameter 1, EmptyElseBlock 1, ComplexCondition 1.
- De grote categorieën (MaxLineLength/MagicNumber/ReturnCount/LongParameterList/…) zijn niet
  betrouwbaar gedragsneutraal-goedkoop te fixen bij effort medium zonder grote churn/risico;
  daar is bewust niet aan getrokken (nightly: bij twijfel niet doen). De goedkope, eerder
  geoogste wins (UseCheckOrError, UseRequire, VariableNaming, UnusedPrivateProperty,
  MatchingDeclarationName, …) zijn in vorige stories al verwerkt — vandaar dat de baseline al
  op 508 staat (i.p.v. de oudere 518).

### Maven build-output
- `mvn -f pom.xml clean test-compile` over de **root-aggregator** (alle 3 modules:
  softwarefactory, agentworker, dashboard-backend + root) → **BUILD SUCCESS, 0 `[WARNING]`-regels,
  0 Kotlin-compilerwaarschuwingen (`w:`)**.
- Conclusie criterium 5: **geen veilig op te lossen Maven-warnings/deprecations** — de build is
  warning-vrij. Niets te doen op deze as.

## Doorgevoerde wijzigingen (gedragsneutraal)

1. **ComplexCondition fix** — `config/services/SecretsEnvLoader.kt`,
   `String.stripSurroundingQuotes()`.
   - De `if`-conditie `((startsWith("\"") && endsWith("\"")) || (startsWith("'") && endsWith("'")))`
     (detekt-complexiteit 4, drempel 4) is opgesplitst in twee leesbare lokale vals
     `doubleQuoted` / `singleQuoted`, waarna `if (doubleQuoted || singleQuoted)`.
   - **Gedragsneutraliteit**: identieke booleaanse evaluatie (extract-variable), zelfde takken,
     zelfde return-waarden. Verbetert leesbaarheid en haalt de ComplexCondition-bevinding weg.

2. **EmptyElseBlock fix (detekt false-positive op single-line `if` in `when`-tak)** —
   `telegram/ClaudeAssistantClient.kt`, `jsonObjectCandidates()`.
   - De `when`-tak `'{' -> { if (depth == 0) start = i; depth++ }` is uitgeschreven naar
     meerregelig:
     ```
     '{' -> {
         if (depth == 0) start = i
         depth++
     }
     ```
   - Detekt rapporteerde hier een (niet-bestaande) "empty else block"; het was een
     parser-artefact op de single-line `if … ; …`-vorm. De meerregelige vorm is **byte-voor-byte
     gelijk gedrag** (de `;` scheidde al twee statements) en de bevinding verdwijnt.

Bewust **niet** aangeraakt: `UnusedParameter` op `core/AiRouting.kt:39` (`role`) — die parameter
hoort bij de publieke `resolve()`-API-vorm en wordt opzettelijk niet gewijzigd (zie agent-tip
`detekt-baseline-and-runner`).

## Detekt-score na afronding
- **Score na: 506** (totalFindings 506, suppressions 0). → **508 → 506**, dus de meetlat
  verslechtert niet (criterium 4 voldaan). Verdwenen: ComplexCondition (1) en EmptyElseBlock (1).
  Geen nieuwe bevindingen en **geen onderdrukkingen toegevoegd** (suppressions blijft 0).

## Testresultaten
- `mvn -f softwarefactory/pom.xml test -Dtest=SecretsEnvLoaderTest` → **14/14 groen**
  (dekt de gewijzigde `SecretsEnvLoader`).
- Brede run `mvn -f softwarefactory/pom.xml test -Dtest='!ModulithArchitectureTest,!AgentResultFileCompletionPollerTest'`
  (de twee bekende, niet door deze story veroorzaakte main-issues uitgesloten) →
  **419 tests, Failures: 0**. De 25 Errors zijn allemaal `Could not find a valid Docker
  environment` (Testcontainers-afhankelijke e2e-/repo-tests) — een omgevingslimiet van de
  developer-runner (geen Docker), niet veroorzaakt door deze wijziging; die draaien in CI.
- Geen enkele test is gewijzigd. Geen e2e-/integratietest aangepast.

## Eigen review-stap
- `git diff --stat`: alleen 2 main-source-bestanden gewijzigd (+ deze worklog). Geen beschermde
  paden geraakt (`quality/detekt.yml`, `quality/run.sh`, e2e-tests, `dashboard-frontend/`,
  andere `docs/stories/…`). `qualityrun/` is git-ignored en komt niet in de diff.
- Beide wijzigingen herleidbaar tot gedragsneutrale transformaties (extract-variable /
  whitespace-reformat). Module-dominante normen (`require`/`requireNotNull` in softwarefactory)
  gerespecteerd; bewuste domeinexcepties ongemoeid.

## Specs
- Geen functionele/UX-/technische gedragswijziging → geen aanpassing nodig aan
  `docs/factory/functional-spec.md`, `technical-spec.md` of `ux/`.
