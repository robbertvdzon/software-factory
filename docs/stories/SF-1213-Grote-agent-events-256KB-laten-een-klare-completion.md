# SF-1213 - Grote agent-events (>256KB) laten een klare completion eeuwig vastlopen

## Story

Grote agent-events (>256KB) laten een klare completion eeuwig vastlopen

<!-- refined-by-factory -->

## Scope

Individuele `events`-payloads die groter zijn dan `MAX_EVENT_BYTES` (262.144 bytes) mogen niet langer de HELE completion laten afwijzen in `validateCompletion()` (`CompletionInboxRepository.kt`). In plaats daarvan wordt zo'n event-payload afgekapt tot een geldige, kleinere payload (met een duidelijke afkap-marker, bv. `"...[afgekapt: origineel N bytes]"`), zodat `accept()` de completion alsnog accepteert en de subtaak niet langer eeuwig op `testing`/`developing` blijft hangen.

De overige bestaande limieten (`MAX_CONTAINER_NAME`, `MAX_STORY_KEY`, `MAX_SUMMARY_BYTES`, `MAX_COLLECTION_ENTRIES`, `MAX_PAYLOAD_BYTES`) blijven ongewijzigd afwijzend — dit is een aparte klasse fouten (te veel/verkeerd gevormde data i.p.v. één te grote payload-waarde) en zit buiten scope van deze story.

## Acceptance criteria

- Een `AgentRunCompleteRequest` met één of meer `events`-entries waarvan de payload > `MAX_EVENT_BYTES` is, wordt door `accept()` **geaccepteerd** i.p.v. verworpen met `CompletionPayloadRejectedException` — mits alle overige validaties (container/story-key/summary/collection-entries/totale payload) slagen.
- De te grote event-payload wordt afgekapt tot ≤ `MAX_EVENT_BYTES` (inclusief marker), niet stilzwijgend vervangen door een lege string; de afkap moet zichtbaar zijn in de opgeslagen payload (bv. een suffix die aangeeft dat en hoeveel er is afgekapt).
- Overige events in dezelfde completion (die wél binnen de limiet vallen) blijven ongewijzigd.
- Als de totale payload ná afkapping van individuele events nog steeds `MAX_PAYLOAD_BYTES` overschrijdt, blijft de completion terecht volledig afgewezen (bestaand gedrag, ongewijzigd) — dit voorkomt dat afkapping zelf weer een oneindige-DB-groei-probleem introduceert.
- Bij een afgekapt event wordt een WARN-logregel geschreven met storyKey, containerName en het aantal/de omvang van de afgekapte events, zodat het incident (ook zonder Telegram-melding) terug te vinden is in de logs.
- Bestaande test `AgentCompletionRecoveryE2eTest.kt` (regel 240-244, "bounded retry manual audit payload limits...") wordt aangepast: het oversized-event-scenario verwacht niet langer een `CompletionPayloadRejectedException`, maar een succesvolle `accept()` met een afgekapte payload. Er komt een nieuwe test toe die het afkap-gedrag expliciet verifieert (payload ≤ limiet, marker aanwezig, overige velden ongewijzigd).
- Bestaande tests voor de ongewijzigde limieten (container/story-key/summary/collection-entries/totale payload) blijven slagen zonder aanpassing.

## Aannames

- **Richting 1 (agentworker/CLI begrenst al bij het schrijven)** is buiten scope: dat component valt buiten dit factory-repo (`softwarefactory/`) en wordt hier niet aangepast.
- **Richting 3 (zichtbare Telegram-/tracker-melding bij permanente afwijzing)** is buiten scope van deze story: dat vereist het koppelen van een permanente `validateCompletion`-afwijzing aan `TrackerField.ERROR` + de bestaande `TelegramNotificationService`-flow, wat een aparte, grotere wijziging is dan de hier beschreven graceful-degradation-fix. Met de afkap-fix in deze story treedt de permanente-afwijzing-situatie (en dus de noodzaak voor die melding) nog maar zelden op (alleen als de totale payload ook ná afkapping te groot blijft). Aanbevolen als vervolgstory indien gewenst.
- De precieze vorm van de afkap-marker (tekst/formaat) is een implementatiedetail voor de developer, zolang deze duidelijk maakt dat en hoeveel is afgekapt, en de payload een geldige UTF-8-string blijft (geen geldige JSON vereist, analoog aan de bestaande `eventsForStory`-afkap in de dashboard-bridge die de frontend al gracieus opvangt).
- `MAX_COLLECTION_ENTRIES` (te veel events) is een aparte, eerder al behandelde bugklasse (SF-1134/SF-1136) en wordt hier niet opnieuw aangepakt.

## Eindsamenvatting

{"phase":"summarized"}
