# SF-1921 - Opruimen: retentie op agent_runs + alle opruimrondes in het maintenance-log

## Story

Opruimen: retentie op agent_runs + alle opruimrondes in het maintenance-log

<!-- refined-by-factory -->

## Samenvatting

De factory ruimt op verschillende plekken oude spullen op, maar je ziet daar bijna niets van
terug. Alleen het opruimen van GitHub-releases heeft een scherm; de rest verdwijnt in de logs.
Daarnaast wordt één tabel — de lijst met agent-runs — helemaal nooit opgeruimd, waardoor het
agent-log-scherm runs van maanden geleden toont waarvan de inhoud allang weg is.

Deze story doet twee dingen. Er komt een automatische opruiming van oude agent-runs, met een
instelbare bewaartermijn en de garantie dat lopende runs blijven staan. En het bestaande
onderhoudsscherm wordt een algemeen "Opruimen"-overzicht waarin élke opruimronde terug te
zien is: wat voor soort opruiming, wanneer, hoeveel er weg is en of het misging.

## Scope

### 1. Retentie op `agent_runs`

- Nieuwe `AgentRunRetentionPoller` + `AgentRunRetentionSettings` in
  `softwarefactory/.../runtime/services/`, één-op-één gemodelleerd op
  `AgentEventRetentionPoller`/`AgentEventRetentionSettings`: `@Scheduled` met eigen
  `fixedDelayString`/`initialDelayString`-properties, `enabled`-vlag, batchgewijs verwijderen met
  `maxBatchesPerRun`, `runCatching`-wrapper, publieke `cleanupOnce()` voor tests.
- Bewust een **aparte** poller, niet een uitbreiding van de event-retentie: andere default-termijn,
  andere aan/uit-schakelaar, andere veiligheidsregels, en de bestaande poller is per definitie een
  no-op zodra runs verdwijnen (cascade). De gedeelde logica is klein en zit al in
  `deleteOlderThan`-vorm op repository-niveau.
- Nieuwe env-vars in de stijl van de bestaande, inclusief dezelfde `coerceIn`-clamping:
  - `SF_AGENT_RUN_RETENTION_ENABLED` (default `true`)
  - `SF_AGENT_RUN_RETENTION_DAYS` (default `90`)
  - `SF_AGENT_RUN_RETENTION_BATCH_SIZE` (default `1000`)
  - `SF_AGENT_RUN_RETENTION_MAX_BATCHES` (default `20`)
- Nieuwe `deleteOlderThan(cutoff, batchSize)` op de agent-run-repository, met deze regels in de
  `WHERE`:
  - `started_at < cutoff`;
  - `ended_at IS NOT NULL` (lopende runs blijven altijd staan, ongeacht leeftijd);
  - geen `agent_run_completions`-rij met status `PENDING`, `IN_PROGRESS` of `FAILED_RETRYABLE`
    (onafgerond werk blijft staan; `COMPLETED` en `FAILED_PERMANENT` zijn terminaal).
- Verwijderen gebeurt alleen op `agent_runs`; `agent_events`, `agent_run_completions` en
  `agent_run_completion_steps` volgen via de bestaande `ON DELETE CASCADE`. Geen extra
  delete-statements, geen wezen.

### 2. Eén opruim-logtabel voor alle mechanismen

- Flyway-migratie **V31** generaliseert `maintenance_cleanup_runs`:
  - nieuwe kolom `kind TEXT NOT NULL` met waarden `github-releases`, `agent-events`,
    `agent-runs`, `completion-payloads`, `workspaces`;
  - `project` wordt nullable (leeg/NULL = factory-breed);
  - generieke tellers `items_deleted` / `items_kept` (vervangen `releases_deleted`,
    `releases_kept`, `packages_deleted`, `packages_kept`);
  - `dry_run`, `error` en `details` blijven zoals ze zijn;
  - index op `(kind, started_at DESC)` naast de bestaande `started_at DESC`.
  - Bestaande rijen worden gemigreerd: `kind = 'github-releases'`, `items_deleted` =
    releases + packages verwijderd, `items_kept` = releases + packages bewaard, en de
    release/package-uitsplitsing gaat mee in `details` zodat het detailscherm niets verliest.
- Repository, record- en insert-types krijgen het `kind`-veld en de generieke tellers;
  `MaintenanceCleanupScheduler` schrijft `kind = 'github-releases'` en zet zijn release/package-
  uitsplitsing in `details`. De bestaande `deleteOlderThan(cutoff)` blijft de retentie op de
  logtabel zelf en dekt daarmee automatisch alle nieuwe soorten.
- De vier overige mechanismen loggen hun ronde:
  - `AgentEventRetentionPoller`, de nieuwe `AgentRunRetentionPoller` en `WorkCleanupPoller` na
    elke `cleanupOnce()`;
  - de completion-payload-purge op de plek waar `coordinator.purgePayloads(...)` wordt
    aangeroepen in `AgentRunCompletionService.reconcileDurableCompletions()`.
- Schrijfregel (zie Aannames): deze vier schrijven alléén een rij bij `items_deleted > 0` of bij een
  fout. De nachtelijke GitHub-cleanup houdt zijn SF-1913-gedrag en schrijft élke ronde, ook bij 0.
- Het wegschrijven is fail-soft: een mislukte log-insert mag een opruimronde nooit laten falen
  (`runCatching` + warn-log).
- Modulith: `runtime/package-info.java` krijgt `"maintenance :: repositories"` in
  `allowedDependencies` (geen cyclus — `maintenance` hangt alleen aan `config`).

### 3. Scherm

- `maintenance_screen.dart` toont `kind` per rij, met een filter/groepering op soort en een
  "alle soorten"-stand als default; project blijft zichtbaar maar mag leeg zijn.
- Lijst- en detail-endpoints blijven `/api/v1/maintenance/cleanups[/{id}]`; het lijst-endpoint
  krijgt een optionele `kind`-queryparameter naast de bestaande `project`, doorgezet via
  `BridgeApiController` → `maintenance.cleanupsList` → `DashboardQueries.maintenanceCleanups`
  → repository. Geen nieuw `when`-blok in `dispatchOverviewRead`.
- Het navigatielabel wordt `Opruimen`; `dashboard-frontend/test/screens/app_shell_test.dart:54`
  assert de exacte labellijst en moet mee.

### 4. Documentatie

- `docs/factory/technical-spec.md`: de vier nieuwe `SF_AGENT_RUN_RETENTION_*`-vars in de
  env-lijst, plus de nu ontbrekende `SF_AGENT_EVENT_RETENTION_*` in dezelfde sectie.
- `docs/technical/scheduled-jobs.md` en `runbook.md`: de nieuwe poller en de gedeelde opruim-log.

### Tests

- Unit-tests voor de nieuwe retentie: verlopen afgeronde run wordt verwijderd; run met
  `ended_at IS NULL` blijft staan ongeacht leeftijd; run met onafgeronde completion blijft staan;
  verse run blijft staan; `enabled=false` doet niets; batching stopt bij een deel-batch.
- Repository-tests voor de gegeneraliseerde logtabel: schrijven en teruglezen van een rij per
  soort, inclusief NULL-project, `error` en `details`, en filteren op `kind`.
- Test op de schrijfregel: een ronde met 0 verwijderingen en zonder fout levert geen rij; een
  ronde met een fout levert wel een rij.
- `MaintenanceCleanupSchedulerTest` blijft groen op het gegeneraliseerde model.
- Frontend: `maintenance_screen_test.dart` uitgebreid met het soort-filter; `app_shell_test.dart`
  bijgewerkt.

### Buiten scope

- Geen Telegram-meldingen.
- Defaults van `SF_AGENT_EVENT_RETENTION_*`, `SF_WORK_CLEANUP_*` en
  `SF_COMPLETION_RETENTION_DAYS` blijven ongewijzigd.
- Geen handmatige "nu opruimen"-knop in het scherm.

## Acceptance criteria

1. Afgeronde `agent_runs` ouder dan `SF_AGENT_RUN_RETENTION_DAYS` verdwijnen automatisch, samen
   met hun `agent_events` en completion-rijen; runs zonder `ended_at` en runs met een onafgeronde
   completion blijven staan, ook als ze ouder zijn dan de retentie.
2. De retentie is uit te zetten met `SF_AGENT_RUN_RETENTION_ENABLED=false` en verwijdert per
   ronde hoogstens `BATCH_SIZE × MAX_BATCHES` rijen.
3. Na een opruimronde met verwijderingen of met een fout is die ronde in het dashboardscherm
   terug te zien met soort, tijdstip, aantal verwijderd, aantal bewaard en eventuele foutmelding —
   voor alle vijf soorten (`github-releases`, `agent-events`, `agent-runs`,
   `completion-payloads`, `workspaces`).
4. In het scherm is op soort te filteren; factory-brede rondes tonen geen project.
5. Bestaande GitHub-cleanup-rijen zijn na de migratie zichtbaar als soort `github-releases`, met
   hun verwijderde tags/package-versies nog in het detailscherm.
6. De opruim-logtabel valt zelf onder retentie (`sf.maintenance.run-retention-days`, default 90)
   voor alle soorten.
7. Een falende log-insert laat de opruimronde zelf slagen.
8. `docs/factory/technical-spec.md` bevat de nieuwe env-vars met hun defaults.
9. `mvn verify` (inclusief modulith- en Detekt-gates) en `flutter analyze && flutter test` zijn
   groen.

## Aannames

- "Onafgeronde completion" = een `agent_run_completions`-rij met status `PENDING`,
  `IN_PROGRESS` of `FAILED_RETRYABLE`. `COMPLETED` en `FAILED_PERMANENT` zijn terminaal en
  blokkeren verwijderen niet.
- Default 90 dagen voor `agent_runs`, ruimer dan de 30 dagen van `agent_events`, zodat de
  kostenhistorie in het agent-log langer meegaat dan de logregels.
- De schrijfregel "alleen bij verwijderingen of fouten" geldt voor de vier pollers; zonder die
  regel zou de payload-purge (die aan de completion-recovery van elke ~2 s hangt) het scherm
  binnen een dag vol lege rijen zetten. De nachtelijke GitHub-cleanup draait één keer per dag en
  blijft daarom ook bij 0 een rij schrijven — daar is "niets opgeruimd" juist informatie.
- Tabelnaam `maintenance_cleanup_runs` en de bestaande endpoint-paden blijven ongewijzigd; alleen
  het model erin generaliseert. Dat scheelt een rename door bridge, dashboard-queries en frontend.
- De pollers mogen rechtstreeks op de log-repository schrijven (via de uitgebreide
  modulith-`allowedDependencies` van `runtime`); een aparte poort-interface is hier overkill.
- `kind` is een vrije TEXT-kolom met de vijf genoemde waarden als afspraak in code, geen
  DB-constraint of enum-tabel — consistent met hoe `outcome`/`role` in `agent_runs` werken.

## Eindsamenvatting

Ik heb `.task.md`, de summarizer-instructies, het worklog (developer + reviewer + tester) en de story-diff (36 bestanden, +1746/-226) gelezen. Hieronder de eindsamenvatting.

---

# SF-1921 — Opruimen: retentie op `agent_runs` + alle opruimrondes in het maintenance-log

## Wat is gebouwd

**1. Automatische retentie op `agent_runs`**
Oude, afgeronde agent-runs worden nu automatisch opgeruimd door een nieuwe `AgentRunRetentionPoller` met bijbehorende `AgentRunRetentionSettings`, gemodelleerd op de bestaande event-retentie. Vier nieuwe env-vars: `SF_AGENT_RUN_RETENTION_ENABLED` (default `true`), `_DAYS` (90), `_BATCH_SIZE` (1000), `_MAX_BATCHES` (20). Verwijderen gebeurt uitsluitend op `agent_runs`; events, completions en completion-steps volgen via de bestaande `ON DELETE CASCADE`. Twee veiligheidsregels zitten in de SQL: runs zonder `ended_at` blijven altijd staan, en runs met een onafgeronde completion (`PENDING`/`IN_PROGRESS`/`FAILED_RETRYABLE`) ook.

**2. Eén gedeelde opruim-log voor alle vijf mechanismen**
Flyway-migratie **V31** generaliseert `maintenance_cleanup_runs`: nieuwe kolom `kind`, nullable `project` (leeg = factory-breed), generieke tellers `items_deleted`/`items_kept` in plaats van de release/package-specifieke kolommen, en een index op `(kind, started_at DESC)`. Bestaande rijen migreren mee als `kind = 'github-releases'`, met de release/package-uitsplitsing bewaard in `details` zodat het detailscherm niets verliest. Een nieuwe `CleanupLogWriter` verzorgt het wegschrijven voor de vier factory-brede opruimers (agent-events, agent-runs, workspaces, completion-payloads).

**3. Scherm**
Het navigatielabel is `Opruimen` geworden. Het scherm toont per rij een soort-badge en de generieke aantallen, laat het project weg als het leeg is, en heeft een filter op soort met "alle soorten" als default. De `kind`-queryparameter loopt door `BridgeApiController` → `maintenance.cleanupsList` → `DashboardQueries` → repository; endpoints en tabelnaam zijn ongewijzigd gebleven.

## Belangrijkste keuzes

- **Aparte poller in plaats van uitbreiding van de event-retentie**: andere default-termijn (90 vs. 30 dagen, zodat de kostenhistorie in het agent-log langer meegaat dan de logregels), eigen aan/uit-schakelaar en eigen veiligheidsregels.
- **Schrijfregel**: de vier factory-brede opruimers schrijven alléén een rij bij daadwerkelijke verwijderingen of bij een fout. Zonder die regel zou de payload-purge (die aan de ~2-seconden-recovery hangt) het scherm binnen een dag met lege rijen vullen. De nachtelijke GitHub-cleanup houdt zijn SF-1913-gedrag en schrijft élke ronde, ook bij 0 — daar is "niets opgeruimd" juist informatie.
- **Fail-soft loggen**: een mislukte log-insert laat de opruimronde zelf altijd slagen.
- **`CleanupLogWriter` als optionele constructorparameter** bij de vier opruimers, zodat bestaande unit-tests ongewijzigd blijven werken.
- **Tabelnaam en endpoint-paden ongewijzigd**: alleen het model erin generaliseert, wat een rename door bridge, dashboard-queries en frontend scheelt.

## Wat is getest

Het volledige vangnet is groen: `mvn verify` → BUILD SUCCESS (softwarefactory 763 unit + 78 e2e, agentworker 61, dashboard-backend 57, factory-common 55, factory-contracts 16 — overal 0 failures/errors, geen flakes). `flutter analyze` → geen issues; `flutter test` → 121 tests groen.

Nieuw geschreven: `AgentRunRetentionPollerTest` (9), `AgentRunRetentionRepositoryTest` (5, echte Postgres — alle veiligheidsregels en de cascade), `CleanupLogWriterTest` (5, schrijfregel + fail-soft), `MaintenanceCleanupRunMigrationTest` (3, migreert tot V30, schrijft een oude rij, draait V31 en leest de uitsplitsing terug — bewijs voor AC5). Verder uitgebreid: `MaintenanceCleanupRunRepositoryTest`, `MaintenanceCleanupSchedulerTest`, `maintenance_screen_test.dart` en `app_shell_test.dart`. De reviewer heeft de volledige diff beoordeeld en akkoord gegeven zonder blockers. Alle negen acceptatiecriteria zijn expliciet afgevinkt door de tester.

Documentatie bijgewerkt: `technical-spec.md` (inclusief de tot nu toe ontbrekende `SF_AGENT_EVENT_RETENTION_*`), `scheduled-jobs.md`, `runbook.md`, `screen-map.md`, `ontwerp-bridge-dashboard.md`, `module-dependencies.md` en `properties.default.env`.

## Bewust niet gedaan / aandachtspunten voor later

- **Geen Telegram-meldingen**, geen handmatige "nu opruimen"-knop, en de defaults van de bestaande retentie-instellingen zijn ongemoeid gelaten — allemaal expliciet buiten scope.
- **Quality-ratchet**: `./quality/run.sh` meldt twee "nieuwe" `TooManyFunctions`-bevindingen op beide `RunRepositories.kt`, maar dezelfde twee staan ook als `resolved` — het zijn verschoven vingerafdrukken van een bestaande overtreding, niet een nieuwe. De baseline is bewust niet opgerekt; de ratchet zit niet in de CI-mergegate. Opsplitsen van `AgentRunRepository` valt buiten deze story.
- **Geen live preview**: er draaide geen dashboard in de sandbox, dus geen browserscenario of screenshots. Schermgedrag is gedekt via widget-tests.
- **Twee kleine suggesties** uit de review, niet blokkerend: de vier factory-brede opruimers schrijven altijd `itemsKept = 0`, waardoor het scherm "N opgeruimd / 0 bewaard" toont (klopt, maar leest raar — een streepje zou netter zijn); en `BridgeApiControllerTest` dekt de nieuwe `kind`-param nog niet, al is het pad zelf wel getest.

<!-- deploy-summary:start -->
Oude agent-runs worden voortaan vanzelf opgeruimd, zodat het agent-log geen maandenoude, lege regels meer laat zien. Lopende en nog niet afgeronde runs blijven altijd bewaard. Het onderhoudsscherm heet nu "Opruimen" en toont elke opruimronde, met een filter om per soort te kijken wat er wanneer is weggehaald.
<!-- deploy-summary:end -->
