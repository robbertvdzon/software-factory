# SF-1725 - [Audit] Dashboard-, bridge- en telegram-secrets uit de agent-container-omgeving houden

## Story

[Audit] Dashboard-, bridge- en telegram-secrets uit de agent-container-omgeving houden

<!-- refined-by-factory -->

## Samenvatting

Elke agent-container krijgt nu een bestand met vrijwel alle instellingen van de factory mee, ook wachtwoorden en sleutels die daar niets te zoeken hebben. Eén daarvan is de sleutel waarmee dashboard-sessies worden ondertekend: wie die sleutel heeft, kan zelf een geldige inlog voor het dashboard maken en de Google-login overslaan. Dat geldt zelfs voor agents die alleen mogen lezen.

Deze story haalt acht van die gevoelige waarden uit wat agents meekrijgen. Er is nagetrokken dat geen enkele agent ze gebruikt, dus het werk van de agents verandert er niet door. Een test bewaakt daarna dat ze er niet stiekem weer in terugkomen.

## Scope

In scope:

1. `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/workspaces/AgentWorkspace.kt` — `AGENT_ENV_DENYLIST` (r113-116) uitbreiden met exact deze acht namen, naast de bestaande `SF_GITHUB_TOKEN` en `SF_COPILOT_TOKEN`:

   - `SF_BRIDGE_TOKEN`
   - `SF_DASHBOARD_REMEMBER_SECRET`
   - `SF_DASHBOARD_PASSWORD`
   - `SF_ALLOWED_EMAILS`
   - `SF_GOOGLE_CLIENT_ID`
   - `SF_GITHUB_PACKAGES_TOKEN`
   - `SF_TELEGRAM_BOT_TOKEN`
   - `SF_FACTORY_API_TOKEN`

2. `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/runtime/DockerAgentRuntimeTest.kt` — de bestaande assertie op r258 (`envFileSnapshots.none { it.contains("SF_COPILOT_TOKEN") }`) uitbreiden naar dezelfde assertie voor de acht nieuwe namen. Dat mag in de bestaande test op r233 of in een eigen testmethode.

Uitdrukkelijk buiten scope:

- De denylist omdraaien naar een per-rol allowlist (de eigenlijke structurele oplossing; vraagt een besluit per rol en per variabele).
- `SF_DATABASE_URL` en `SF_AI_OAUTH_TOKEN` aanraken — die worden in de container aantoonbaar wél gelezen en moeten blijven.
- Wijzigingen aan `ClaudeAssistantClient` / de assistent-container. Die bouwt zijn eigen `docker run` en zet `SF_FACTORY_API_TOKEN` los als `-e` (ClaudeAssistantClient.kt r157-160), dus die route blijft werken.
- Documentatie: geen enkel bestand in `docs/factory/` of `docs/technical/` beschrijft de denylist of `factory.env`-inhoud, dus er is geen doc-drift op te lossen.

## Acceptance criteria

1. `AGENT_ENV_DENYLIST` in `AgentWorkspace.kt` bevat de tien namen: de twee bestaande plus de acht genoemde. `SF_DATABASE_URL` en `SF_AI_OAUTH_TOKEN` staan er niet in.
2. Het `factory.env`-bestand dat `AgentWorkspaceFactory.create()` schrijft, bevat geen van die tien namen, ongeacht de rol van de agent.
3. `DockerAgentRuntimeTest` bevat een assertie die voor elk van de acht nieuwe namen aantoont dat hij niet in `envFileSnapshots` voorkomt, en die assertie is bewijskrachtig: de gebruikte `FakeEnvironmentProvider` zet die acht namen ook daadwerkelijk in de nep-omgeving (met een herkenbare waarde), zodat een teruggedraaide denylist de test rood maakt.
4. De assertie dekt óók de waarden, niet alleen de sleutelnamen: een snapshot mag de meegegeven testwaarde van die acht variabelen niet bevatten.
5. Een positieve tegencheck blijft of wordt gedekt: met `SF_DATABASE_URL` in de nep-omgeving verschijnt die variabele wél in het env-bestand (de bestaande test op r31 dekt dit al voor `SF_DATABASE_URL`; die test mag niet verslechteren).
6. `mvn -pl softwarefactory test` (of de repo-brede verify) is groen; er zijn geen andere tests die de exacte inhoud van `factory.env` vastpinnen.
7. Geen gedragswijziging voor agents: er is geen leesplek van de acht variabelen in `agentworker` of `factory-common`.

## Aannames

- De acht variabelen zijn in een agent-container ongebruikt. Nagetrokken: een grep op `SF_[A-Z0-9_]+` over `agentworker/src/main` en `factory-common/src/main` levert 31 namen op, waarvan geen enkele in de acht zit; `agentworker` doet verder geen dynamische env-lookup (alleen `System.getenv()` als geheel doorgegeven map in `AgentCli.kt:31` en `PATH` in `TesterVerificationRunner.kt:77`).
- `tools/sf-story` en `tools/check-main-ci-green` (de enige gebruikers van `SF_FACTORY_API_TOKEN` / `SF_TELEGRAM_BOT_TOKEN`) draaien alleen in de assistent-container; `DockerAgentRuntime` mount `tools/` nergens. Een agent die aan de software-factory-repo werkt heeft die scripts wel in zijn checkout staan, maar geen rolinstructie in `docs/factory/agents/*.md` verwijst ernaar — het gebruiken van die scripts vanuit een agent is dus geen bestaand gedrag dat hier breekt.
- Het testbestand heet `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/runtime/DockerAgentRuntimeTest.kt` (dus zonder `docker/`-submap, anders dan de oorspronkelijke storytekst suggereert); de assertie staat daar op r258.
- Behalve `DockerAgentRuntimeTest` is er geen test die de inhoud van `factory.env` vastlegt (`AgentWorkspaceTipsTest`, `AgentWorkspaceCleanerTest` en `AgentWorkspacePathSanitizationTest` raken het env-bestand niet).
- Dit is een eerste stap; het bredere risico (denylist in plaats van allowlist) blijft bestaan en wordt bewust in een aparte story opgepakt.

## Eindsamenvatting

## Eindsamenvatting SF-1725 — Dashboard-, bridge- en telegram-secrets uit de agent-container houden

### Wat is er gebouwd
Agent-containers kregen via het bestand `factory.env` vrijwel alle factory-instellingen mee, inclusief secrets die een agent nooit nodig heeft. De meest riskante daarvan was `SF_DASHBOARD_REMEMBER_SECRET`: wie die sleutel heeft kan zelf een geldige dashboard-sessie ondertekenen en de Google-login overslaan — ook vanuit een agent die alleen zou moeten lezen.

De bestaande `AGENT_ENV_DENYLIST` in `AgentWorkspace.kt` is uitgebreid van 2 naar 10 namen. Nieuw geblokkeerd: `SF_BRIDGE_TOKEN`, `SF_DASHBOARD_REMEMBER_SECRET`, `SF_DASHBOARD_PASSWORD`, `SF_ALLOWED_EMAILS`, `SF_GOOGLE_CLIENT_ID`, `SF_GITHUB_PACKAGES_TOKEN`, `SF_TELEGRAM_BOT_TOKEN` en `SF_FACTORY_API_TOKEN`. Het filter werkt rolonafhankelijk, dus dit geldt voor elke agent. Bij de lijst staat nu een toelichting waarom hij bestaat.

De diff is klein en volledig binnen scope: 3 bestanden (implementatie, test, worklog), geen wijziging aan de assistent-route.

### Gemaakte keuzes
- **`SF_DATABASE_URL` en `SF_AI_OAUTH_TOKEN` blijven bewust doorgegeven** — die worden in de container aantoonbaar gelezen; weghalen zou agents breken. Dit is ook als expliciete assertie in de test vastgelegd.
- **De bewakende test is nieuw en bewijskrachtig gemaakt** in plaats van de oude one-liner uit te breiden. De nep-omgeving zet alle tien namen daadwerkelijk met een unieke, herkenbare waarde (`<NAAM>-leaked-value`), en er wordt per naam gecontroleerd op de sleutel, op de wáárde én op de waarde in het docker-commando. Zo faalt de test ook als een secret via een andere route zou lekken.
- **Twee positieve tegenchecks** (`SF_DATABASE_URL` en `SF_AI_OAUTH_TOKEN` komen er wél doorheen) plus een `isNotEmpty()`-check sluiten uit dat de test vacuüm-groen staat.

### Wat is getest
- **Mutatie-sanitycheck:** met de acht namen tijdelijk uit de denylist faalt de nieuwe test op de inhoudelijke assertie (`sleutel SF_BRIDGE_TOKEN lekt naar het env-bestand`), niet alleen op een lijstvergelijking. Een teruggedraaide denylist wordt dus direct rood.
- **Live gedragsbewijs (tester):** in een draaiende tester-container van de huidige main-versie waren alle acht namen inderdaad aanwezig, terwijl de al geblokkeerde `SF_GITHUB_TOKEN`/`SF_COPILOT_TOKEN` ontbraken. Daarmee is zowel het lek als de werking van het filtermechanisme in productie aangetoond. Alleen namen gecontroleerd, geen waarden gelogd.
- **Geen gedragswijziging voor agents:** door developer, reviewer én tester onafhankelijk nagetrokken dat geen van de acht namen in `agentworker` of `factory-common` wordt gelezen; ook `docker/`, `Dockerfile.agent` en de rolinstructies noemen ze niet.
- **Builds:** `mvn clean verify` vanaf repo-root groen (alle modules SUCCESS, 0 failures/errors). Gerichte runs door reviewer en tester: 16 resp. 21 tests groen, geen flakes.

### Bewust niet gedaan
- **De denylist omdraaien naar een per-rol allowlist** — dat is de eigenlijke structurele oplossing, maar vraagt een besluit per rol en per variabele. Het bredere risico (nieuwe secrets lekken standaard mee tot iemand ze aan de denylist toevoegt) blijft dus bestaan en verdient een aparte story.
- **`ClaudeAssistantClient` / de assistent-container** is niet aangeraakt; die zet `SF_FACTORY_API_TOKEN` los als `-e` en blijft werken.
- **Documentatie:** geen enkel bestand in `docs/factory/` of `docs/technical/` beschrijft de denylist of de inhoud van `factory.env`, dus er was geen doc-drift. De documentatie-subtaak SF-1729 zal hier naar verwachting weinig tot niets te doen vinden.
- **Geen screenshots/preview-verificatie**: deze wijziging heeft geen UI-oppervlak en er was geen preview-omgeving in de sandbox.

**Openstaand punt voor de PO:** de secrets die tot nu toe naar agent-containers gingen (met name `SF_DASHBOARD_REMEMBER_SECRET`) zijn historisch wel meegegeven. Overweeg of die waarden geroteerd moeten worden nu deze fix live gaat.
