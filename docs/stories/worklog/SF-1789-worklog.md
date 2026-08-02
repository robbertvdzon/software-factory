# SF-1789 - Worklog

## Story in eigen woorden

Wanneer Claude door quota niet verder kan, bewaart de factory de gestructureerde resetinformatie,
laat zij de actieve fase zonder fout staan en plant zij een automatische hervatting. De wachtstatus
moet persistent en zichtbaar zijn op story- en subtaakniveau, buiten poll-limieten blijven, geen
transient-retrybudget verbruiken en alleen bij `na-elke-stap` idempotent via Telegram melden.

## Checklist

[x]: Factory-instructies, refined story en bestaande architectuur gelezen.
[x]: Additief agentresultaatcontract en Claude `rate_limit_event`-parsing geïmplementeerd.
[x]: Quota/retryable/fatal-classificatie met quota-precedence geïmplementeerd.
[x]: Persistent `RetryAfter`-veld, migratie, mappings en poll-window-selectie toegevoegd.
[x]: Completion, story-/subtaak-wachten, timeoutprecedence en automatische hervatting toegevoegd.
[x]: Transient-capboekhouding en expliciete resetpaden quota-veilig gemaakt.
[x]: Dashboard- en Telegram-wachtstatus inclusief idempotentie/onderdrukking toegevoegd.
[x]: Unit-, integratie- en widgettests voor de geraakte gedragspaden toegevoegd.
[x]: Functional, technical en relevante UX-specs bijgewerkt.
[x]: Gerichte tests en volledige repository-verificatie groen uitgevoerd.
[x]: Self-review afgerond en bewijs genoteerd.
[x]: Review-loopback: gestructureerde rate-limitinformatie persistent in agent-runhistorie gemaakt.
[x]: Review-loopback: subtaakquota read-only zichtbaar gemaakt op de parent-story.
[x]: Herreview-loopback: vaste ruwe runlimiet uit de transient-capboekhouding verwijderd.
[x]: Herreview-loopback: Telegram-quota-idempotentie uitsluitend aan `RetryAfter` gebonden.

## Gedaan en waarom

- `AgentResultFile.rateLimit` is optioneel/defaulted gehouden, zodat oude writers/readers blijven
  werken. De Claude-parser bewaart het laatste event met een status en ondersteunt beide gangbare
  timestampnaamstijlen.
- `AgentFailurePolicy` classificeert expliciet; successen worden nooit quota en generieke rate-limit-
  fouten blijven transient. Completion berekent de hervatting met veiligheidsmarge/fallback en
  schrijft geen `Error` of fase-overgang.
- `retry_after` staat in de eigen tracker-DB en wordt altijd meegepolld. De coördinatoren toetsen de
  wachtstand vóór hard timeout en starten op/na het tijdstip dezelfde actieve rol opnieuw met een
  verse `AgentStartedAt`.
- Quota-records worden voor transient-telling overgeslagen, zodat zij niet tellen en ook geen reeks
  van echte transients onderbreken. Successen, fatale uitkomsten, dispatch en handmatige resetpaden
  wissen verouderde wachttijden.
- Het Flutter-dashboard gebruikt een amber wachtbadge/banner met lokale datum/tijd; Telegram gebruikt
  een timestampgebonden signature en verstuurt alleen bij `na-elke-stap` een informatief bericht.
- Bijgewerkte specs: `docs/factory/functional-spec.md`, `technical-spec.md`,
  `ux/screens/story-detail.md` en `ux/screens/stories.md`, zodat gedrag, persistence en presentatie
  voor volgende rollen actueel zijn.

## Review-loopback

- Reviewbevinding 2305 gereproduceerd: `AgentRunCompletionRecord` en `agent_runs` bewaarden alleen
  outcome/summary, waardoor een historische `error-claude-cli` met neutrale tekst en uitsluitend
  `rateLimit.status=rejected` later niet meer als quota herkenbaar was.
- Een additief/defaulted intern `AgentRunRateLimit`-veld toegevoegd aan completion- en runrecords.
  De wiredata wordt aan de runtimegrens expliciet gemapt, zodat de bestaande Modulith-grens tussen
  `core` en `contract` intact blijft.
- Migratie `V28__agent_run_rate_limit.sql` en JDBC-write/read-mapping bewaren status, `resetsAt` en
  `overageResetsAt` in de persistente agent-runhistorie. De transient-telling gebruikt de bewaarde
  status bij quotaclassificatie.
- De gemaskeerde regressietest gebruikt nu exact `error-claude-cli` + neutrale summary +
  gestructureerde `rejected`-status. Een extra Testcontainers-test bewijst de volledige Flyway/JDBC-
  roundtrip. `functional-spec.md` en `technical-spec.md` zijn hierop aangescherpt; de UX verandert
  niet en behoefde in deze loopback geen aanpassing.

## Verificatie

- Loopbackgerichte regressies groen: 17 completion-tests, 4 Modulith-architectuurtests en 10
  Testcontainers/Flyway/JDBC-integratietests (0 failures, 0 errors).
- `mvn -B --no-transfer-progress verify`: alle zes reactor-modules groen met 16 contracttests,
  55 common-tests, 701 softwarefactory-unittests, 76 integratietests, 61 agentworker-tests en 51
  dashboard-backendtests (0 failures, 0 errors).
- `tools/verify-repository` vanuit een schone build: exitcode 0. Omdat de sandbox wel de Docker-
  socket maar geen CLI op `PATH` aanbood, is voor de echte image-buildstage tijdelijk buiten de
  checkout de officiële statische Docker CLI 28.3.0 gebruikt. Alle 112 Flutter-tests waren groen.
- Quality-ratchet: groen, geen nieuwe bevindingen of suppressies en drie bestaande bevindingen
  opgelost. Module-dependency-drift, mini-reactor-smoke, Docker `build`-stage en
  `documentation-audit/v1` waren eveneens groen.
- `git diff --check` en conflictmarkercontrole: groen.

## Review 2026-08-02

- [blocker] Quota op een uitvoerende subtaak is niet zichtbaar als wachtstatus bij de parent-story.
  `AgentDispatcher` gebruikt voor subtaken de subtaak-key als `storyKey` en completion schrijft
  `RetryAfter` uitsluitend op die key. Het stories-overzicht en de storyheader lezen daarentegen
  alleen `fields.retryAfter` van de parent-story; alleen de subtaakrij aggregeert zijn eigen veld.
  Daardoor ontbreekt bij developer/reviewer/tester/summary/documentation-quota de vereiste melding
  “bij de story”, zowel in het overzicht als bovenaan storydetail. De widgettest maskeert dit door
  hetzelfde `retryAfter` handmatig op zowel parent als subtaak te zetten. Voeg een realistisch
  subtaak-only datapad toe en laat de storypresentatie de wachtende subtaak aggregeren/exponeren.
- Gerichte reviewerchecks groen: 82 JVM-tests voor contract, Claude-parser/client, classificatie,
  completion, recovery en Telegram; 10 Flutter-tests uit `story_detail_screen_test.dart`;
  `git diff --check` groen. Het volledige revisiongebonden bewijs uit de developer-run is aanwezig,
  maar bovenstaande acceptatie-afwijking vereist een developer-loopback.

## Review-loopback issue comment 2307

- De bevinding is opgelost als presentatiestatus, zonder `retryAfter` naar de parent te kopiëren:
  het persistente veld stuurt de orchestrator aan en hoort daarom uitsluitend op het daadwerkelijk
  wachtende issue te staan.
- `IssueReader.findQuotaWaitingIssues` en de Postgres-implementatie leveren alle wachtende issues
  ongelimiteerd en zonder comments/N+1. `DashboardQueryService` groepeert die op owner-story en
  exposeert `quotaRetryAfterByStory` voor het stories-overzicht.
- Storydetail bepaalt dezelfde effectieve wachttijd uit de al meegeleverde subtaken. De eerdere
  widgettest zet daarom alleen nog `retryAfter` op de subtaak; een nieuwe stories-widgettest en een
  bridge-regressietest dekken ook het overzichtsdatapad. De tracker-integratietest dekt de nieuwe
  read-query inclusief `parentKey`.
- `functional-spec.md`, `technical-spec.md`, `ux/screens/stories.md` en
  `ux/screens/story-detail.md` zijn aangescherpt met deze afgeleide parentpresentatie en de reden
  waarom de parent niet persistent wordt gemuteerd.

## Verificatie review-loopback issue comment 2307

- `BridgeRequestHandlerTest`: 33 tests groen, inclusief subtaak-only quota-aggregatie naar het
  storyoverzicht en bewijs dat het parentveld zelf leeg blijft.
- `TrackerCapabilityPersistenceE2eTest`: 23 Testcontainers/Flyway/Postgres-tests groen, inclusief
  de ongelimiteerde quota-read en behouden `parentKey`.
- `flutter analyze`: geen bevindingen. Gerichte storydetail-/stories-widgetrun: 11 tests groen.
- `mvn -B --no-transfer-progress verify` vanaf de repositoryroot: BUILD SUCCESS in 4m16s,
  exitcode 0, 0 failures en 0 errors.
- `tools/verify-repository`: exitcode 0. De schone Maven-run bevatte 16 contracttests, 55
  common-tests, 778 softwarefactory-tests (unit + integratie), 61 agentworker-tests en 51
  dashboard-backendtests; alle failures/errors waren 0. Ook Flutter analyze/pub-get/113 tests,
  mini-reactor-smoke, de echte Docker image-buildstage en documentatie-audit waren groen.
- `git diff --check` en conflictmarkercontrole: groen. Self-review vond geen resterende blocker.

## Herreview 2026-08-02 na issue comment 2308

- [bug] De transient-capboekhouding is niet onbeperkt quota-transparant. In
  `AgentRunCompletionService.retryableFailureCount` worden maximaal 1000 recente runs opgehaald en
  pas daarna quota-runs weggefilterd. Bij 999 of meer quota-runs tussen twee echte transient
  failures valt de oudere transient buiten het venster en wordt de reeks dus alsnog onderbroken.
  Ook een geldige `SF_MAX_TRANSIENT_RETRIES` boven 999 kan hierdoor nooit worden bereikt. Dit botst
  met de acceptatie-eis dat quota de omliggende transienttelling niet onderbreekt en dat de bestaande
  niet-quota retrylimiet behouden blijft. Laat de persistencequery voldoende niet-quota-uitkomsten
  ophalen/tellen, zonder een vaste ruwe-runlimiet die quota opnieuw betekenis geeft.
- [bug] De Telegram-idempotentiesleutel is niet uitsluitend aan het ingestelde `retryAfter`
  gebonden. `classify` maakt wel `claude-quota:<retryAfter>`, maar `notifyPending` voegt voor iedere
  contextmelding alsnog `quotaContext(...).hashCode()` toe. Als bijvoorbeeld de parent-lookup voor
  een wachtende subtaak bij de eerste melding degradeert en later herstelt, verandert de context en
  wordt voor exact hetzelfde `retryAfter` een tweede bericht verstuurd. Sluit QUOTA uit van de
  algemene context-hashverrijking en voeg een regressietest toe waarin de context bij gelijkblijvend
  `retryAfter` verandert.
- Gerichte herreviewchecks groen: 115 JVM-tests over contract, Claude-client/parser,
  classificatie, completion, recovery, Telegram en bridge; 11 Flutter-widgettests voor stories en
  storydetail; `git diff --check main...HEAD` groen. Het volledige developerbewijs voor de huidige
  revision is groen gerapporteerd, maar de twee ongedekte acceptatie-afwijkingen vereisen opnieuw
  een developer-loopback.

## Developer-loopback issue comment 2309

- `AgentRunCompletionService` vraagt niet langer maximaal 1000 ruwe runs op. De bestaande
  `AgentRunRepository.recentForRole` ondersteunt nu `excludeQuotaFailures=true` en filtert quotaruns
  in de JDBC-query vóórdat de limiet `SF_MAX_TRANSIENT_RETRIES + 1` wordt toegepast. Zo blijven
  oudere echte transients zichtbaar na meer dan 1000 quotaruns en werkt een geconfigureerde cap
  boven 999 eveneens.
- De repository heeft een compatibele default voor test-/andere implementaties; de Postgres-
  implementatie doet de begrensde filtering efficiënt in de database. Een integratietest bewijst
  dat de limiet pas ná quotafiltering geldt.
- `TelegramNotificationService` verrijkt signatures van vragen en voortgang nog steeds met de
  context-hash, maar sluit `QUOTA` daarvan uit. De quotamelding gebruikt nu exact
  `claude-quota:<retryAfter>`, terwijl de context wel in de berichttekst blijft staan.
- Nieuwe regressietests dekken 1001 tussenliggende quotaruns, `SF_MAX_TRANSIENT_RETRIES=1000`, de
  JDBC-filtervolgorde en parentcontext die tussen twee polls herstelt bij gelijkblijvend
  `RetryAfter`.
- `functional-spec.md` en `technical-spec.md` zijn aangescherpt met de onbeperkte quotatransparantie
  en de contextonafhankelijke Telegram-idempotentiesleutel. De UX zelf verandert niet.

## Verificatie developer-loopback issue comment 2309

- Gerichte unitrun: `AgentRunCompletionServiceTest` en `TelegramNotificationServiceTest`, 51 tests
  groen (0 failures, 0 errors).
- Postgres/Flyway-regressie `AgentCompletionRecoveryE2eTest`: 11 integratietests groen (0 failures,
  0 errors), inclusief quotafiltering vóór de querylimiet.
- Verplicht volledig vangnet `mvn verify` vanaf de repositoryroot: BUILD SUCCESS in 4m17s; alle zes
  reactormodules groen, 0 failures en 0 errors.
- De eerste repositorygate signaleerde twee nieuwe `TooManyFunctions`-ratchetbevindingen doordat de
  eerste opzet een extra repositoryfunctie toevoegde. Dit is zonder suppressies herwerkt naar de
  optionele vlag op de bestaande `recentForRole`-functie; de gerichte 51 tests en de quality-ratchet
  waren daarna opnieuw groen.
- Definitieve `tools/verify-repository` (`repository-verification/v1`): exitcode 0. De schone Maven-
  fase was BUILD SUCCESS in 4m17s; quality-ratchet had geen nieuwe bevindingen/suppressies, module-
  dependency-drift was schoon, Flutter analyze en alle 113 tests waren groen, mini-reactor-smoke en
  de echte Docker image-buildstage slaagden, en `documentation-audit/v1` rapporteerde PASS. Voor de
  image-build is tijdelijk buiten de checkout de officiële Docker CLI 28.3.0 gebruikt; client en
  engine waren beide 28.3.0.

## Herreview 2026-08-02 na issue comment 2310

- [bug] De productionele `excludeQuotaFailures`-query filtert niet alleen mislukte quotaruns maar
  iedere run waarvan outcome/samenvatting een quotawoord bevat, en ook iedere run met een
  blokkerende rate-limitstatus. Een succesvolle developer-run met bijvoorbeeld samenvatting
  “quota-route geïmplementeerd” verdwijnt daardoor uit de historie voordat `takeWhile` de
  transientreeks kan stoppen. Een latere transient kan dan ten onrechte eerdere transients van vóór
  die succesvolle run meetellen en de retrycap te vroeg bereiken. Dit botst met de policy en spec
  dat alleen mislukte runs quota zijn. Laat de persistencefilter successen behouden en voeg een
  Postgres-regressie toe met `transient -> succesvolle run met quotatekst/gestructureerd signaal ->
  transient`, waarbij de laatste run een nieuwe transientreeks start.
- Gerichte reviewerchecks groen: 27 JVM-tests (`AgentFailurePolicyTest` en
  `AgentRunCompletionServiceTest`), 11 Flutter-widgettests voor stories/storydetail en
  `git diff --check main...HEAD`. Het volledige revisiongebonden developerbewijs is groen, maar de
  bovenstaande acceptatie-afwijking vereist een developer-loopback.
