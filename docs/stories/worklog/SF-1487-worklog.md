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

Review (SF-1583, reviewer):
- Volledige story-diff t.o.v. `main` beoordeeld: alleen `ClaudeAssistantClient.kt` (±6 regels),
  de nieuwe `ClaudeAssistantClientConfigTest.kt` en dit worklog. Geen scope creep.
- Alle acceptatiecriteria 1-8 en 11 nagelopen en akkoord: geen `System.getenv` meer voor beide
  sleutels, identieke validatieketens/defaults, env wint nog steeds van bestandslagen
  (`SecretsEnvLoader.resolvedValues() = fileValues + environment`, r42), constructorsignatuur
  ongewijzigd, beide TODO's weg, geen resterende verwijzing naar de oude companion-`IMAGE`.
- Specs consistent: `docs/factory/secrets-local.md` (r96/121-122), `functional-spec.md` (r256/263)
  en `docs/technical/external-systems.md` beschreven dit gedrag al; geen doc-wijziging nodig.
- [suggestie, niet blokkerend] `resolvedValues()` wordt tweemaal aangeroepen tijdens constructie;
  één lokale val zou dat kunnen bundelen. Functioneel identiek, dus niet aangepast.
- Testbewijs (`mvn verify` groen op deze revisie) overgenomen uit de developer-run; niet herhaald.
- Besluit: goedgekeurd.

Test (SF-1584, tester):
- Story-diff t.o.v. `main` gecontroleerd: alleen `ClaudeAssistantClient.kt`,
  `ClaudeAssistantClientConfigTest.kt` en dit worklog. Geen andere wijzigingen in de working tree.
- AC1/4/5/7/11 statisch geverifieerd op de bron: geen `System.getenv` meer voor beide sleutels,
  beide TODO-commentaren weg, companion-`IMAGE` verwijderd en geen resterende `IMAGE`-referentie,
  `dockerCommand()` (r186) en de warn-log in `runDocker()` (r239) gebruiken de nieuwe instance-val
  `image`, defaults `assistant:local` / `DEFAULT_TIMEOUT_SECONDS` (3600) ongewijzigd.
- AC2/AC3 (bestandslagen worden gehonoreerd, echte env wint) geverifieerd via de keten
  `ConfigApi.default()` → `SecretsEnvLoader.resolvedValues() = fileValues + environment` (r42), met
  `fileValues` = properties.default.env < properties.env < secrets.env (r29-35); bestaand
  gedragsbewijs in `SecretsEnvLoaderTest` ("environment variables win over file values…" en
  "properties layer in order defaults below overrides below secrets below env").
- AC6: constructorsignatuur incl. defaults ongewijzigd; alle bestaande call-sites compileren
  (bevestigd door de groene build).
- AC8/9/10: volledig vangnet vanaf repo-root: `mvn -B --no-transfer-progress clean verify`
  → BUILD SUCCESS, exit 0, 0 failures / 0 errors over alle modules
  (factory-contracts 16, factory-common 52, softwarefactory 683 unit + 70 e2e, agentworker 60,
  dashboard-backend 50). `ClaudeAssistantClientConfigTest` 6/6 groen.
- Geen preview/browser-scenario van toepassing: deze story raakt geen UI en de assistent-container
  vergt Docker+Telegram; het gedrag van het samengestelde docker-commando is in plaats daarvan
  gedekt door de reflectietests op `dockerCommand()`.
- Flake gemeld (pre-existing, niet story-gerelateerd): in de eerste volledige `clean verify`-run
  crashte de surefire-fork tijdens `FactoryApiControllerTest` ("forked VM terminated…, Process Exit
  Code: 0"). Oorzaak: die test roept de ECHTE `FactoryProcessService.requestRestart()` aan, die een
  non-daemon thread start welke na 600 ms `Runtime.getRuntime().halt(0)` uitvoert en zo de test-JVM
  hard afsluit. Race: valt die halt binnen de resterende testduur van de fork, dan crasht de run.
  Test en service staan onveranderd op `main` (071c3ac). Geïsoleerde herrun 5/5 groen en de tweede
  volledige `clean verify` volledig groen → behandeld als flake, geen regressie van deze story.
  Aanbeveling: `FactoryApiControllerTest` een stub/fake `FactoryProcessControl` laten gebruiken
  in plaats van de echte `FactoryProcessService`.
- Besluit: goedgekeurd (tested).
