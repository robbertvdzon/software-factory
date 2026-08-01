# SF-1725 - Worklog

Story-context bij eerste pickup:
Denylist uitbreiden met acht secrets en de bewakende test bewijskrachtig maken

1) softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/workspaces/AgentWorkspace.kt: breid het companion-veld AGENT_ENV_DENYLIST (r113-116) uit van twee naar tien namen. Naast de bestaande SF_GITHUB_TOKEN en SF_COPILOT_TOKEN toevoegen: SF_BRIDGE_TOKEN, SF_DASHBOARD_REMEMBER_SECRET, SF_DASHBOARD_PASSWORD, SF_ALLOWED_EMAILS, SF_GOOGLE_CLIENT_ID, SF_GITHUB_PACKAGES_TOKEN, SF_TELEGRAM_BOT_TOKEN, SF_FACTORY_API_TOKEN. SF_DATABASE_URL en SF_AI_OAUTH_TOKEN NIET toevoegen - die worden in de container aantoonbaar gelezen. Zet een korte comment bij de lijst die uitlegt waarom hij bestaat en dat die twee er bewust buiten blijven. 2) Verifieer vooraf zelf met `grep -rhoE 'SF_[A-Z0-9_]+' agentworker/src/main factory-common/src/main | sort -u` dat geen van de acht namen daar voorkomt; vind je er toch een, stop dan en meld dat terug in plaats van door te gaan. 3) softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/runtime/DockerAgentRuntimeTest.kt (let op: pad zonder /docker/): maak de bewaking bewijskrachtig. Zorg dat de FakeEnvironmentProvider van de betreffende test de acht nieuwe namen ook echt in de nep-omgeving zet, elk met een unieke herkenbare waarde, en asserteer dat geen enkele envFileSnapshot die naam EN die waarde bevat. Uitbreiden van de bestaande test rond r233-259 of een eigen testmethode mag allebei. 4) Laat de bestaande positieve dekking intact: de eerste test (r31 e.v.) bewijst dat SF_DATABASE_URL wel in het env-bestand landt; die mag niet verslechteren. Voeg desgewenst SF_AI_OAUTH_TOKEN toe als tweede positieve tegencheck. 5) Sanity-check dat de test niet vacuum-groen is: haal de nieuwe namen tijdelijk lokaal uit de denylist, zie de test rood worden, en draai hem terug. 6) Raak ClaudeAssistantClient of de assistent-route niet aan; draai geen allowlist-refactor (buiten scope). 7) Sluit af met `mvn verify` (minimaal `mvn -pl softwarefactory test`) en met een zelfreview van de diff.

Story in eigen woorden:
Agent-containers krijgen via `factory.env` vrijwel alle SF_-instellingen van de factory mee, ook
secrets die daar niets te zoeken hebben (o.a. de sleutel waarmee dashboard-sessies worden
ondertekend). Acht van die waarden gaan uit de meegegeven omgeving, en de test die dat bewaakt
wordt bewijskrachtig gemaakt zodat een teruggedraaide denylist meteen rood is.

Stappenplan:
[x]: read issue and target docs
[x]: vooraf verifiëren dat geen agent-code de acht variabelen leest
[x]: AGENT_ENV_DENYLIST uitbreiden van 2 naar 10 namen (+ toelichtende comment)
[x]: DockerAgentRuntimeTest bewijskrachtig maken (sleutels én waarden, plus positieve tegencheck)
[x]: mutatie-sanitycheck: denylist tijdelijk terugdraaien, test rood zien, herstellen
[x]: volledig vangnet draaien (`mvn clean verify` vanaf repo-root)
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- **Vooraf-check (stap 2 uit de description):**
  `grep -rhoE 'SF_[A-Z0-9_]+' agentworker/src/main factory-common/src/main | sort -u` levert 31
  namen op; geen enkele van de acht zit erin. Wel aanwezig zijn `SF_DATABASE_URL` en
  `SF_AI_OAUTH_TOKEN`, die dus terecht buiten de denylist blijven. Geen gedragswijziging voor
  agents (AC7).
- **`AgentWorkspace.kt`:** `AGENT_ENV_DENYLIST` uitgebreid van 2 naar 10 namen, met een KDoc-comment
  die uitlegt waaróm de lijst bestaat (meegegeven secrets zijn buiten de factory om misbruikbaar) en
  dat `SF_DATABASE_URL` / `SF_AI_OAUTH_TOKEN` er bewust buiten blijven omdat ze in de container
  aantoonbaar gelezen worden (AC1, AC2 — het filter op r41 was al generiek over de hele set).
- **`DockerAgentRuntimeTest.kt`:** nieuwe test
  `denylisted factory secrets never reach the agent env file`. De `FakeEnvironmentProvider` zet alle
  tien denylist-namen daadwerkelijk in de nep-omgeving, elk met een unieke herkenbare waarde
  (`<NAAM>-leaked-value`). Geassserteerd wordt per naam dat geen enkele `envFileSnapshot` de sleutel
  bevat, dat geen enkele snapshot de wáárde bevat, en dat de waarde ook niet in het docker-commando
  staat (AC3, AC4). De bestaande test op r31 (`SF_DATABASE_URL` landt wél in het env-bestand) is
  ongewijzigd gebleven; in de nieuwe test zitten `SF_DATABASE_URL` én `SF_AI_OAUTH_TOKEN` als
  positieve tegencheck, zodat de test niet vacuüm-groen kan zijn (AC5).
- **Mutatie-sanitycheck (stap 5):** met de acht nieuwe namen tijdelijk uit de denylist verwijderd
  faalt de nieuwe test op de inhoudelijke assertie
  (`AssertionFailedError: sleutel SF_BRIDGE_TOKEN lekt naar het env-bestand`), niet alleen op een
  structurele lijstvergelijking. De structurele asserties op de denylist staan bewust ná de
  snapshot-asserties, juist zodat de mutatie het lek zélf aantoont. Daarna volledig hersteld.
- **Niet aangeraakt:** `ClaudeAssistantClient` / de assistent-route en er is geen allowlist-refactor
  gedaan (beide expliciet buiten scope).
- **Specs:** geen bestand in `docs/factory/` of `docs/technical/` beschrijft de denylist of de
  inhoud van `factory.env`, dus er is geen spec-drift bijgewerkt.

Bewijs vangnet:
- `mvn -B --no-transfer-progress -pl softwarefactory test -Dtest=DockerAgentRuntimeTest`:
  Tests run: 16, Failures: 0, Errors: 0.
- `mvn -B --no-transfer-progress clean verify` vanaf repo-root: **BUILD SUCCESS**, exitcode 0,
  0 failures / 0 errors, alle modules SUCCESS (factory-contracts, factory-common, softwarefactory,
  agentworker, dashboard-backend), totale tijd 04:15 min. Geen andere test bleek de exacte inhoud
  van `factory.env` vast te pinnen (AC6).

Review (SF-1726, 01-08-2026) - akkoord:
- Diff t.o.v. `main` is 3 bestanden / +134 regels en volledig binnen scope; geen wijziging aan
  `ClaudeAssistantClient` of allowlist-refactor.
- AC1-AC5 nagelopen in de code: denylist telt 10 namen zonder `SF_DATABASE_URL`/`SF_AI_OAUTH_TOKEN`,
  het filter in `AgentWorkspaceFactory.create()` (r39-41) is rolonafhankelijk, en de nieuwe test
  asserteert per naam op sleutel, waarde en docker-commando met `envFileSnapshots.isNotEmpty()` als
  vacuumbeveiliging plus twee positieve tegenchecks.
- AC7 zelf hercontroleerd: `grep -rhoE 'SF_[A-Z0-9_]+' agentworker/src/main factory-common/src/main`
  bevat geen van de acht namen. `SF_FACTORY_API_TOKEN` wordt alleen in de orchestrator gelezen
  (`DeploySubtaskHandler`/`ProjectDeployClient` via `ConfigApi`), niet in de agent-container.
- Gerichte hercontrole: `mvn -pl factory-common,softwarefactory -am test -Dtest=DockerAgentRuntimeTest`
  -> Tests run: 16, Failures: 0, Errors: 0 (exit 0).
- Geen spec-drift: `docs/factory/` en `docs/technical/` noemen de denylist of `factory.env` nergens.

Test (SF-1727, 01-08-2026) - akkoord:
- **Live gedragsbewijs van het lek (pre-fix baseline):** in de draaiende tester-container (gestart
  door de main-versie van de orchestrator) zijn alle acht namen aanwezig in de omgeving, terwijl
  `SF_GITHUB_TOKEN` en `SF_COPILOT_TOKEN` (al denylisted) ontbreken. Dat bewijst zowel dat het
  probleem reeel is als dat het denylist-mechanisme in productie daadwerkelijk filtert; met deze
  diff verdwijnen de acht op dezelfde manier. (Alleen namen gecontroleerd, geen waarden gelogd.)
- AC1/AC2 in code: `AgentWorkspaceFactory.create()` (r37-46) filtert rolonafhankelijk
  (`filterKeys { it !in AGENT_ENV_DENYLIST }`); de lijst telt 10 namen, zonder `SF_DATABASE_URL`
  en `SF_AI_OAUTH_TOKEN`.
- AC3/AC4: nieuwe test zet de tien namen echt in de nep-omgeving met unieke waarden en asserteert
  per naam op sleutel, waarde en docker-commando; `envFileSnapshots.isNotEmpty()` plus twee
  positieve tegenchecks (AC5) sluiten vacuum-groen uit.
- AC7 hercontroleerd: `grep -rhoE 'SF_[A-Z0-9_]+' agentworker/src/main factory-common/src/main`
  bevat geen van de acht. Bredere grep over `docker/`, `Dockerfile.agent` en `docs/factory/` levert
  alleen host-/operator-plekken op (`docker-compose.yml`, `smoke-local-quickstart.sh`,
  `secrets-local.md`, `durable-completion.md` - een operator-runbook met curl naar
  `localhost:8080`, niet aangeroepen vanuit een agent). Geen enkel bestand in
  `docs/factory/agents/` noemt de acht variabelen. `DockerAgentRuntime.dockerRunCommand()`
  (r160-198) zet geen van de acht los als `-e`.
- AC6: `DockerAgentRuntimeTest` is het enige testbestand dat de inhoud van `factory.env` raakt.
- Gedraaid: `mvn -B --no-transfer-progress -pl softwarefactory -am test
  -Dtest='DockerAgentRuntimeTest,AgentWorkspace*Test' -Dsurefire.failIfNoSpecifiedTests=false`
  -> exit 0, Tests run: 21, Failures: 0, Errors: 0, Skipped: 0 (16 + 2 + 2 + 1). Geen flakes.
- Geen preview-omgeving/browser in deze sandbox (geen `SF_PREVIEW_URL`), dus geen screenshots;
  het volledige vangnet (`mvn clean verify`, `tools/audit-documentation`) draait de harness
  revisiegebonden na deze run.
