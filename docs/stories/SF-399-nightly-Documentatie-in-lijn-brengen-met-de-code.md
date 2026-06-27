# SF-399 - nightly: Documentatie in lijn brengen met de code

## Story

nightly: Documentatie in lijn brengen met de code

<!-- refined-by-factory -->

## Scope

Controleer de volledige documentatie van de repository (met name `docs/factory/` — README, functional-spec, technical-spec, deployment, development, agent-instructies en UX-docs — plus README's elders in de repo) en breng deze in lijn met de huidige broncode.

- Alle functionaliteit die in de code aanwezig is, moet correct en volledig in de documentatie terugkomen.
- Documentatie die de code beschrijft die niet meer bestaat of inmiddels anders werkt, wordt gecorrigeerd.
- `docs/stories` mag als input gebruikt worden om te begrijpen *waarom* functionaliteit bestaat, maar wordt niet gewijzigd.
- Alleen documentatie (.md / docs-bestanden) wordt aangepast. **Geen** broncode, tests of andere implementatiebestanden worden gewijzigd.
- Als de bestaande documentatie al volledig in sync is, worden geen wijzigingen gemaakt en wordt dat expliciet gerapporteerd in het worklog.

Buiten scope:
- Functionele wijzigingen aan de applicatie.
- Aanpassingen aan `docs/stories` (read-only input).

## Acceptance criteria

- De broncode (productiecode en tests) is ongewijzigd; `git diff main...HEAD` toont uitsluitend wijzigingen in documentatiebestanden (en het story-worklog).
- Geconstateerde afwijkingen tussen documentatie en code zijn in de documentatie gecorrigeerd zodat de docs de actuele functionaliteit accuraat beschrijven.
- Functionaliteit die in de code aanwezig is maar in de documentatie ontbrak, is toegevoegd aan de relevante docs.
- Het worklog (`docs/stories/worklog/SF-399-worklog.md`) beschrijft welke documenten zijn gecontroleerd, welke afwijkingen zijn gevonden en welke aanpassingen zijn gedaan; als er niets is gewijzigd, staat daar de expliciete vaststelling dat de docs al in sync zijn.
- Er zijn geen nieuwe branches of PR's handmatig aangemaakt; doc-wijzigingen blijven uncommitted in de working tree voor de factory.

## Aannames

- "Documentatie" omvat primair `docs/factory/` en repo/module-README's; impliciete code-commentaar valt buiten scope.
- Bij twijfel is de code leidend (source of truth) en wordt de documentatie daarop aangepast, niet andersom.
- Het bijwerken van het story-worklog telt niet als een verboden codewijziging.
- Een lege diff (docs al correct) is een geldig en geslaagd eindresultaat, mits gerapporteerd.
- De controle gebeurt op effort-niveau "medium": de belangrijkste/structurele afwijkingen worden geadresseerd, niet noodzakelijk elke triviale formulering.

## Eindsamenvatting

Op basis van `.task.md`, het worklog (`docs/stories/worklog/SF-399-worklog.md`) en de reviewer-comments, en geverifieerd tegen `git diff main...HEAD`, hier de eindsamenvatting voor de PO.

---

## Eindsamenvatting — SF-399: Documentatie in lijn brengen met de code

### Wat is gebouwd
Een documentatie-audit (nightly) die de repo-docs heeft vergeleken met de actuele broncode en de geconstateerde afwijkingen heeft gecorrigeerd. Code is als source of truth gebruikt; uitsluitend Markdown is aangepast. De diff (`git diff main...HEAD`) bevat alleen documentatie plus het worklog — geen broncode, tests of `docs/stories`-content.

### Doorgevoerde wijzigingen
1. **Nieuwe modules toegevoegd aan de docs.** Naast `softwarefactory` en `agentworker` bestaan nu ook `dashboard-backend` (Spring Boot JSON-API, Maven-module) en `dashboard-frontend` (Flutter web-app). Verwerkt in `development.md` (build/test-commando's + structuur, root-pom als aggregator over 3 modules, Flutter-toolchain), `technical-spec.md` (Dart/Flutter + nieuwe Modules-sectie met poorten 8080/9090/9080) en `docs/factory/README.md`.
2. **`docs/technical/` verouderd** — hoofdbranch `master` → `main`, packagetelling gecorrigeerd naar 15 directe packages, niet-verifieerbare endpoint-telling verwijderd; ontbrekende module-secties (`core`, `pipeline`, `nightly`, `telegram`) toegevoegd in `modules.md`.
3. **Verouderde sync-beschrijving** gecorrigeerd: `SF_AUTO_SYNC_AFTER_AGENT` en `@factory:command:sync` bestaan niet meer; sync gebeurt nu onvoorwaardelijk via `AgentRunCompletionService.syncRepositoryAfterAgent` (met skip voor refiner/planner).
4. **`secrets-local.md` aangevuld** met reële env-vars uit `secrets.env.example`/code: o.a. `SF_YOUTRACK_PUBLIC_URL`, `SF_DASHBOARD_PASSWORD`, `SF_DASHBOARD_BASE_URL`, `SF_TELEGRAM_BOT_TOKEN`/`_CHAT_ID`, `SF_CODEX_CREDENTIALS_DIR`, `SF_MAX_TEST_CHAIN_RESETS`.

### Gemaakte keuzes
- **Code is leidend** bij twijfel; docs zijn op de code aangepast, niet andersom.
- **Effort medium**: structurele/feitelijke afwijkingen geadresseerd, niet elke triviale formulering.
- `functional-spec.md`, `deployment.md`, de agent-rolinstructies en de UX-docs zijn gecontroleerd en **bewust ongewijzigd** gelaten — bevonden in sync met de code.

### Getest
Niet van toepassing als unit-test: de story raakt uitsluitend Markdown, geen productiecode of tests. Verificatie is gedaan via de story-brede controle en reviewer-steekproeven tegen `pom.xml`, docker-compose, de Kotlin-packages en de Flutter-app. Reviewer (SF-400) en test-fase (SF-401) zijn akkoord; geen blockers.

### Bewust niet gedaan
- Geen functionele/broncode-wijzigingen.
- `docs/stories` niet aangepast (read-only input).
- Geen handmatige branches/PR's; doc-wijzigingen blijven in de working tree voor de factory.

---
