# SF-385 - nightly: Consistentie: afwijkende patronen gladstrijken

## Story

nightly: Consistentie: afwijkende patronen gladstrijken

<!-- refined-by-factory -->

## Scope

Doel: zuiver consistentiewerk in de software-factory codebase (`softwarefactory/` en `agentworker/`, Kotlin/Spring Boot). Zoek plekken waar code afwijkt van de norm die elders in dezelfde codebase al gangbaar is, en breng die in lijn — zonder het functionele gedrag te veranderen.

In scope (alleen waar een duidelijke meerderheidsnorm bestaat om naar te conformeren):
- **Naamgeving**: afwijkende namen voor variabelen, functies, parameters, classes of bestanden t.o.v. de dominante conventie in de buurliggende code.
- **Structuur**: afwijkende package-/bestandsindeling of opbouw van vergelijkbare componenten (bijv. handlers, services, repositories) waar de rest een duidelijk patroon volgt.
- **Error-handling**: hetzelfde foutscenario dat op meerdere manieren wordt afgehandeld; gelijktrekken naar het patroon dat elders de norm is.
- **Logging**: afwijkend log-niveau, -formaat of -fraseologie t.o.v. vergelijkbare plekken.
- **API-/code-conventies**: bijv. inconsistente toepassing van bestaande conventies zoals de `SF_`-prefix voor env-vars, of twee oplossingen voor hetzelfde probleem die ongelijk zijn.

Buiten scope:
- Nieuwe features, gedragswijzigingen, bugfixes of refactors die observeerbaar gedrag raken.
- Het wijzigen van publieke contracten (API-responses, DB-schema/Flyway-migraties, YouTrack-veldnamen/-waarden, env-var-namen) — die zijn zelf "gedrag".
- Stilistische voorkeuren waar géén bestaande meerderheidsnorm is; verzin geen nieuwe norm.
- Aanpassingen aan integratietests (zie acceptatiecriteria).

Als er geen voldoende duidelijke, gedrags-neutrale inconsistenties te vinden zijn, is een no-op met een toelichtende worklog-notitie een geldige uitkomst (geen geforceerde wijzigingen).

## Acceptance criteria

1. Elke wijziging is puur consistentie/conformering: het **functionele gedrag blijft exact hetzelfde** (geen wijziging in observeerbare output, API-contracten, DB-schema, env-var-namen of YouTrack-velden).
2. Elke conformering verwijst naar een bestaande meerderheidsnorm in dezelfde codebase; de gekozen "norm" is die welke al het meest voorkomt op vergelijkbare plekken.
3. Alle bestaande tests slagen: `mvn -f softwarefactory/pom.xml test` en `mvn -f agentworker/pom.xml test` (of `mvn test` vanaf root) zijn groen.
4. **Integratietests worden niet aangepast.** Als een wijziging een integratietest zou breken, of alleen groen te krijgen is door een integratietest aan te passen, dan verandert het gedrag → die wijziging wordt teruggedraaid; lukt de story dan niet veilig, dan in error gaan i.p.v. de test wijzigen.
5. Bij twijfel of een wijziging gedrag verandert: de wijziging wordt niet uitgevoerd (of de story gaat in error). Liever minder, zeker-veilige wijzigingen dan brede risicovolle.
6. `docs/stories/worklog/SF-385-worklog.md` wordt bijgewerkt met: welke inconsistenties zijn aangepakt, naar welke norm is geconformeerd en waarom dat gedrags-neutraal is. Bij een no-op staat dat expliciet onderbouwd in de worklog.
7. De diff bevat uitsluitend de consistentie-wijzigingen (plus de worklog-update); geen ongerelateerde aanpassingen.

## Aannames

- "Functioneel gedrag" omvat observeerbare output, API-/HTTP-contracten, DB-schema en migraties, env-var-namen (`SF_*`), en YouTrack-veld-/enumnamen en -waarden; conformering die deze raakt valt buiten scope.
- Pure interne hernoemingen (lokale variabelen, private members, niet-publieke functienamen) en gelijktrekken van logging/error-handling-patronen gelden als gedrags-neutraal, mits tests groen blijven.
- Logniveaus/-teksten worden als gedrags-neutraal beschouwd, tenzij een test of extern systeem aantoonbaar op de exacte tekst/het niveau leunt — in dat geval niet wijzigen.
- "Integratietests" = de bestaande testsuite die als vangnet dient; deze blijven ongewijzigd. Unit-tests die louter een interne hernoeming volgen, mogen mee-aangepast worden zolang ze geen gedrag herdefiniëren.
- De omvang is bewust niet gefixeerd op een aantal wijzigingen; de developer kiest een behapbare, veilige set en documenteert de keuze. Een lege (no-op) uitkomst met onderbouwing is acceptabel.
- Scope blijft binnen de twee Maven-modules (`softwarefactory/`, `agentworker/`); `docs/` en wireframes vallen erbuiten, behalve de verplichte worklog-update.

## Eindsamenvatting

## Eindsamenvatting — SF-385: Consistentie, afwijkende patronen gladstrijken

**Wat is gebouwd**
Een zuiver gedrags-neutrale consistentie-conformering in de twee Maven-modules (`softwarefactory/`, `agentworker/`). De codebase importeert vrijwel overal expliciet per type (~1068 expliciete imports); drie main-bestanden gebruikten nog een project-interne wildcard-import. Deze 5 wildcard-imports zijn vervangen door expliciete imports, conform de duidelijke meerderheidsnorm:

- `softwarefactory/.../youtrack/services/AgentCommentContext.kt`
- `softwarefactory/.../youtrack/clients/YouTrackClient.kt`
- `agentworker/.../agentworker/cli/AgentCli.kt`

De diff bevat uitsluitend deze import-blokken plus de verplichte worklog-update (4 bestanden, +154/-5 regels).

**Gemaakte keuzes**
- Bewust klein en zeker-veilig gehouden: alleen import-vorm, geen enkele regel logica, signatuur, string, log-niveau of -tekst gewijzigd.
- De wildcard → expliciet-conformering is gekozen omdat de meerderheidsnorm hier ondubbelzinnig is (in twee bestanden stond al een expliciete import náást de wildcard).

**Wat is getest**
- `test-compile` groen voor beide modules (compiler bevestigt dat alle referenties resolven).
- `agentworker` volledige suite: 34 tests, 0 failures, 0 errors.
- `softwarefactory` aangeraakte bestanden (`YouTrackClientTest`, `AgentCommentContextTest`): groen.
- Volledige softwarefactory-suite: 390 tests, **0 failures**, 14 errors — allemaal pre-existing/omgevings-gerelateerd (Docker-e2e, Testcontainers/Postgres zonder daemon, bekende Modulith-cycle). Geen regressie; failures-count is het regressiesignaal.
- Geen testbestand of integratietest aangepast.

**Bewust niet gedaan**
- `LoggerFactory.getLogger(ProjectRepoResolver::class.java)` niet gelijkgetrokken naar `javaClass`: staat in een `companion object`, waar `javaClass` de logger-naam zou veranderen → niet gedrags-neutraal.
- Overige stijlverschillen (collection-constructors, elvis-varianten, get/find/read-naamgeving) bleken contextueel betekenisvol of zonder duidelijke meerderheidsnorm → conform scope niet aangeraakt.
- De pre-existing dubbele `AgentRole` (`core.` vs `youtrack.`) valt buiten scope (kan publieke namen raken).
- Geen `docs/factory`-spec geraakt; `SF_`-conventies ongewijzigd.

Story is review-approved en test-approved; klaar voor documentatie/merge.
