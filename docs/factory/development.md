# Development

## Toolchain

- JDK 21 en Maven;
- Docker voor Testcontainers en optionele lokale PostgreSQL, niet voor agentuitvoering;
- Flutter voor `dashboard-frontend`;
- toegang tot Agent Runtime v2 voor echte agentjobs.

## Modules

De Maven-reactor bevat vier modules:

- `factory-contracts`: bridge- en integratiewiretypes;
- `factory-common`: gedeelde configuratie, projectcatalogus en integratieprimitives;
- `softwarefactory`: orchestrator, tracker, pipeline, Runtime-consumer, Telegram, audits en
  maintenance;
- `dashboard-backend`: remote dashboard-API en huidige WebSocketbridge.

`dashboard-frontend` is een zelfstandige Flutter-app. Er is geen `agentworker`-module en er worden
geen lokale agentimages gebouwd.

## Bouwen en testen

```bash
# alle snelle Maven-tests
mvn -B --no-transfer-progress test

# volledige reactor met integratietests en kwaliteitsgates
mvn -B --no-transfer-progress verify

# alleen de hoofdapp plus afhankelijkheden
mvn -B --no-transfer-progress -pl softwarefactory -am test

# frontend
tools/verify-dashboard-frontend
```

Gerichte tests kunnen met `-Dtest=NaamVanTest -Dsurefire.failIfNoSpecifiedTests=false`. Database-
en migratietests gebruiken Testcontainers.

## Lokaal starten

```bash
./factory local-services
./factory start
```

Of start `SoftwareFactoryApplication` vanuit de IDE. Configuratie wordt geladen uit
`properties.default.env`, `properties.env`, `secrets.env` en ten slotte echte environmentvariabelen.
Zie [`secrets-local.md`](secrets-local.md).

`factory-loop.sh` is een tijdelijke lokale proceswrapper. Hij herstart de Spring-app na exit en
bouwt geen agentimages. In het latere OpenShift-topologieplan verdwijnt ook deze hostafhankelijkheid.

## Agent Runtime tijdens ontwikkeling

Gebruik voor domeintests `TestAgentRuntime`: die bootst asynchrone Runtime-v2-completions en
repositoryresultaten na. Gebruik voor contract-/productieproeven een geregistreerde
repositoryalias en bij voorkeur `mock/mock/MOCK`.

De consumer bewaakt:

- één logische agentstap per duurzame `runtime_job_id` en idempotentiesleutel;
- gevalideerde resultaten, artifacts, usage en kosten;
- repositoryalias, branch, publicatiemodus en `checkoutCommitSha`;
- afzonderlijk AI-resultaat, `repositoryResult` en `verificationResult`;
- cancel, timeout, quota, retries en herstel na restart.

Repositoryoverdracht tussen jobs verloopt alleen via de remote storybranch. Voeg geen lokale
workspace of netwerkshare toe.

## Verificatieconfig van targetprojecten

Muterende jobs lezen `.factory/verification.yaml` in de Runtime-checkout. Commands zijn expliciete
argv-lijsten, zonder impliciete shell. Een ontbrekende/ongeldige config, missende tool, timeout,
non-zero exitcode of ongeldig bewijs faalt dicht. De Runtime-worker mag maximaal het ingestelde
aantal herstelrondes met dezelfde AI-agent uitvoeren en pusht uitsluitend na groen bewijs.

Iedere command draait geïsoleerd in een eigen executioncontainer. Stappen die dezelfde lokale
toolcache nodig hebben staan daarom samen in één versioned repositoryscript. Voor Flutter is dat
`tools/verify-dashboard-frontend`: `pub get`, `analyze --no-pub` en `test --no-pub` delen zo één
packagecache zonder een shell-string in `.factory/verification.yaml`.

## Codeconventies

- Kotlin- en Dartproductiecode krijgt gerichte tests.
- Module-API's staan in de moduleroot of als benoemde interface; implementaties blijven intern.
- Gebruik `ConfigApi` in plaats van verspreide `System.getenv`-calls.
- Voeg nooit providercredentials of targetprojectsecrets aan prompts, logs, artifacts of tests toe.
- Houd `docs/software-factory-v2/VOORTGANG.md` bij voor migratiebewijs en concrete gates.
