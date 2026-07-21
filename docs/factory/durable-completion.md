# Duurzame agent-completion

Agentresultaten worden vóór business-effecten geaccepteerd in Postgres. Daardoor kan de factory na
een exception, containerstop of JVM-restart verdergaan vanaf de eerste niet-afgeronde stap. De
externe `POST /agent-run/complete`-payload is ongewijzigd: volledig verwerkt geeft `200`, duurzaam
geaccepteerd maar nog niet volledig verwerkt geeft `202`, onbekende actieve run `404`, een
conflicterende redelivery `409`.

## Protocol

Migratie `V16__durable_agent_completion.sql` maakt een inbox, een stappenledger en een append-only
requeue-audit. Eén agent-run heeft exact één completion en payloadhash. Een identieke redelivery is
een geldige replay; dezelfde run met een andere payload wordt geweigerd.

De stabiele stappen zijn, in volgorde:

1. `ACCEPT_RUN_RESULT`
2. `APPLY_USAGE_AND_COSTS`
3. `WRITE_FINAL_STORY`
4. `SYNC_REPOSITORY`
5. `UPSERT_REPOSITORY_METADATA`
6. `STORE_AGENT_EVENTS`
7. `SYNC_TESTER_ARTIFACTS`
8. `APPLY_TRACKER_RESULT`
9. `UPSERT_KNOWLEDGE`
10. `FINALIZE_COMMENT_MARKERS`
11. `CLEAN_WORKSPACE`
12. `PUBLISH_COMPLETION_WAKE`

Workers claimen één stap atomair met een lease. Een verlopen lease mag door een andere
factory-instantie worden overgenomen. Fouten krijgen begrensde exponentiële backoff en eindigen na
acht pogingen in `FAILED_PERMANENT`. De pipeline blokkeert nieuwe dispatch, recovery, merge en
deploy voor de story zolang een completion niet `COMPLETED` is. Usage-totalen en agent-events
hebben daarnaast eigen database-idempotencykeys voor het crashvenster na effect maar vóór ack.

Afgeronde payloads worden na 30 dagen verwijderd; hash, status, stapledger en timestamps blijven
als tombstone bestaan. Payloads zijn maximaal 1 MiB, met maximaal 1000 events/knowledge-updates/
subtaken, 200 kB samenvatting en 256 KiB per event. Een individuele event-payload die de
256 KiB-limiet overschrijdt laat de completion niet meer afwijzen: `accept()` kapt zo'n payload
af tot binnen de limiet (met een zichtbare `"...[afgekapt: origineel N bytes]"`-marker) en
schrijft een WARN-logregel met storyKey, containerName en het aantal/de omvang van de afgekapte
events. Overige events en de overige limieten (container/story-key/samenvatting/aantal
entries/totale payload) blijven onverkort afwijzend; als de totale payload ná afkapping nog
steeds de 1 MiB-grens overschrijdt, wordt de completion nog steeds volledig geweigerd.

## Operationeel herstel

Bekijk pending/permanente completions en hun oudste leeftijd:

```sql
SELECT status, count(*), min(created_at) AS oldest
FROM software_factory.agent_run_completions
WHERE status <> 'COMPLETED'
GROUP BY status;
```

Bekijk de mislukte stap vóór ingrijpen:

```sql
SELECT c.id, c.story_key, s.step_key, s.status, s.attempts, s.last_error, s.updated_at
FROM software_factory.agent_run_completions c
JOIN software_factory.agent_run_completion_steps s ON s.completion_id = c.id
WHERE c.status <> 'COMPLETED'
ORDER BY c.created_at, s.step_order;
```

Los eerst de onderliggende oorzaak op. Requeue daarna alleen de mislukte stap via de beveiligde
API; gebruik dezelfde bearer-token als `/api/restart` en vermeld actor en reden:

```bash
curl -X POST \
  -H "Authorization: Bearer $SF_FACTORY_API_TOKEN" \
  -H "X-Operator: robbert" \
  -H "Content-Type: application/json" \
  -d '{"reason":"afhankelijkheid hersteld"}' \
  http://localhost:8080/api/completions/123/steps/SYNC_REPOSITORY/requeue
```

De API accepteert uitsluitend een `FAILED_RETRYABLE`- of `FAILED_PERMANENT`-stap en schrijft
actor, reden, oude status en tijd append-only naar `agent_run_completion_requeues`. Ontbrekende of
onjuiste authenticatie verandert geen status. Herstarten zonder requeue is voldoende voor pending,
retryable of verlopen leased werk: de reconciler scant standaard iedere twee seconden.

Tuning gebruikt `SF_COMPLETION_RECOVERY_POLL_MS` (standaard 2000),
`SF_COMPLETION_MAX_ATTEMPTS` (8), `SF_COMPLETION_LEASE_SECONDS` (300),
`SF_COMPLETION_BACKOFF_MS` (2000) en `SF_COMPLETION_RETENTION_DAYS` (30). Waarden buiten de veilige
grenzen worden begrensd; verlaag de lease alleen als de langzaamste repository-/trackerstap daar
ruim binnen blijft.

Bij rollback blijft migratie V16 staan. Een oudere binary begrijpt pending completions niet en mag
daarom alleen tijdelijk worden gebruikt nadat alle inboxregels `COMPLETED` zijn; anders eerst de
nieuwe binary opnieuw uitrollen en recovery laten eindigen.
