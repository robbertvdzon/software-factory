# SF-1360 - Verwijder overbodige self-package wildcard-imports in softwarefactory (core.contracts/telegram/runtime)

## Story

Verwijder overbodige self-package wildcard-imports in softwarefactory (core.contracts/telegram/runtime)

<!-- refined-by-factory -->

## Scope

Verwijder in `softwarefactory/src/main/kotlin` alle wildcard-imports die exact het eigen package van het bestand targeten (bv. een bestand in package `core.contracts` dat zelf `import nl.vdzon.softwarefactory.core.contracts.*` bevat). Dit betreft minstens 37 bestanden, geconcentreerd in:

- `core/contracts`
- `telegram/clients`, `telegram/services`, `telegram/repositories`
- `runtime/services`, `runtime/models`, `runtime/types`

Alleen de exacte self-package-wildcard-regel wordt verwijderd — geen andere imports aanpassen, geen overige code wijzigen. Kotlin maakt same-package symbolen automatisch zichtbaar, dus dit is pure dode-code-verwijdering zonder functionele impact.

Nadrukkelijk **buiten scope**: de resterende ~45 cross-package wildcard-imports (vooral `telegram.*`/`runtime.*` waar echte types uit een ander package gebruikt worden). Die vereisen per bestand het uitschrijven van de daadwerkelijk gebruikte types en horen bij een aparte, latere story.

## Acceptance criteria

- Alle geïdentificeerde self-package wildcard-import-regels in de genoemde packages zijn verwijderd.
- Geen enkele andere regel/import/logica is gewijzigd (pure verwijdering, geen refactor).
- `mvn -f softwarefactory/pom.xml test` slaagt (gedrag ongewijzigd).
- `quality/run.sh` draait succesvol.
- Het aantal `WildcardImport`-findings in `quality/baselines/plan-07-ratchet.json` daalt (mag nooit stijgen).
- Cross-package wildcard-imports (telegram.*/runtime.* met echte externe types) blijven ongemoeid.

## Aannames

- "Zelfde package" wordt letterlijk geïnterpreteerd: de wildcard-importregel matcht exact de package-declaratie bovenaan hetzelfde bestand.
- Als na verwijdering blijkt dat een bestand toch een niet-same-package symbool via die wildcard binnenhaalde (dus de story-aanname "pure verwijdering, geen functionele wijziging mogelijk" niet klopt voor een specifiek bestand), mag de developer dat ene bestand overslaan en dit expliciet melden, in plaats van de hele story te blokkeren.
- De ratchet-baseline (`quality/baselines/plan-07-ratchet.json`) wordt bijgewerkt als onderdeel van deze story indien nodig, zodat de daling wordt vastgelegd.

## Eindsamenvatting

I heb voldoende context uit `.task.md` en het worklog `docs/stories/worklog/SF-1360-worklog.md` om de eindsamenvatting te maken.

## Eindsamenvatting SF-1360 — Verwijder overbodige self-package wildcard-imports

**Gebouwd:**
In `softwarefactory/src/main/kotlin` zijn in 37 bestanden self-package wildcard-import-regels verwijderd (regels van de vorm `import <eigen package>.*`), verspreid over `core/contracts` (24×), `runtime/models`+`runtime/types` (2×) en `telegram/clients`+`telegram/services`+`telegram/repositories` (11×). Dit zijn functioneel overbodige imports, omdat Kotlin symbolen binnen hetzelfde package automatisch zichtbaar maakt.

**Keuzes:**
- Puur mechanische verwijdering: per bestand alleen die ene wildcard-regel weggehaald, geen enkele andere regel/import/logica aangeraakt (bevestigd: 37 files changed, 37 deletions, 0 insertions in de kotlin-bronbestanden).
- Cross-package wildcard-imports (zoals `telegram.*`/`runtime.*` waar wél externe types uit een ander package gebruikt worden) zijn bewust buiten scope gelaten — die vergen per bestand het uitschrijven van de daadwerkelijk gebruikte types en horen bij een latere, aparte story.
- De ratchet-baseline (`quality/baselines/plan-07-ratchet.json`) is volledig geregenereerd; het aantal `WildcardImport`-findings daalde van 110 naar 45 in de `softwarefactory`-module.

**Getest:**
- `mvn verify` vanaf de repo-root: BUILD SUCCESS, alle modules groen, 0 failures/errors (incl. 70 surefire-rapporten door de reviewer gecontroleerd, alle 0 Failures/Errors).
- `quality/run.sh`: exitcode 0, `"ok": true`, 0 nieuwe/ambigue findings, 0 nieuwe suppressions.
- Reviewer heeft de diff onafhankelijk geverifieerd (scope, cross-package wildcards ongemoeid, testresultaten, baseline-diff) en goedgekeurd.

**Bewust niet gedaan:**
- Geen documentatie-aanpassingen: `technical-spec.md`/`development.md` verbieden project-interne wildcard-imports al, dus deze story brengt de code alleen dichter bij de bestaande norm zonder die norm te wijzigen.
- Geen bestanden overgeslagen: alle 37 verwijderde imports bleken puur redundant.
- Een pre-existing, story-onafhankelijk kwaliteitsprobleem (verouderde blocking-rule-findings zoals Cyclomatic/LongMethod/MaxLineLength in bv. `AgentPromptContracts.kt`) is expliciet benoemd als niet-opgelost en niet aan deze story toegeschreven.
