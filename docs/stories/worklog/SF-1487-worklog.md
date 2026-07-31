# SF-1487 - Worklog

Story-context bij eerste pickup:
ClaudeAssistantClient: SF_ASSISTANT_IMAGE en SF_ASSISTANT_TIMEOUT_SECONDS via ConfigApi

In softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/telegram/clients/ClaudeAssistantClient.kt: (1) laat de instance-val timeoutSeconds de waarde uit configApi.resolvedValues()["SF_ASSISTANT_TIMEOUT_SECONDS"] lezen met exact dezelfde validatieketen als nu (?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SECONDS). (2) Verplaats IMAGE uit het private companion object naar een private instance-val, gelezen als configApi.resolvedValues()["SF_ASSISTANT_IMAGE"]?.takeIf { it.isNotBlank() } ?: "assistant:local", en pas de twee gebruiksplekken aan (dockerCommand() en de warn-log in runDocker()). IMAGE_EXTS, MODEL, DEFAULT_TIMEOUT_SECONDS, STOPPED_REPLY en DISALLOWED_TOOLS blijven ongewijzigd in het companion object. (3) Verwijder beide '// TODO(fase 3): via ConfigApi'-commentaren; laat de verklarende KDoc/comments staan. (4) Houd de publieke constructorsignatuur inclusief defaults intact, zodat ClaudeAssistantClient(secrets) blijft compileren (BridgeTestFixtures.kt, TelegramAssistantServiceTest.kt, TelegramAssistantFlowTest.kt, TelegramPollerTest.kt). (5) Schrijf een unittest met een stub-ConfigApi (enige abstracte methode: resolvedValues()) die aantoont dat beide sleutels uit de config worden overgenomen, dat een lege map de defaults oplevert (assistant:local / 3600) en dat een blanke of niet-numerieke/<=0 waarde terugvalt op de default; reflectie op de private velden mag, conform het bestaande patroon in TelegramAssistantServiceTest. Wijzig niets aan ConfigApi, SecretsEnvLoader, FactorySecrets, properties.default.env of de documentatie. Draai mvn -f softwarefactory/pom.xml test en mvn verify, doe een eigen review-stap en werk docs/stories/worklog/SF-1487-worklog.md bij.

Story in eigen woorden:
De Telegram-assistent las twee instellingen (`SF_ASSISTANT_IMAGE`, `SF_ASSISTANT_TIMEOUT_SECONDS`)
rechtstreeks via `System.getenv`, terwijl de rest van de factory alles via `ConfigApi` leest.
Daardoor werkten die twee sleutels alleen als échte omgevingsvariabele en niet vanuit
`secrets.env`/`properties.env`/`properties.default.env`, ondanks wat de docs beloven. Beide lopen nu
via `configApi.resolvedValues()`; defaults en gedrag blijven verder identiek.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `ClaudeAssistantClient.timeoutSeconds` leest nu `configApi.resolvedValues()["SF_ASSISTANT_TIMEOUT_SECONDS"]`
  met exact dezelfde keten (`?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SECONDS`).
- `IMAGE` is uit het private companion object gehaald en vervangen door de private instance-val
  `image` (`configApi.resolvedValues()["SF_ASSISTANT_IMAGE"]?.takeIf { it.isNotBlank() } ?: "assistant:local"`);
  de twee gebruiksplekken (`dockerCommand()` en de warn-log in `runDocker()`) zijn meegegaan. Instance-val
  i.p.v. companion, omdat de waarde nu per instantie van de geïnjecteerde `ConfigApi` afhangt.
- Beide `// TODO(fase 3): via ConfigApi`-commentaren verwijderd; de verklarende KDoc/comments over de
  betekenis van de instellingen zijn behouden (de nieuwe `image`-val heeft een eigen korte KDoc).
- `IMAGE_EXTS`, `MODEL`, `DEFAULT_TIMEOUT_SECONDS`, `STOPPED_REPLY` en `DISALLOWED_TOOLS` staan
  ongewijzigd in het companion object; de publieke constructorsignatuur (incl. defaults) is intact,
  dus `ClaudeAssistantClient(secrets)` blijft in de bestaande tests compileren.
- Nieuwe unittest `softwarefactory/src/test/kotlin/.../telegram/ClaudeAssistantClientConfigTest.kt`
  (6 tests) met een stub-`ConfigApi`: waarden uit de config worden overgenomen; lege map geeft de
  defaults (`assistant:local` / 3600); blanke image en niet-numerieke/lege/0/negatieve timeout vallen
  terug op de default; en het via reflectie opgebouwde docker-commando bevat de juiste image
  (config-waarde resp. default) op de plek vlak vóór `claude`. Reflectie op de private leden volgt het
  bestaande patroon in `TelegramAssistantServiceTest`.
- Geen wijziging aan `ConfigApi`, `SecretsEnvLoader`, `FactorySecrets`, `properties.default.env` of de
  documentatie: `docs/factory/secrets-local.md`, `functional-spec.md` en `docs/technical/external-systems.md`
  beschreven het beoogde gedrag al correct, dus er is geen spec bijgewerkt.

Bewijs:
- `mvn verify` (repo-root, incl. Testcontainers-e2e): BUILD SUCCESS, exitcode 0, 0 failures / 0 errors
  (reactor: factory-contracts, factory-common, softwarefactory, agentworker, dashboard-backend).
- `ClaudeAssistantClientConfigTest`: tests=6, failures=0, errors=0, skipped=0.
- `tools/audit-documentation`: `documentation-audit/v1: PASS`.

Let op bij lokaal draaien: `mvn -f softwarefactory/pom.xml test` alléén kan falen op een verouderde
`factory-contracts`-snapshot in `~/.m2` (bv. `Unresolved reference 'AgentNoDecision'`); bouw dan vanaf
de repo-root (reactor) in plaats van per module.
