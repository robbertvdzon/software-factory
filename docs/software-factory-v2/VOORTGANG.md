# Voortgang overstap naar Agent Runtime v2

Laatst bijgewerkt: 2026-09-11

Deze file is de enige live voortgangsbron voor
[`stappenplan.md`](stappenplan.md). Bewijs wordt alleen als afgerond gemarkeerd wanneer het
controleerbaar aanwezig is; ontwerpstatus in de Runtime-documenten telt niet als uitvoeringsbewijs.

## Huidige gate

- Agent Runtime repositoryprotocol: **beschikbaar**. Het prerequisitedocument meldt uitvoering en
  het gepubliceerde productiecontract bevat de branchgerichte checkout, afzonderlijk
  `repositoryResult`, aliasselectie en publicatiemodi.
- Verificatie binnen de job: **beschikbaar**. Het verificatiedocument meldt volledige implementatie
  en productiecontrole; het productiecontract bevat `JobVerification` en `VerificationResult`.
- Software Factory-refactor: **gestart op `main`**.

## Stapstatus

| Stap | Status | Bewijs | Volgende gate |
|---:|---|---|---|
| 0 | bezig | Productie-health is groen; execution options en repositoryaliases zijn op 2026-09-11 uitgelezen; een echte `STRUCTURED_GENERATION`-probe eindigde `SUCCEEDED` met job `3a2c4970-b6b3-426c-830a-488aa47ef8af`. | Mock- en repositoryprobes afronden. De allowlist bevat `test-repository`, maar er is nog geen online worker die die alias aanbiedt. |
| 1 | afgerond | [`ontwerp-runtimevervanging.md`](ontwerp-runtimevervanging.md) legt vervanging, correlatie, prompts, Git-eigenaarschap, modelconfiguratie, quota en workspace-aannames vast. | Runtime-consumer bouwen. |
| 2 | bezig | Getypeerde `/v2`-contracten, HTTP-consumer, bearerconfiguratie, duurzame correlatietabel en modelconfiguratie per rol/project zijn toegevoegd. | Resultaatprojectie en refiner/planner/summarizer aansluiten; beheerscherm toevoegen. |
| 3 | niet gestart | — | Stap 2 groen. |
| 4 | niet gestart | — | Stap 3 groen. |
| 5 | niet gestart | — | Oude runner verwijderd en volledige reactor lokaal groen. |

## Contractcontrole

| Vereiste | Status | Bewijs |
|---|---|---|
| Bearer-auth per tenant | beschikbaar | Productie-aanroepen met de Software Factory-credential slagen. |
| Idempotente jobaanmaak | beschikbaar | `CreateJobRequest.idempotencyKey` is verplicht in het gepubliceerde `/v2`-contract. |
| `APPLICATION_WORK` / `STRUCTURED_GENERATION` | bewezen | Productieprobe `3a2c4970-b6b3-426c-830a-488aa47ef8af` leverde gevalideerd JSON-resultaat en usage. |
| `REPOSITORY_WORK` / `REPOSITORY_AGENT` | beschikbaar | Productie execution-options tonen online capaciteit voor alle aangeboden modellen. |
| Bestaande branch via alias | beschikbaar | OpenAPI bevat `RepositoryCheckout(alias, branch, publicationMode)`; Runtime-document meldt uitvoering. |
| Read-only branchjob | beschikbaar | Contract valideert `APPLICATION_WORK` + `REPOSITORY_AGENT` + `NONE`. |
| Afzonderlijk AI- en repositoryresultaat | beschikbaar | `JobResultView.result` en `JobResultView.repositoryResult` zijn afzonderlijke velden. |
| `NO_CHANGES` | beschikbaar | `RepositoryPublicationStatus.NO_CHANGES` staat in het productiecontract. |
| `BRANCH_CHANGED` zonder force-push | beschikbaar | Normatieve Runtime-documentatie meldt productie-uitvoering; consumerafhandeling volgt in stap 3. |
| Verificatie en herstelrondes | beschikbaar | Contract bevat `REPOSITORY_CONFIG`, `maxRepairAttempts` en getypeerd bewijs. |
| Resultaat bij terminale verificatiefout | beschikbaar | Resultaatendpoint documenteert gevalideerd resultaat plus bewijs bij terminale verificatiefout. |
| Events, artifacts, usage en cancel | beschikbaar | Gepubliceerde endpoints en contracttypen aanwezig; consumerimplementatie volgt in stap 2. |
| Repositoryaliascatalogus | bewezen | Productie meldde `software-factory` en de targetprojectaliases beschikbaar op één online worker. |
| Mockuitvoering | beschikbaar, live probe open | Contract en Runtime-tests ondersteunen mocks; productie verbiedt mocks terecht. Lokale/acceptatieprobe volgt zonder productieconsumer te muteren. |
| Tijdelijke repositoryketen | geblokkeerd voor live probe | `test-repository` is toegestaan maar niet beschikbaar op een online worker. Er wordt niet uitgeweken naar een productierepository. |

## Besluiten en blokkades

- De ontbrekende online `test-repository`-alias blokkeert alleen de destructieve live probe uit
  stap 0, niet de consumerimplementatie: dezelfde contracten zijn in Agent Runtime zelf getest en
  productie toont de echte repositorycapaciteit.
- Er komt geen lokale workaround, gedeelde checkout of netwerkvolume.
- Commits tot en met stap 4 eindigen op `[skip ci]`; stap 5 activeert de normale pipeline.

## Bewijslog

### 2026-09-11 — start stap 0 en 1

- Baseline: `bcf0afae7f9947a9a31e00bcab4fdb5f87c26038` op `main`, gelijk aan `origin/main`.
- Productie `GET /healthz`: `UP`.
- Productie `GET /v2/execution-options` voor `STRUCTURED_GENERATION` en `REPOSITORY_AGENT`:
  online worker beschikbaar.
- Productie `GET /v2/repository-aliases`: alle beheerde projectaliases beschikbaar;
  `test-repository` toegestaan maar zonder matching online worker.
- Productieprobe: één kleine OpenAI `STRUCTURED_GENERATION`-job, één attempt, getypeerd resultaat,
  geen artifacts.
- Geen repositorybranch, PR of targetrepository is voor deze probe gewijzigd.

### 2026-09-11 — begin stap 2

- Toegevoegd: getypeerde requests en responses voor jobs, resultaten, repositorybewijs,
  verificatiebewijs, events, execution options en aliases.
- Toegevoegd: HTTP-consumer voor create, status, resultaat, events, annulering en catalogi.
- Configuratie gebruikt `SF_AGENT_RUNTIME_URL` en het uitsluitend extern aangeleverde secret
  `SF_AGENT_RUNTIME_TOKEN`; het bestaande secretmechanisme is niet gewijzigd.
- Test: `mvn -B --no-transfer-progress -pl softwarefactory -am
  -Dtest=AgentRuntimeV2HttpClientTest -Dsurefire.failIfNoSpecifiedTests=false test` — groen.
- Migratie `V36` voegt rol-/projectconfiguratie en de duurzame koppeling tussen `agent_runs` en
  Runtime-jobs toe. Alle bestaande rijen en tabellen blijven intact.
- De configuratieservice valideert iedere nieuwe vendor/model/mode-combinatie live tegen de
  execution options van Runtime. De defaults zijn expliciet en er is geen model-fallback.
- Compilebewijs na de migratie en repositorylaag: `mvn -B --no-transfer-progress -pl
  softwarefactory -am -DskipTests compile` — groen.
