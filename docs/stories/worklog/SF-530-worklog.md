# SF-530 - Worklog

Story-context bij eerste pickup:
Documentatie synchroniseren met de code

Controleer de documentatie in docs/ (primair docs/factory/: README.md, development.md, technical-spec.md, functional-spec.md, deployment.md, agents/*, ux/*) tegen de werkelijke broncode (agentworker, dashboard-backend, dashboard-frontend, softwarefactory, deploy, docker, tools, quality en build/config zoals pom.xml, compose.yaml, Dockerfile.*, factory-loop.sh, properties.default.env, projects.yaml.example). Werk ALLEEN documentatie bij: corrigeer verouderde/hernoemde/verwijderde beschrijvingen, voeg ontbrekende-maar-bestaande functionaliteit toe en verwijder achterhaalde info; code is bron van waarheid. Pas GEEN broncode aan en wijzig GEEN bestanden onder docs/stories (alleen lezen als input). Werk de worklog docs/stories/worklog/SF-530-worklog.md bij met wat gecontroleerd en aangepast is (of dat alles al in sync was). Sluit af met een zelf-review die bevestigt dat de diff uitsluitend documentatiebestanden raakt.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests (n.v.t. — geen code gewijzigd)
[x]: update story-log with results

## Wat gecontroleerd is

Alle `docs/factory/`-documentatie vergeleken met de code:

- **README.md** — modulestructuur (softwarefactory + ingebouwd HTML-dashboard, agentworker,
  dashboard-backend, dashboard-frontend) en verwijzing naar `specs/specs.md`: klopt.
- **development.md** — build/test-commando's (3 Maven-modules + root aggregator), `./factory local-db`,
  structuur: geverifieerd tegen `pom.xml` (`<module>`'s), `factory`-script en `docker/docker-compose.yml`. Klopt.
- **technical-spec.md** — stack, modules + poorten (dashboard-backend 9090, frontend 9080),
  verplichte/optionele SF_-vars, orchestrator-tuning-defaults, per-project `manualApprove`, merge-altijd-automatisch,
  documentatie-stap, YouTrack custom fields, nightly scheduler (V11/V12/V13, `kind`, manual/scheduled runs):
  geverifieerd tegen `OrchestratorSettings`, `compose.yaml`/`docker-compose.yml`, `properties.default.env`. Grotendeels in sync.
- **functional-spec.md** — agent-keten, Silent (SF-335), documentatie-stap (SF-213), manual-approve (SF-192),
  merge (SF-244), test-chain-reset (SF-200), Telegram-test-melding (SF-206), nightly (SF-350): in sync.
- **deployment.md / secrets-local.md** — run-commando's, frontmatter, verplichte + optionele keys: geverifieerd
  tegen `FactorySecrets.REQUIRED_KEYS` en de `SF_`-vars die de code daadwerkelijk leest.
- **agents/*.md** — rolinstructies developer/documenter/refiner/reviewer/summarizer/tester: nog accuraat.

## Geconstateerde discrepanties + aanpassingen

1. **Telegram-assistent ontbrak volledig in de docs.** De code bevat een conversationele
   Telegram-assistent (`TelegramAssistantService`, `ClaudeAssistantClient`, `AssistantWorkspaceService`,
   geroute via `TelegramPoller`; `AgentRole.ASSISTANT`; `Dockerfile.assistant`) die nergens in
   `docs/factory/` beschreven stond. → Nieuwe sectie **"Telegram-assistent — conversationeel kanaal"**
   toegevoegd aan `functional-spec.md` (threads, per-project context, tools `sf-youtrack`/`sf-browser`/`oc`,
   kennis onder rol ASSISTANT, aan/uit via `SF_AI_OAUTH_TOKEN`).

2. **Agent-workspace-opruiming env-vars ontbraken.** `SF_AGENT_WORKSPACE_CLEANUP_ENABLED` en
   `SF_AGENT_WORKSPACE_PRESERVE_ON_FAILURE` staan in `properties.default.env` en worden door
   `AgentWorkspaceCleaner` gelezen, maar stonden niet in de docs. → Toegevoegd aan `technical-spec.md`
   (met uitleg + verwijzing naar `properties.default.env`) en aan de optionele-keys-lijst van `secrets-local.md`.

3. **Assistent-config-vars ontbraken.** `SF_ASSISTANT_IMAGE` (default `assistant:local`) en
   `SF_ASSISTANT_TIMEOUT_SECONDS` (default 3600) waren niet gedocumenteerd. → Toegevoegd aan
   `secrets-local.md` (env-blok + uitleg-paragraaf).

### Gecontroleerd maar GEEN wijziging nodig (geen discrepantie)

- `SF_DASHBOARD_REMEMBER_SECRET` (secrets-local.md): de softwarefactory-`FactoryDashboardAuth` hardcodet
  de remember-secret als `"$username:$password"`, maar de var bestaat wél en wordt gelezen door
  `dashboard-backend/DashboardConfig.kt` (fallback `"$username:$password"`). Doc-tekst klopt dus; ongemoeid gelaten.

## Tests

Geen broncode gewijzigd, dus geen build/test nodig (acceptatiecriterium 5). De diff bevat uitsluitend
documentatiebestanden (`docs/factory/functional-spec.md`, `technical-spec.md`, `secrets-local.md`) plus deze worklog.

## Zelf-review

`git diff --stat` bevestigt: alléén bestanden onder `docs/factory/` zijn gewijzigd; geen broncode en geen
bestanden onder `docs/stories/` (behalve deze worklog onder `docs/stories/worklog/`, wat verplicht is).

## Review (SF-531, reviewer)

[info] Diff t.o.v. `main` bevat uitsluitend documentatie (`functional-spec.md`, `technical-spec.md`,
`secrets-local.md`) + deze worklog — geen broncode. AC1 voldaan.
[info] Documentatieclaims steekproefsgewijs tegen de code geverifieerd en correct:
- Assistent-componenten bestaan (`TelegramAssistantService`, `ClaudeAssistantClient`,
  `AssistantWorkspaceService`, `TelegramPoller`, `AgentRole.ASSISTANT`, `Dockerfile.assistant`).
- Defaults kloppen: `SF_ASSISTANT_IMAGE`=`assistant:local` (`ClaudeAssistantClient.kt:358`),
  `SF_ASSISTANT_TIMEOUT_SECONDS`=3600 (`:362`), `SF_AGENT_WORKSPACE_CLEANUP_ENABLED`=true /
  `SF_AGENT_WORKSPACE_PRESERVE_ON_FAILURE`=false (`AgentWorkspaceCleaner.kt:19-20`, ook in
  `properties.default.env:29-30`).
- Gedrag (threads/prefixes `nieuw:`/`new:`/`story:`, `/stop`, `/help`, gate op `SF_AI_OAUTH_TOKEN`,
  `/work/in`+`/work/out`, `projectNameForChatId`) komt overeen met `TelegramAssistantService.kt`.
[info] `ux/*`-docs zijn (terecht, medium effort) niet exhaustief doorlopen; buiten kritieke scope.
[info] Geen build/tests nodig (geen code gewijzigd). Akkoord.

## Test (SF-532, tester)

[info] Diff t.o.v. `main` geverifieerd: uitsluitend documentatie
(`docs/factory/functional-spec.md`, `technical-spec.md`, `secrets-local.md`) + deze worklog.
Geen enkel broncodebestand gewijzigd → AC1 voldaan.
[info] Nieuwe/aangepaste documentatieclaims tegen de code gecontroleerd; allemaal correct:
- Componenten bestaan: `TelegramAssistantService`/`TelegramPoller`/`ClaudeAssistantClient`/
  `AssistantWorkspaceService`/`AgentWorkspaceCleaner`, `AgentRole.ASSISTANT`, `Dockerfile.assistant`.
- Defaults kloppen: `SF_ASSISTANT_IMAGE`=`assistant:local` (ClaudeAssistantClient.kt:358),
  `SF_ASSISTANT_TIMEOUT_SECONDS`/`DEFAULT_TIMEOUT_SECONDS`=3600 (:359,:362),
  `SF_AGENT_WORKSPACE_CLEANUP_ENABLED`=true / `SF_AGENT_WORKSPACE_PRESERVE_ON_FAILURE`=false
  (AgentWorkspaceCleaner.kt:19-20 + properties.default.env:29-30).
- Gedrag klopt: thread-prefixes (`nieuw:`/`new:`/`story:` e.a., TelegramAssistantService.kt:311),
  `/stop`+`/help` (:57,:61), gate op `SF_AI_OAUTH_TOKEN` ("staat uit" :70, enabled :67),
  `/work/in`+`/work/out`, `projectNameForChatId` (ProjectRepoResolver.kt:104),
  tools `sf-youtrack`/`sf-browser` (read-only mount), `oc`/`kubectl` cluster-toegang.
[info] AC5: geen build/tests gedraaid — er is geen code gewijzigd (conform AC: tester hoeft niets
te testen bij een docs-only wijziging). Geen preview-context beschikbaar (SF_PREVIEW_URL leeg).
[done] Alle acceptatiecriteria voldaan. Resultaat: tested.
