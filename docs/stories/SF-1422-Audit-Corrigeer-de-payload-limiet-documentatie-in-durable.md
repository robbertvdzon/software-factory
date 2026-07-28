# SF-1422 - [Audit] Corrigeer de payload-limiet-documentatie in durable-completion.md (1 MiB → 8 MiB) en de bijbehorende foutmelding in de code

## Story

[Audit] Corrigeer de payload-limiet-documentatie in durable-completion.md (1 MiB → 8 MiB) en de bijbehorende foutmelding in de code

<!-- refined-by-factory -->

## Samenvatting
De documentatie over de maximale grootte van agent-completion-payloads is verouderd: er staat overal "1 MiB", terwijl de code al jaren een limiet van 8 MiB hanteert. Ook de foutmelding die gebruikers te zien krijgen bij een te grote payload noemt nog de verkeerde 1 MiB-grens. Deze story corrigeert die tekst zodat documentatie, foutmelding en code weer met elkaar overeenkomen.

## Scope
In scope:
- `docs/factory/durable-completion.md` regel 37: "Payloads zijn maximaal 1 MiB" wijzigen naar "Payloads zijn maximaal 8 MiB".
- `docs/factory/durable-completion.md` regels 43-44: de zin "als de totale payload ná afkapping nog steeds de 1 MiB-grens overschrijdt" wijzigen naar "8 MiB-grens".
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/repositories/CompletionInboxRepository.kt` regel 415: foutmeldingstekst `"completion payload exceeds 1 MiB"` wijzigen naar `"completion payload exceeds 8 MiB"`.

Buiten scope:
- `MAX_PAYLOAD_BYTES` (regel 316, waarde `8_388_608` = 8 MiB) blijft ongewijzigd — dit is al correct.
- Geen andere limieten (event-bytes, summary-bytes, collection-entries) worden aangepast; die zijn al correct gedocumenteerd.
- Geen gedragswijziging, geen nieuwe tests nodig (puur tekst/documentatie).

## Acceptance criteria
- `docs/factory/durable-completion.md` regel 37 vermeldt "8 MiB" in plaats van "1 MiB" als maximale payloadgrootte.
- `docs/factory/durable-completion.md` regels 43-44 vermelden "8 MiB-grens" in plaats van "1 MiB-grens".
- `CompletionInboxRepository.kt` regel 415 geeft bij overschrijding de melding "completion payload exceeds 8 MiB".
- `MAX_PAYLOAD_BYTES` (8_388_608) is ongewijzigd gebleven.
- Geen enkele andere regel gedrag (validatie, afkapping, overige limieten) is aangepast.

## Aannames
- Er zijn geen overige verwijzingen naar "1 MiB" elders in de codebase die met deze payload-limiet samenhangen (bevestigd via repo-brede grep op "1 MiB" en "MAX_PAYLOAD_BYTES").
- De foutmelding is alleen tekst in een exception-message; er zijn geen tests die specifiek op de letterlijke string "1 MiB" matchen (dit wordt door de developer bij implementatie geverifieerd, maar vormt geen blokkade voor refinement).

## Eindsamenvatting

Ik heb genoeg context uit `.task.md`, de git-diff en `docs/stories/worklog/SF-1422-worklog.md`. Nu schrijf ik de eindsamenvatting.

## Eindsamenvatting SF-1422 — Corrigeer payload-limiet-documentatie (1 MiB → 8 MiB)

**Wat is gebouwd**

Drie tekstcorrecties, geen gedragswijziging:

1. `docs/factory/durable-completion.md` (regel 37): "Payloads zijn maximaal 1 MiB" → "8 MiB".
2. `docs/factory/durable-completion.md` (regels 43-44): "de 1 MiB-grens overschrijdt" → "de 8 MiB-grens overschrijdt".
3. `softwarefactory/.../CompletionInboxRepository.kt` (regel 415): foutmeldingstekst "completion payload exceeds 1 MiB" → "completion payload exceeds 8 MiB".

Hiermee sluiten documentatie en de foutmelding weer aan bij de al langer geldende code-limiet `MAX_PAYLOAD_BYTES = 8_388_608` (8 MiB), die zoals gepland ongewijzigd is gebleven.

**Gemaakte keuzes**

- Puur tekstuele wijziging; geen aanpassing aan validatielogica, afkapgedrag of overige limieten (events, samenvatting, collection-entries).
- Repo-breed gegrept op "1 MiB" en op "exceeds 1 MiB"/"exceeds 8 MiB" om te bevestigen dat er geen overige verwijzingen of tests bestaan die op de oude tekst matchen — dat was ook zo, dus geen extra bestanden hoefden aangepast.

**Wat is getest**

- `mvn verify` vanaf de repo-root: BUILD SUCCESS voor alle modules (factory-contracts, factory-common, softwarefactory incl. Testcontainers-e2e, agentworker, softwarefactory-dashboard-backend), 0 failures/errors/skipped.
- Geen nieuwe tests nodig/toegevoegd, conform scope (zuiver tekstcorrectie, geen letterlijke stringmatch in bestaande tests).

**Bewust niet gedaan**

- `MAX_PAYLOAD_BYTES` (code-limiet) niet aangeraakt — was al correct.
- Geen wijzigingen aan `functional-spec.md`, `technical-spec.md` of ux-documentatie, aangezien er geen architectuur- of gedragswijziging is.
