# SF-1775 - Story pauzeren bij Claude-quota en automatisch hervatten zodra de quota terug is

## Story

Story pauzeren bij Claude-quota en automatisch hervatten zodra de quota terug is

<!-- refined-by-factory -->

## Samenvatting

Als Claude tijdelijk niet verder kan door een gebruiks- of quotumlimiet, mag de story niet vastlopen met een fout.
De factory toont dat de story wacht en tot wanneer.
Zodra de limiet is hersteld, gaat dezelfde stap automatisch verder.
Andere tijdelijke en permanente fouten blijven zich gedragen zoals nu.

## Scope

- Lees het laatste bruikbare `rate_limit_event` uit de Claude stream en leg minimaal `status`, `resetsAt` en `overageResetsAt` vast in het agentresultaat. Breid het gedeelde wire-contract alleen achterwaartscompatibel uit, met defaults voor oudere writers en readers.
- Introduceer in `AgentFailurePolicy` een expliciete classificatie met minimaal `quota`, `retryable` en `fatal`. Classificeer alleen mislukte runs als quota:
  - op basis van een expliciet blokkerend Claude-rate-limitsignaal; of
  - case-insensitive op de teksten `usage limit reached`, `quota` en `credit balance`.
- Een los `allowed`- of `allowed_warning`-event maakt een succesvolle of anderszins mislukte run niet automatisch tot quota. Een quota-specifieke classificatie heeft voorrang op de bestaande generieke `rate limit`-retry.
- Voeg een persistent, optioneel `retryAfter`-tijdstip toe aan tracker-issues en alle bijbehorende contract-, mapping- en databasepaden. Het veld kan zowel op een story tijdens refining/planning als op een agent-subtaak staan.
- Bij een quota-uitkomst:
  - blijft de actieve story- of subtaakfase ongewijzigd;
  - blijft `Error` leeg;
  - wordt `retryAfter` gevuld;
  - wordt `Paused` niet gebruikt, omdat dat de bestaande handmatige pauzestand is.
- Bepaal `retryAfter` uit de laatste geldige toekomstige `resetsAt`, met één minuut veiligheidsmarge. Ontbreekt een bruikbare toekomstige resettijd, probeer dan na vijftien minuten opnieuw. Een volgende quota-uitkomst mag het tijdstip opnieuw bijstellen.
- Zolang `now < retryAfter` skipt de orchestrator de betreffende story of subtaak vóór de hard-timeoutcontrole en vóór dispatch.
- Zodra `retryAfter` bereikt is, wist de orchestrator de wachttoestand en dispatcht hij dezelfde rol opnieuw. De nieuwe run krijgt een vers `agentStartedAt`. Succes, een terminale uitkomst en expliciete reset-/herimplementatiepaden mogen geen verouderde `retryAfter` laten staan.
- Quota-runs tellen niet mee voor `SF_MAX_TRANSIENT_RETRIES` en onderbreken ook niet de telling van omliggende echte transient failures. De bestaande retry- en fatal-logica voor niet-quota-uitkomsten blijft ongewijzigd.
- Zorg dat issues met `retryAfter` altijd in de orchestrator-pollset blijven, ook buiten de recente top-N en zowel op story- als subtaakniveau. De bestaande extra selectie van niet-terminale subtaken mag hiervoor worden hergebruikt, maar is op zichzelf niet voldoende voor wachtende stories.
- Toon in het dashboard bij de story en de getroffen stap duidelijk “Gepauzeerd wegens Claude-quota tot <tijdstip>”. Toon dit als wachtstatus en niet als foutstatus.
- Voeg een idempotente Telegram-statusmelding toe met story/subtaak en het wachttijdstip. Behandel deze als informatieve voortgang: versturen bij `na-elke-stap`, onderdrukken bij `geen`, `als-klaar` en `als-klaar-en-gedeployed`. Er wordt geen ERROR-melding verstuurd. Een hervattingsmelding is niet vereist.
- Het preventief uitstellen van nieuwe Claude-runs bij `allowed_warning` is optioneel en geen acceptatievoorwaarde. Als dit wordt toegevoegd, mag het een geslaagde run niet alsnog als quota-uitkomst behandelen.
- Copilot en Codex hoeven geen gestructureerde quota-events te leveren. Een generiek datamodel mag gedeeld worden zolang hun bestaande gedrag niet verandert.

## Acceptance criteria

- Gegeven een mislukte Claude-run met een blokkerend rate-limitsignaal of quota-specifieke fouttekst, wanneer de completion wordt verwerkt, dan blijft de actieve fase staan, is `Error` leeg en bevat het issue een toekomstig `retryAfter`.
- `status`, `resetsAt` en `overageResetsAt` uit een Claude `rate_limit_event` worden correct geparsed en via het agentresultaat aan de factory doorgegeven.
- Een succesvol resultaat met `status=allowed` of `status=allowed_warning` wordt niet als quota-fout behandeld. Een niet-quota-fout met alleen de bestaande generieke `rate limit`-tekst behoudt het bestaande transient-retrygedrag.
- Vóór `retryAfter` vindt geen nieuwe dispatch plaats en kan ook na meer dan `SF_AGENT_HARD_TIMEOUT_MINUTES` geen hard-timeoutfout ontstaan.
- Op of na `retryAfter` wordt dezelfde rol zonder menselijke actie opnieuw gedispatcht, met een nieuw starttijdstip en zonder achterblijvende quota-wachtstatus.
- Een quota-uitkomst kan meer dan `SF_MAX_TRANSIENT_RETRIES` keren voorkomen zonder de transient-cap of een permanente fout te veroorzaken. Niet-quota transient en fatal failures behouden hun huidige limieten en gevolgen.
- Een wachtende story of subtaak wordt nog gepolld wanneer meer recent gewijzigde issues de normale querylimiet vullen.
- Storydetail en subtaakoverzicht tonen de quota-wachtstatus met een ondubbelzinnig tijdstip; de toestand wordt nergens als Error gepresenteerd.
- Bij meldingen=`na-elke-stap` wordt per ingesteld `retryAfter` hoogstens één informatieve Telegram-melding verstuurd. De andere meldingenstanden onderdrukken deze volgens hun bestaande betekenis.
- Tests dekken minimaal: Claude-eventparsing en contractcompatibiliteit, classificatie en precedence, completion naar wachttoestand, story- en subtaakherstel, hervatting, hard timeout tijdens een lange pauze, transient-capboekhouding, poll-window, dashboardweergave en Telegram-onderdrukking/idempotentie.

## Aannames

- De quota-afhandeling geldt voor alle Claude-agentrollen, inclusief refiner en planner; “story pauzeren” is dus niet beperkt tot uitvoerende subtaken.
- Tijden worden als absoluut tijdstip opgeslagen. Dashboard en Telegram volgen de bestaande tijdzoneconventie en tonen voldoende datum-/tijdzonecontext om verwarring rond middernacht of zomertijd te voorkomen.
- `resetsAt` en `overageResetsAt` zijn Unix-timestamps in seconden. `resetsAt` is leidend voor hervatting; `overageResetsAt` wordt als aanvullende diagnostische informatie bewaard.
- Een ontbrekende resettijd veroorzaakt periodieke hercontrole en geen permanente fout. Handmatig ingrijpen blijft mogelijk via de bestaande reset-/herimplementatiecommando’s.
- De handmatige `Paused`-stand en globale creditpauzes blijven onafhankelijk van deze automatische Claude-quota-wachtstand.

## Eindsamenvatting SF-1775

De factory herkent blokkerende Claude-quota-uitkomsten en bewaart de gestructureerde rate-limitinformatie. Story of subtaak blijft foutloos in de actieve fase wachten tot `retryAfter`, wordt buiten poll-limieten gevolgd en hervat daarna automatisch met een nieuwe run. Quota-uitkomsten verbruiken of onderbreken het transient-retrybudget niet.

Het dashboard toont de wachtstatus op zowel de getroffen stap als de bovenliggende story. Telegram verstuurt bij `na-elke-stap` maximaal één informatieve melding per ingesteld hervattijdstip. De persistente wachtstatus blijft uitsluitend op het werkelijk getroffen issue staan; de parentstatus wordt voor presentatie afgeleid.

Bewust niet toegevoegd: preventief uitstel bij `allowed_warning`, een hervattingsmelding en gestructureerde quota-events voor Copilot/Codex. Handmatige pauzes en globale creditpauzes blijven ongewijzigd.

De story-brede testfase is goedgekeurd. Gerichte contract-, parser-, classificatie-, recovery-, persistence-, polling-, dashboard- en Telegram-regressies zijn groen. Ook de volledige Maven-verificatie over alle zes modules, Flutter-analyse en alle 113 Flutter-tests, quality-ratchet, Docker-imagebuild en documentatie-audit zijn geslaagd zonder failures of errors.
