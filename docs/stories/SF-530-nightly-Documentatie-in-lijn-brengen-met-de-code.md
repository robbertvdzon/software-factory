# SF-530 - nightly: Documentatie in lijn brengen met de code

## Story

nightly: Documentatie in lijn brengen met de code

<!-- refined-by-factory -->

## Scope

Controleer of de volledige documentatie in `docs/` (in het bijzonder `docs/factory/`) nog in lijn is met de huidige broncode, en breng waar nodig **alleen de documentatie** in sync.

In scope:
- Doorlopen van de documentatie (`docs/factory/README.md`, `development.md`, `technical-spec.md`, `functional-spec.md`, `deployment.md`, de `agents/*`-rolinstructies en de `ux/*`-schermbeschrijvingen) en vergelijken met wat de code daadwerkelijk doet.
- Bijwerken van documentatie die niet meer klopt: verouderde beschrijvingen, hernoemde of verwijderde componenten, gewijzigd gedrag, ontbrekende functionaliteit die wél in de code zit.
- Toevoegen van documentatie voor bestaande code-functionaliteit die nog niet beschreven is.
- `docs/stories` mag als input gebruikt worden (om te begrijpen waaróm functionaliteit bestaat), maar mag niet gewijzigd worden.

Buiten scope:
- Elke wijziging aan broncode (alleen documentatiebestanden mogen veranderen).
- Wijzigingen aan `docs/stories`.
- Nieuwe functionaliteit of refactors.

## Acceptance criteria

1. Er zijn **geen broncode-wijzigingen**: de diff bevat uitsluitend documentatiebestanden (bv. onder `docs/`). Wordt er code aangepast, dan moet de story worden afgekeurd.
2. De documentatie beschrijft de bestaande functionaliteit correct: gecontroleerde onderdelen komen overeen met het werkelijke gedrag van de code.
3. Geconstateerde discrepanties tussen docs en code zijn opgelost door de documentatie aan te passen (corrigeren, aanvullen of verwijderen van achterhaalde info).
4. Functionaliteit die in de code aanwezig is maar in de documentatie ontbrak, is toegevoegd aan de relevante documentatie.
5. De build/tests blijven ongewijzigd groen (er is immers geen code gewijzigd); een tester hoeft niets te testen als er geen code-aanpassingen zijn.
6. In de worklog (`docs/stories/worklog/SF-530-worklog.md`) staat kort beschreven welke documentatie is gecontroleerd en welke aanpassingen zijn gedaan (of dat docs en code al in sync waren).

## Aannames

- "Documentatie" betreft de Markdown-/tekstdocumentatie in `docs/` (primair `docs/factory/`); gegenereerde of UI-wireframe-bestanden worden alleen aangepast wanneer ze feitelijk onjuist zijn t.o.v. de code.
- Bij twijfel geldt de code als bron van waarheid: de documentatie wordt naar de code toe aangepast, niet andersom.
- Als documentatie en code al volledig in sync zijn, is een lege functionele wijziging (alleen een worklog-notitie) een acceptabel resultaat.
- Codecommentaar binnen broncodebestanden valt niet onder "documentatie" en wordt niet aangepast (dat zou immers een code-wijziging zijn).

## Eindsamenvatting

Ik heb de worklog, de review/test-notities en de diff bekeken. Alles staat klaar voor de eindsamenvatting.

## Eindsamenvatting — SF-530: Documentatie in lijn brengen met de code

**Doel van de story**
Nachtelijke onderhoudstaak: controleren of de factory-documentatie in `docs/` (primair `docs/factory/`) nog klopt met de werkelijke broncode, en waar nodig **alleen de documentatie** bijwerken. Code is de bron van waarheid; broncode en `docs/stories` mogen niet gewijzigd worden.

**Wat is gebouwd / aangepast (uitsluitend documentatie)**
De volledige `docs/factory/`-set is tegen de code gecontroleerd. Drie reële discrepanties zijn opgelost:

1. **Telegram-assistent gedocumenteerd** — de conversationele Telegram-assistent (`TelegramAssistantService`, `ClaudeAssistantClient`, `AssistantWorkspaceService`, `TelegramPoller`, rol `ASSISTANT`, `Dockerfile.assistant`) bestond wél in code maar ontbrak in de docs. Nieuwe sectie *"Telegram-assistent — conversationeel kanaal"* toegevoegd aan `functional-spec.md` (threads, per-project context, tools `sf-youtrack`/`sf-browser`/`oc`, aan/uit via `SF_AI_OAUTH_TOKEN`).
2. **Workspace-cleanup-vars toegevoegd** — `SF_AGENT_WORKSPACE_CLEANUP_ENABLED` en `SF_AGENT_WORKSPACE_PRESERVE_ON_FAILURE` (gelezen door `AgentWorkspaceCleaner`) gedocumenteerd in `technical-spec.md` en `secrets-local.md`.
3. **Assistent-config-vars toegevoegd** — `SF_ASSISTANT_IMAGE` (default `assistant:local`) en `SF_ASSISTANT_TIMEOUT_SECONDS` (default 3600) toegevoegd aan `secrets-local.md`.

**Gemaakte keuzes**
- README, development, deployment, de overige `technical-spec`/`functional-spec`-secties en de `agents/*`-rolinstructies bleken al in sync — bewust ongemoeid gelaten.
- `SF_DASHBOARD_REMEMBER_SECRET` gecontroleerd maar niet aangepast: de doc-tekst klopt (var wordt door `dashboard-backend/DashboardConfig.kt` gelezen met fallback `"$username:$password"`).
- De `ux/*`-schermbeschrijvingen zijn bij medium effort bewust niet exhaustief doorlopen (buiten kritieke scope).

**Wat is getest**
Geen broncode gewijzigd, dus conform acceptatiecriterium 5 geen build/tests nodig. Reviewer (SF-531) én tester (SF-532) hebben beide bevestigd: de diff t.o.v. `main` bevat **uitsluitend** documentatie (`functional-spec.md`, `technical-spec.md`, `secrets-local.md`) plus de worklog — geen enkel codebestand. De nieuwe documentatieclaims zijn steekproefsgewijs tegen de code geverifieerd en defaults kloppen (o.a. `assistant:local`, timeout 3600, cleanup-defaults true/false). Beide rollen akkoord.

**Bewust niet gedaan**
- Geen broncodewijzigingen (per scope verboden).
- Geen wijzigingen aan `docs/stories` (alleen als input gelezen).
- `ux/*`-wireframes niet diepgaand herzien.

**Resultaat:** 4 bestanden, +149 regels, docs-only. Story klaar voor de documentatie-/merge-/deploy-vervolgstappen (SF-534 t/m SF-536).
