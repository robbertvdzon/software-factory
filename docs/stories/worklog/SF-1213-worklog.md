# SF-1213 - Worklog

Story-context bij eerste pickup:
Afkappen i.p.v. afwijzen van oversized event-payloads in CompletionInboxRepository

Pas accept()/validateCompletion() in CompletionInboxRepository.kt aan zodat individuele events-payloads > MAX_EVENT_BYTES (262.144 bytes) worden afgekapt tot ≤ de limiet (met een zichtbare afkap-marker, geldige UTF-8) in plaats van de hele completion te laten afwijzen. Overige, ongewijzigde events blijven exact gelijk. Log een WARN-regel bij afkapping met storyKey, containerName en aantal/omvang van de afgekapte events (voeg een logger toe aan het bestand). Als de totale payload ná afkapping nog steeds MAX_PAYLOAD_BYTES overschrijdt, blijft de completion terecht volledig afgewezen (bestaand gedrag). Pas de bestaande test in AgentCompletionRecoveryE2eTest.kt (rond regel 240-244) aan zodat het oversized-event-scenario niet langer een CompletionPayloadRejectedException verwacht maar een succesvolle accept() met afgekapte payload, en voeg nieuwe tests toe die het afkap-gedrag (marker, ≤ limiet, overige events ongewijzigd) en de blijvende-afwijzing-bij-te-grote-totaalpayload expliciet verifiëren. Bestaande tests voor de overige limieten (container/story-key/summary/collection-entries/totale payload) moeten ongewijzigd blijven slagen.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.

## SF-1214 (development)

- `CompletionInboxRepository.kt`:
  - `accept()` roept nu eerst `truncateOversizedEvents(request)` aan, vóórdat de request naar
    JSON geserialiseerd en gevalideerd wordt. Events met een payload > `MAX_EVENT_BYTES`
    (262.144 bytes) worden afgekapt tot ≤ die limiet (incl. marker) via de nieuwe helpers
    `truncateEventPayload`/`truncateToUtf8ByteBudget`; die laatste kapt af op een UTF-8
    teken-grens (backt af zolang het volgende byte een continuation-byte is) zodat de
    afgekapte string altijd geldige UTF-8 blijft, ook bij multibyte-tekens.
  - De marker (`"...[afgekapt: origineel N bytes]"`) vermeldt het originele aantal bytes; de
    marker-tekst hoeft geen geldige JSON te blijven (analoog aan de bestaande
    `eventsForStory`-afkap in `FactoryDashboardRepository.kt`, SF-1199).
  - Bij minstens één afgekapt event wordt een WARN gelogd (nieuwe SLF4J-logger op de klasse)
    met het aantal afgekapte events, hun totale originele bytegrootte, `storyKey` en
    `containerName`.
  - De oude event-size-rejectie in `validateCompletion()` is verwijderd (die klasse fouten
    wordt nu vóór validatie al afgekapt i.p.v. afgewezen); de overige limieten
    (container/story-key/summary/collection-entries/totale payload) zijn ongewijzigd.
  - Als de totale payload ná afkapping nog steeds `MAX_PAYLOAD_BYTES` overschrijdt, blijft
    `validateCompletion()` de completion terecht volledig afwijzen met
    `CompletionPayloadRejectedException` (bestaand, ongewijzigd gedrag op dat punt).
- `AgentCompletionRecoveryE2eTest.kt`:
  - Het oversized-event-scenario in `bounded retry manual audit payload limits...` (was
    regel 240-244) verwacht niet langer een `CompletionPayloadRejectedException`, maar een
    succesvolle `accept()` met een afgekapte payload (≤ limiet, marker aanwezig).
  - Nieuwe test `oversized event payload is truncated on accept while other events stay
    unchanged`: verifieert marker-inhoud (incl. origineel bytenummer), dat de afgekapte
    payload ≤ `MAX_EVENT_BYTES` is, en dat een tweede, niet-oversized event in dezelfde
    completion ongewijzigd blijft (zowel in het `accept()`-resultaat als in de opgeslagen
    `payload_json`).
  - Nieuwe test `completion is still rejected when total payload exceeds the limit even
    after truncating events`: 40 oversized events (elk 300.000 bytes) leiden na afkapping
    nog steeds tot een payload > `MAX_PAYLOAD_BYTES` (8 MiB) → nog steeds
    `CompletionPayloadRejectedException`.
- Geen wijzigingen nodig in `docs/factory/functional-spec.md`/`technical-spec.md`/`ux/`: dit
  is een interne fix in de durable-completion-inbox zonder nieuw extern gedrag/contract
  (bestaande `docs/factory/durable-completion.md` beschrijft de MAX_*-limieten niet in detail
  op afkap-vs-afwijs-niveau, dus geen bestaande claim die nu onjuist is geworden).
- Getest: `mvn verify` vanaf de repo-root — BUILD SUCCESS, alle modules groen, softwarefactory
  module inclusief Testcontainers-e2e-tests (Docker was in deze sandbox-run beschikbaar).
  `AgentCompletionRecoveryE2eTest`: Tests run: 7, Failures: 0, Errors: 0 (was 5, +2 nieuw).
  Totaal (laatste run): reactor SUCCESS voor factory-contracts, factory-common,
  softwarefactory, agentworker, softwarefactory-dashboard-backend; 0 failures/errors overal.
