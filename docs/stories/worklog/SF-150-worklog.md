# SF-150 / SF-151 - Worklog

Story-context:
Implementeer sf-knowledge tool, FactorySecrets-veld, container-mounts en tips-injectie in system prompt

## Samenvatting

De Telegram-assistent kan nu geleerde inzichten opslaan (via `sf-knowledge`) en bij de volgende sessie
terugkrijgen (via de KnowledgeApi) als sectie `## Geleerde inzichten` in de system prompt.

## Checklist

- [x]: tools/sf-knowledge Python-script aangemaakt
- [x]: FactorySecrets.kt — `factoryInternalUrl` veld toegevoegd (SF_FACTORY_INTERNAL_URL)
- [x]: SecretsEnvLoader — laadt SF_FACTORY_INTERNAL_URL
- [x]: ClaudeAssistantClient.dockerCommand() — mount sf-knowledge + env-vars SF_FACTORY_BASE_URL + SF_FACTORY_TARGET_REPO
- [x]: TelegramAssistantService — KnowledgeApi geïnjecteerd, tips ophalen + injecteren in system prompt
- [x]: TelegramAssistantService — sf-knowledge tool-beschrijving in system prompt
- [x]: Unit-tests: systemPrompt met/zonder tips (mock KnowledgeApi), dockerCommand met/zonder factoryInternalUrl
- [x]: Worklog bijgewerkt
- [x]: Review-loopback: API-endpoint en parameter-naming in sf-knowledge gecorrigeerd

## Gedaan en waarom

### tools/sf-knowledge
Nieuw Python-script analoog aan `sf-youtrack`. Ondersteunt:
- `upsert --category --key --content` → POST /agent-knowledge/update
- `list` → GET /agent-knowledge?target_repo=&role=assistant

Gebruikt SF_FACTORY_BASE_URL (interne URL) en SF_FACTORY_TARGET_REPO (projectnaam van het kanaal).

### FactorySecrets.kt
Nieuw veld `val factoryInternalUrl: String?` (optioneel, default null). Ingeladen via
`SF_FACTORY_INTERNAL_URL`. Toegevoegd aan `redactedSummary()` (niet-secret, gewoon een URL).

### SecretsEnvLoader.kt
Roept `resolveOptional("SF_FACTORY_INTERNAL_URL")` aan en zet dit als `factoryInternalUrl`.

### ClaudeAssistantClient.kt
- `ask()`, `attempt()`, `dockerCommand()` krijgen parameter `targetRepo: String?`
- `dockerCommand()` mount `tools/sf-knowledge` naar `/usr/local/bin/sf-knowledge:ro`
- Als `factoryInternalUrl` niet leeg: injecteer `SF_FACTORY_BASE_URL` en `SF_FACTORY_TARGET_REPO`
  (conditioneel: alleen nuttig als de interne factory-URL beschikbaar is)

### TelegramAssistantService.kt
- Constructor-parameter `knowledgeApi: KnowledgeApi` toegevoegd
- `loadedTips(chatId)`: roept `knowledgeApi.find(targetRepo, "assistant")` aan,
  fouten worden silent gelogd (geen crash bij DB-problemen)
- `systemPrompt()`: voegt `## Geleerde inzichten` sectie toe als er tips zijn,
  plus beschrijving van de `sf-knowledge` tool
- `claude.ask()` krijgt nu `targetRepo = projectName(chatId)` mee

### Unit-tests (TelegramAssistantServiceTest.kt)
- `systemPrompt bevat geleerde inzichten als KnowledgeApi tips teruggeeft`
- `systemPrompt bevat geen geleerde-inzichten-sectie als er geen tips zijn`
- `systemPrompt gooit geen exception als KnowledgeApi faalt`
- `systemPrompt bevat sf-knowledge tool-beschrijving`
- `dockerCommand injecteert factory env-vars als factoryInternalUrl geconfigureerd is`
- `dockerCommand injecteert geen factory env-vars als factoryInternalUrl leeg is`

Tests kunnen niet lokaal gedraaid worden (geen mvn in de agent-omgeving). Correctheid geverifieerd
via statische review.

## SF-152 test-loopback fix

De tester meldde twee compile-fouten in `TelegramAssistantServiceTest.kt`:

1. `minimalSecrets.copy(...)` compileert niet: `FactorySecrets` is geen `data class`.
2. `kubeconfig` en `aiCredentialsDir` ontbreken in de constructor-aanroep (nullable maar zonder default).

**Fix**: `minimalSecrets` aangevuld met `kubeconfig = null, aiCredentialsDir = null`;
`secretsWithFactory` uitgeschreven als volledige constructor-aanroep in plaats van `.copy()`.
