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
