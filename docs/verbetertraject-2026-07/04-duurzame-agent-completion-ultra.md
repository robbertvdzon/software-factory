# Plan 04 — Duurzame en hervatbare agent-completion

## Metadata

| Veld | Waarde |
| --- | --- |
| Plan | 04 |
| Status | `NIET GESTART` |
| Werkpakket | `REL-01` |
| Prioriteit / omvang | P1 / L |
| Aanbevolen model | GPT-5.6 Sol |
| Effort | Ultra |
| Waarom dit niveau | Completion combineert lokale transacties, git/GitHub, trackertransities, artifacts, retries en crash recovery. Schijnbare precies-één-keerverwerking is hier gevaarlijker dan zichtbare complexiteit. |
| Prerequisite | Plan 03 volledig `AFGEROND`, gemerged en groen volgens `VOORTGANG.md` |
| Volgend plan | Plan 05 |

Dit plan bevat bewust uitsluitend `REL-01`. Voer geen application-, tracker-capability- of
packagebrede refactor uit; die volgen in plan 05. Het resultaat van dit plan is een duurzaam
completionprotocol dat na een exception, procescrash of restart aantoonbaar kan hervatten zonder
business-effecten te verdubbelen.

## Bindende bronnen

Lees vóór iedere wijziging volledig:

- `docs/verbetertraject-2026-07/UITVOERREGELS.md`;
- `docs/verbetertraject-2026-07/VOORTGANG.md`;
- `docs/verbeterplan-onderhoudbaarheid-2026-07.md`, in het bijzonder `REL-01`;
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/services/AgentRunCompletionService.kt`;
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/services/AgentResultFileCompletionPoller.kt`;
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/RuntimeApi.kt`;
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/repositories/RunRepositories.kt`;
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/core/RunRepositories.kt`;
- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/runtime/repositories/AgentEventRepository.kt`;
- `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/runtime/AgentRunCompletionServiceTest.kt`;
- `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/runtime/AgentResultFileCompletionPollerTest.kt`;
- de actuele Flyway-migraties en relevante e2e-harness.

## Harde uitvoerinvarianten

- Maak voor `REL-01` precies één Factory-story, branch en PR en registreer deze direct in
  `VOORTGANG.md`.
- Bewaar het bestaande externe completion-wireformaat. Additieve responsevelden mogen alleen met
  contracttests; bestaande producers mogen niet tegelijk verplicht aangepast hoeven worden.
- Een herhaalde identieke completion is geldig en levert geen `404`; een conflicterende tweede
  payload voor dezelfde agent-run wordt zichtbaar geweigerd en niet stil overschreven.
- Workspace-cleanup gebeurt pas wanneer geen nog uit te voeren completionstap de workspace nodig
  heeft.
- Zolang een completion niet terminaal is, mogen dispatch, actieve-faserecovery en PR-monitoring
  dezelfde story/run niet naar een volgende businessfase of nieuwe agent brengen. Een permanent
  gefaalde completion vereist een expliciete operationele beslissing; zij start nooit automatisch
  een volgende run.
- Secrets, tokens, volledige gevoelige payloads en ongeredigeerde exceptions komen niet in
  `last_error`, logs of events.
- Geen enkele falende unit-, integratie-, e2e-, contract-, Flutter- of smoketest mag worden
  genegeerd, ook niet als de fout pre-existing, flaky, omgevingsgebonden of ongerelateerd lijkt.
- Gebruik geen `@Disabled`, skip, quarantine, suppressie, `|| true` of kleinere testsuite om groen
  bewijs te simuleren.
- Bij ieder gericht Mavencommando met `-Dsurefire.failIfNoSpecifiedTests=false` bewijzen vers
  gegenereerde Surefire-/Failsafe-rapporten dat de expliciete doelmodule meer dan nul geselecteerde
  tests uitvoerde. De optie geldt alleen voor `-am`-dependency-modules zonder match; nul tests in
  `softwarefactory` is rood, ook bij exitcode 0, en een oud rapport is geen bewijs.

## Prerequisites en startcontrole

1. Controleer dat plan 03 in `VOORTGANG.md` `AFGEROND` is en dat `VER-02`, `DOC-01` en `MOD-01`
   gemerged en groen zijn.
2. Werk bij naar de actuele default branch en controleer story, branch, PR en worktree; vertrouw
   niet blind op een oude statusregel.
3. Maak de Factory-story met onderstaande titel en zet haar niet tevens op `start` voor een tweede
   interne uitvoerder.
4. Leg vóór de eerste wijziging vast:

   ```bash
   git status --short
   git log -1 --oneline
   mvn verify
   ./quality/run.sh
   cd dashboard-frontend
   flutter analyze
   flutter test
   cd ..
   ./factory build-images
   ```

5. Voer daarnaast onvoorwaardelijk de vanuit plan 03 overgedragen documentatie-audit en
   branch-protectionaudit uit en controleer de actuele `Repository verification`-run op dezelfde
   SHA. Ontbrekende commando's of bewijs uit de overdracht blokkeren de start.
6. Noteer datum/tijd, SHA, exitcodes, testtellingen en qualityscore in story/worklog en
   `VOORTGANG.md`. Een rode baseline blokkeert de start en wordt volgens `UITVOERREGELS.md`
   opgelost; zij wordt nooit als “bekend” geaccepteerd.

## Kopieerbare startopdracht

```text
Voer plan 04 volledig autonoom uit volgens
docs/verbetertraject-2026-07/04-duurzame-agent-completion-ultra.md.

Lees vóór iedere wijziging ook volledig:
- docs/verbetertraject-2026-07/UITVOERREGELS.md
- docs/verbetertraject-2026-07/VOORTGANG.md
- docs/verbeterplan-onderhoudbaarheid-2026-07.md

Controleer eerst dat plan 03 aantoonbaar AFGEROND en groen is. Maak voor REL-01 één Factory-story
met een eigen branch en PR en registreer alle overdrachten en testbewijzen in VOORTGANG.md.
Implementeer een duurzame completion-inbox, een idempotente stapledger en een lease-gebaseerde
restart-reconciler. Voeg systematische failure-injection toe vóór het effect, ná het effect maar
vóór de bevestiging, en ná de bevestiging van iedere duurzame stap. Bewijs na iedere injectie en
restart dat verwerking voltooit zonder dubbele usage, events, comments, attachments, subtaken,
knowledge of PR-effecten. Verzwak geen bestaand contract en negeer geen enkele falende test.
Laat developer, reviewer en tester hun volledige gates uitvoeren. Push en merge uitsluitend na
alle vereiste groene checks en voer daarna de post-mergeverificatie op de gemergede SHA uit. Stop
alleen bij een echte externe blokkade of een onomkeerbare productbeslissing.
```

## Verplichte volgorde

Voer deze stappen binnen `REL-01` strikt in deze volgorde uit. Sla geen bewijsstap over.

1. Karakteriseer de huidige normale en foutpaden zonder productiegedrag te wijzigen.
2. Leg de completion-identiteit, stapvolgorde, retryclassificatie en transacties schriftelijk vast
   in het story-worklog.
3. Voeg de database-inbox en stapledger met Flyway toe.
4. Maak acceptatie van een completion atomair en herhaalbaar.
5. Maak iedere businessstap afzonderlijk idempotent.
6. Voeg leasing, backoff en de restart-reconciler toe.
7. Voeg de volledige failure-injectionmatrix toe en maak haar groen.
8. Splits pas daarna de oude hoofdservice tot een korte orchestrator en kleine step handlers.
9. Werk actuele runtime-, database- en scheduled-jobdocumentatie bij.
10. Laat developer, reviewer en tester de uiteindelijke commit controleren; merge en verifieer de
    default branch opnieuw.

## REL-01 — Maak agent-completion duurzaam en hervatbaar

**Voorgestelde Factory-storytitel:** `Duurzame agent-completion met idempotente restart-recovery`

### Probleem en bewijs

`AgentRunCompletionService.complete()` sluit nu eerst de actieve agent-run en voert daarna usage,
repositorysync, PR-metadata, events, screenshots, trackertransities, subtaken, knowledge,
commentmarkers, cleanup en wake-up uit. `JdbcAgentRunRepository.complete()` accepteert alleen
`ended_at IS NULL`. Zodra een latere stap faalt, ziet een retry dus geen actieve run meer. Een deel
van de latere fouten wordt bovendien alleen gelogd. Daardoor kan een callback zichtbaar afgerond
lijken terwijl de workflow half is verwerkt, en kan blind opnieuw uitvoeren usage, comments of
artifacts verdubbelen.

Relevante huidige locaties zijn minimaal:

- `AgentRunCompletionService.kt:76-96` — niet-duurzame sequentie;
- `AgentRunCompletionService.kt:115-140` — run afsluiten en usage optellen;
- `AgentRunCompletionService.kt:213-300` — repository- en trackerupdates;
- `AgentRunCompletionService.kt:355-399` — screenshots vervangen/uploaden;
- `AgentRunCompletionService.kt:440-506` — knowledge en commentmarkers;
- `RunRepositories.kt:308-357` — eenmalige `ended_at`-guard en additieve usage-update;
- `AgentResultFileCompletionPoller.kt` — tweede producerroute naast HTTP.

### Vast ontwerpdoel

Implementeer een **duurzame completion-inbox met stapledger**. Een algemene async-messagebroker is
niet nodig. De Postgres-database is de bron van waarheid en bewaart genoeg informatie om na een
JVM-restart zonder het oorspronkelijke HTTP-request of `agent-result.json` verder te kunnen.

Gebruik minimaal deze concepten; namen mogen afwijken wanneer de betekenis gelijk blijft:

- `agent_run_completions`: één canonieke completion per `agent_run_id`, met uitsluitend de minimale
  allowlisted, geredigeerd-opslagbare velden die de duurzame stappen aantoonbaar nodig hebben,
  payloadhash, globale status, attemptteller, `next_attempt_at`, lease-owner/lease-until, laatste
  veilige fout en timestamps. Sla geen ruwe stdout/stderr, volledige logs, tokens of ongebruikte
  requestvelden op;
- `agent_run_completion_steps`: één rij per completion en stabiele step key, met status
  `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED_RETRYABLE` of `FAILED_PERMANENT`, attempts,
  lease/timestamps en veilige foutinformatie;
- de globale completionstatus gebruikt dezelfde relevante lifecycle: `PENDING`, `IN_PROGRESS`,
  `COMPLETED`, `FAILED_RETRYABLE` en `FAILED_PERMANENT`. Een permanent gefaalde step zet de globale
  status atomair op `FAILED_PERMANENT`; geen scheduler of reconciler probeert die automatisch
  opnieuw;
- unieke constraint op `agent_run_id`; identieke redelivery hergebruikt de bestaande completion;
- atomische claim via row lock/`FOR UPDATE SKIP LOCKED` of een gelijkwaardig compare-and-set;
- een vervallen lease maakt een na een crash achtergelaten `IN_PROGRESS`-stap opnieuw claimbaar;
- bounded exponential backoff zonder tight retry-loop;
- permanente invaliditeit/conflict wordt expliciet zichtbaar en blijft inspecteerbaar; zij wordt
  niet als `COMPLETED` gemarkeerd;
- een geautoriseerde handmatige requeue is de enige route uit `FAILED_PERMANENT`. Zij vereist actor,
  reden en timestamp, schrijft een append-only auditrecord, behoudt de eerdere fout-/attemptgeschiedenis
  en reset uitsluitend de bewust gekozen gefaalde step plus noodzakelijke globale claimvelden. Een
  scheduler mag deze operatie nooit impliciet uitvoeren;
- valideer vóór opslag een expliciete maximale serialized payloadgrootte en begrensde aantallen/
  groottes voor collecties en tekstvelden. Overschrijding geeft een getypeerde, contractgeteste
  rejection zonder gedeeltelijke inbox-/steprijen. Bepaal en documenteer numerieke defaults en
  geldige configuratiegrenzen op basis van de bestaande HTTP-/result-filecontractlimieten en een
  gemeten representatieve grootste geldige payload; kies geen onverklaard willekeurig getal;
- gedetailleerde payloadvelden hebben een expliciete configureerbare retentie en purgejob. Bewaar
  daarna minimaal een tombstone met `agent_run_id`, canonieke payloadhash, finale uitkomst en
  relevante timestamps zolang de agent-run/story bestaat. Purge van detail mag de idempotencykey
  nooit verwijderen of late redelivery opnieuw laten uitvoeren; de bestaande storypurge ruimt de
  tombstone uiteindelijk volgens gedocumenteerd beleid op. Leg een numerieke standaardretentie en
  configuratiegrenzen vast die nooit korter zijn dan de maximale automatische retry-/recoveryperiode
  en stem de uiteindelijke tombstonelevensduur aantoonbaar af op de bestaande agent-run-/storypurge.

De inbox bewaart geen secrets buiten wat strikt noodzakelijk is voor een duurzame step. Sla
foutmeldingen geredigeerd en begrensd op; leg geen volledige stacktrace in een databaseveld. Leg
per bewaard veld de consumer/step en retentiereden vast, zodat “voor de zekerheid” geen geldige
datagrond is.

### Canonieke stapvolgorde

Leg stabiele step keys vast en wijzig ze later niet zonder migratie. Minimaal:

1. `ACCEPT_RUN_RESULT` — koppel payload aan de bestaande run en zet de rungegevens atomair;
2. `APPLY_USAGE_AND_COSTS` — usage/kosten exact eenmaal verwerken;
3. `WRITE_FINAL_STORY` — alleen voor een geslaagde summarizer;
4. `SYNC_REPOSITORY` — alleen voor succesvolle rollen die de repo mogen wijzigen;
5. `UPSERT_REPOSITORY_METADATA` — gerapporteerde branch/PR-data veilig samenvoegen;
6. `STORE_AGENT_EVENTS` — elk bron-event deterministisch dedupliceren;
7. `SYNC_TESTER_ARTIFACTS` — alleen voor tester, herhaalbaar en zonder tijdelijk alles kwijt te
   raken;
8. `APPLY_TRACKER_RESULT` — fase/error, subtaken en agentcomment volgens bestaand gedrag;
9. `UPSERT_KNOWLEDGE` — iedere knowledge-update herhaalbaar;
10. `FINALIZE_COMMENT_MARKERS` — tracker- en PR-commentstatussen herhaalbaar afronden;
11. `CLEAN_WORKSPACE` — uitsluitend nadat alle workspace-afhankelijke stappen voltooid zijn;
12. `PUBLISH_COMPLETION_WAKE` — niet-duurzame wake-up als laatste, veilig om meermaals te sturen.

Behoud bestaande rol- en foutsemantiek. Een conditioneel niet-toepasselijke stap krijgt duurzaam de
status `COMPLETED` met resultaat `SKIPPED_NOT_APPLICABLE`; hij blijft niet eeuwig `PENDING`.

### Idempotencycontract per effect

| Effect | Verplicht idempotencymechanisme | Te bewijzen herhaling |
| --- | --- | --- |
| Agent-run afsluiten | Unieke `agent_run_id`; update-if-null plus bestaande rij teruglezen | Dezelfde payload geeft dezelfde completion; afwijkende payload geeft conflict |
| Usage en storytotalen | Transactie met unieke toepassing per `agent_run_id`, niet blind opnieuw optellen | Crash na totaalupdate maar vóór step-ack telt niet dubbel |
| Repositorysync/commit/push/PR | Deterministische branch/commitdetectie en bestaande PR hergebruiken | Crash na push/PR maar vóór step-ack maakt geen tweede commit of PR |
| PR-/branchmetadata | Upsert van dezelfde waarden | Herhaling verandert geen reeds correct record |
| Agent-events | Deterministische dedupkey per `agent_run_id` en bronindex/hash, met databaseconstraint | Het aantal events blijft exact gelijk na N retries |
| Testerattachments | Deterministische naam/dedupkey; nieuwe set eerst veilig beschikbaar, daarna obsolete oude set verwijderen | Crash tijdens upload laat geen duplicaten en verwijdert niet de enige geldige set |
| Trackerfase/error | Conditionele/idempotente update | Dezelfde fase wordt niet opnieuw gebumpt of teruggezet |
| Agentcomment | Verborgen stabiele completionmarker of gelijkwaardige unieke dedupkey | Precies één zichtbaar rolcomment per completion |
| Planner-subtaken | Bestaande materialisatie-idempotentie plus completiontest | Geen dubbele subtaken na retry/restart |
| Knowledge | Bestaande businesskey-upsert expliciet testen | Zelfde update blijft één entry |
| PR-commentmarkers | Monotone stateovergang; reeds `done/failed` is no-op | Geen markerterugval of dubbele API-side-effect |
| Final-storybestand | Deterministisch pad en atomische replace | Zelfde inhoud, geen extra bestand |
| Workspace-cleanup | Padvalidatie plus `cleanup_completed_at`; pas als laatste | Niet te vroeg verwijderd; herhaalde cleanup is no-op |

Voor een extern effect dat geen native idempotencykey ondersteunt, implementeer eerst een
read-before-write/deterministische sleutel en test expliciet het venster **effect geslaagd, proces
crasht vóór lokale bevestiging**. Claim geen wiskundig exactly-oncegedrag waar de externe API dat
niet kan leveren; beschrijf het concrete effectively-oncemechanisme.

### Concrete implementatiestappen

1. Voeg characterizationtests toe voor de bestaande succesvolle rollen, retryable failure,
   non-retryable failure, refiner/planner zonder reposync, developer met PR-comment, tester met
   screenshots en summarizer met final-storybestand.
2. Voeg een uitsluitend additieve Flyway-migratie toe voor inbox/steps, requeue-audit en alle
   noodzakelijke unieke constraints/indexen. Maak queries efficiënt voor `status`,
   `next_attempt_at` en vervallen leases; verwijder of hernoem geen kolom/tabel die de gedeclareerde
   rollbackbinary nodig heeft.
3. Introduceer kleine repositories/ports voor accept, claim, step-complete, retry en inspectie. Houd
   SQL- en statusovergangen uit de orchestrator.
4. Accepteer HTTP- en result-filecompletions via exact dezelfde methode:
   - zoek de agent-run ook wanneer deze al geëindigd is;
   - canonicaliseer en hash de payload;
   - insert completion + initial steps en sluit de run in één lokale transactie;
   - identieke redelivery retourneert succesvol dezelfde identifiers;
   - afwijkende redelivery retourneert een getypeerd conflict en een veilige melding.
5. Leg accepted-versus-completed als expliciet extern contract vast:
   - duurzame inboxacceptatie betekent **accepted**, niet dat alle businesssteps `COMPLETED` zijn;
   - behoud in de eerste compatibiliteitsfase exact de bestaande 2xx-status en bestaande verplichte
     responsevelden. Voeg alleen optionele velden toe, zoals `completionId` en
     `processingStatus=ACCEPTED|COMPLETED|FAILED_PERMANENT`, wanneer oude callers onbekende velden
     aantoonbaar accepteren; een bestaande lege response blijft compatibel en krijgt zo nodig een
     afzonderlijk statusendpoint;
   - documenteer dat de bestaande 2xx in die fase duurzame acceptatie betekent. Schakel alleen naar
     HTTP `202 Accepted` wanneer **alle** actuele HTTP-callers met contracttests voor die status en
     body zijn bewezen en de omschakeling afzonderlijk uitrolbaar is;
   - maak voortgang en finale uitkomst observeerbaar via een getypeerde statusquery/endpoint of de
     bestaande result-route. Een caller mag een accepted-response niet als completed interpreteren.
   Laat de requestthread zo mogelijk direct verwerken, maar maak succesvolle duurzame acceptatie
   nooit afhankelijk van het voltooien van alle externe stappen.
6. Maak één `CompletionStepHandler` per stap of per strikt samenhangend lokaal transactiegebied.
   Iedere handler krijgt een immutable context en retourneert getypeerd `Completed`,
   `RetryableFailure` of `PermanentFailure`.
7. Voeg een reconciler toe die bij startup en periodiek pending/retryable/verlopen work claimt.
   Gebruik een bounded batch, lease-owner en klokinjectie. Twee gelijktijdige workers mogen dezelfde
   completion niet tegelijk uitvoeren.
8. Maak retryclassificatie expliciet. Netwerk/timeouts en test-geïnjecteerde fouten zijn retryable;
   payloadconflict en ongeldige invarianten zijn permanent. Zet permanente fouten duurzaam op step-
   en globaal `FAILED_PERMANENT`, maak ze zichtbaar in logs/dashboard/tracker en probeer ze nooit
   automatisch opnieuw. Voeg een geautoriseerde, auditable handmatige requeue-use-case toe met
   actor/reason/timestamp en tests voor autorisatie, auditbehoud en exact hervatten.
9. Verplaats cleanup naar de laatste duurzame stap. Bij een onafgeronde completion blijft de
   workspace behouden; laat de algemene cleanup-poller deze toestand eveneens respecteren zonder
   de scope van `OPS-01` opnieuw te implementeren.
10. Vervang `AgentRunCompletionService` door een korte accept-/orchestratieservice. Splits geen
    andere runtime-, tracker- of pipelinecode die niet nodig is voor REL-01.
11. Voeg guards toe aan nieuwe-agentdispatch, actieve-faserecovery en PR-monitoring. Zolang een
    completion `PENDING`, `IN_PROGRESS` of `FAILED_RETRYABLE` is, mogen die paden dezelfde story/run
    niet verderzetten, ook niet als de agent-run al `ended_at` heeft. `FAILED_PERMANENT` is voor de
    completionledger terminal, maar houdt de story zichtbaar geblokkeerd/Error tot een
    geautoriseerde requeue of expliciete operatorafhandeling; het triggert geen automatische
    dispatch. Test ieder van deze drie consumers tegen alle statussen.
12. Voeg operationele observability toe: aantal pending/retrying/permanent-failed completions,
    oudste pending leeftijd en veilige logregels met completion-id, agent-run-id en step key.
13. Implementeer en test dataminimalisatie, payload-/collectielimieten, detailretentie en tombstones.
    Test expliciet een late identieke én conflicterende redelivery nadat detail is gepurged: dezelfde
    hash geeft dezelfde finale uitkomst zonder effect; een andere hash blijft conflict. Verwijder de
    tombstone pas met de gedocumenteerde agent-run-/storypurge.
14. Leg een compatibele tweefasenrollout en rollback vast en automatiseer de bewijsroute:
    - deploy eerst schema plus een compatibilitybinary met durable processing uitgeschakeld maar met
      inbox-/blocking-guards; deze direct voorafgaande binary is het veilige rollbackdoel;
    - activeer durable accept/reconcile pas wanneer alle instanties en callers compatibel zijn;
    - rollback laat additieve tabellen, pending rows en tombstones intact, verwerkt geen legacy-pad
      naast de inbox en veroorzaakt geen dubbel effect; herdeploy van de nieuwe reconciler hervat de
      bestaande rows;
    - een pre-compatibilitybinary die de guards niet kent is na activatie geen toegestaan
      rollbackdoel. Test mixed-version rollout, rollback met pending completion en herdeploy/recovery
      expliciet.
15. Werk minimaal `docs/technical/scheduled-jobs.md`, `docs/technical/modules.md`,
    `docs/factory/technical-spec.md`, relevante endpoints en runbook recoveryprocedure bij.

### Verplichte failure-injection

Gebruik een test-only injecteerbare port, bijvoorbeeld `CompletionFailureInjector`; geen productie-
env-var of verborgen debugendpoint. De default productie-implementatie doet niets. Ondersteun per
step minimaal:

- `BEFORE_EFFECT`;
- `AFTER_EFFECT_BEFORE_ACK`;
- `AFTER_ACK`.

Voeg exact
`softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/AgentCompletionRecoveryE2eTest.kt`
toe. Deze Failsafe-klasse gebruikt Testcontainers Postgres en dezelfde behouden tijdelijke
git-/artifact-/workspacestate over een opnieuw geconstrueerde service/reconciler. Zij is de
canonieke completion-recovery-E2E en voert minimaal de onderstaande crashmatrix, redelivery via
beide producerroutes en de rollout-/rollbackrecovery uit.

Test voor iedere duurzame stap ten minste dit scenario:

1. accepteer één completion;
2. injecteer op het gekozen punt een harde exception of gesimuleerde procescrash;
3. construeer de service/reconciler opnieuw tegen dezelfde Testcontainers-database en dezelfde
   tijdelijke git-/artifactstate — dit simuleert een echte restart, niet alleen een tweede methodecall;
4. laat de lease verlopen via een geïnjecteerde `Clock` indien nodig;
5. draai recovery tot de completion terminaal is;
6. voer dezelfde originele completion nogmaals aan via HTTP-equivalent én result-file-equivalent;
7. assert alle eindtoestanden en exacte aantallen.

Minimale matrix:

| Stap | Vóór effect | Ná effect/vóór ack | Ná ack + restart | Exact te asserten |
| --- | --- | --- | --- | --- |
| Accept/run sluiten | ja | ja | ja | één inbox, één gesloten run |
| Usage/kosten | ja | ja | ja | totalen exact eenmaal |
| Final story | ja | ja | ja | één bestand, correcte inhoud |
| Repositorysync | ja | ja | ja | één commit/push/PR |
| Metadata | ja | ja | ja | één consistente upsert |
| Events | ja | ja | ja | exact bron-eventaantal |
| Screenshots | ja | ja | ja | juiste set, geen duplicaten of verlies |
| Trackerresultaat/subtaken/comment | ja | ja | ja | juiste fase, één comment, één set subtaken |
| Knowledge | ja | ja | ja | één entry per businesskey |
| Commentmarkers | ja | ja | ja | monotone eindstatus |
| Cleanup | ja | ja | ja | pas na alle eerdere stappen; map uiteindelijk weg/behouden volgens beleid |

Voeg daarnaast tests toe voor leaseconcurrentie, bounded backoff, retrylimiet/handmatige recovery,
payloadconflict, onbekende container, twee gelijktijdige identieke callbacks en een crash terwijl
twee completions in dezelfde batch zitten.

Breid die aanvullende matrix verplicht uit met:

- een retryable fout tot aan de limiet, de atomische overgang van step en globaal naar
  `FAILED_PERMANENT`, meerdere schedulerpolls zonder nieuwe attempt en daarna uitsluitend een
  geautoriseerde handmatige requeue met bewaard actor/reason/timestamp-auditspoor;
- een ongeautoriseerde en een onvolledige requeue die beide zonder statuswijziging falen;
- een al geëindigde agent-run met niet-terminale completion, waarbij dispatch,
  actieve-faserecovery en PR-monitoring over meerdere polls niet verdergaan; na `COMPLETED` mag het
  bestaande vervolgpad precies eenmaal doorgaan en na `FAILED_PERMANENT` blijft automatische
  dispatch geblokkeerd;
- oversized payload, te grote collectie en te lange tekst met getypeerde rejection en zonder
  gedeeltelijke duurzame state;
- detailretentiepurgatie met behouden tombstone, gevolgd door late identieke en conflicterende
  redelivery en exacte no-duplicate-asserts;
- HTTP-contracttests die accepted en completed onderscheiden, oude responseparsers behouden en
  een eventuele `202` pas na bewezen callercompatibiliteit toestaan;
- schema-first rollout, de gedeclareerde compatibilityrollback met een pending completion en
  herdeploy van de nieuwe reconciler, zonder legacy-dubbelverwerking of verlies van de pending row.

### Acceptatiecriteria

- De volledige completionpayload en voortgang zijn na een JVM-restart uit Postgres te hervatten.
- Een identieke callback of opnieuw gelezen `agent-result.json` levert hetzelfde resultaat en geen
  dubbel effect; zij wordt niet als `NoActiveRun`/`404` behandeld.
- Een afwijkende tweede payload voor dezelfde agent-run wordt als conflict geregistreerd.
- Iedere step is duurzaam inspecteerbaar en heeft een stabiele idempotencystrategie.
- Failure-injection vóór, na en tussen effect/ack van iedere step eindigt na recovery in één
  volledige completion.
- Usage, kosten, events, comments, attachments, subtaken, knowledge, commits en PR's zijn niet
  verdubbeld.
- Twee reconcilers verwerken via lease/claim nooit gelijktijdig dezelfde completion; een verlopen
  lease wordt wel hervat.
- Een retryable fout gebruikt backoff en herstelt; een permanente fout is zichtbaar en veroorzaakt
  geen tight loop.
- Step en globale completion gebruiken `FAILED_PERMANENT`; alleen een geautoriseerde handmatige
  requeue met append-only actor/reason/timestamp-audit kan hervatten. Automatische retries of
  statusreset vanaf permanent zijn onmogelijk.
- Dispatch, actieve-faserecovery en PR-monitoring zetten een story/run niet verder zolang completion
  niet terminaal is. Een permanent gefaalde completion blijft operationeel geblokkeerd tot een
  expliciete operatoractie en start geen nieuwe agent.
- Workspacecleanup kan geen input verwijderen die een pending step nog nodig heeft.
- De externe completionrequest blijft backward compatible en contracttests dekken identieke
  redelivery, conflict en onbekende run.
- Het HTTP-contract benoemt duurzame acceptatie afzonderlijk van volledige completion; bestaande
  callers blijven compatibel en een statusroute maakt de finale uitkomst observeerbaar.
- Alleen minimaal benodigde, begrensde en geredigeerde data wordt opgeslagen. Detailretentie en
  storypurge zijn getest; payload-/veld-/collectielimieten en retentieduren hebben gedocumenteerde
  numerieke defaults en grenzen, en een behouden tombstone voorkomt re-executie na detailpurge.
- Additieve migratie, schema-first activation, compatibilityrollback en recovery van pending rows
  zijn getest zonder dubbel effect of dataverlies.
- `AgentCompletionRecoveryE2eTest` bestaat onder het exact voorgeschreven pad en draait via
  Failsafe `verify`.
- `AgentRunCompletionService` is een korte orchestrator; step handlers zijn apart unit-testbaar.
- De relevante Detekt-complexiteit en `TooManyFunctions`/`LongParameterList`-schuld daalt; de totale
  qualityscore stijgt niet.
- Actuele docs beschrijven acceptatie, recovery, leases, retrybeleid en operationele diagnose.

### Gerichte verificatie

Voer exact de bestaande unitklassen en de verplicht benoemde Failsafe-klasse uit:

```bash
mvn -pl softwarefactory -am \
  -Dtest=AgentRunCompletionServiceTest,AgentResultFileCompletionPollerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl softwarefactory -am -Dtest='*Completion*' \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl softwarefactory -am -Dit.test=AgentCompletionRecoveryE2eTest \
  -Dsurefire.failIfNoSpecifiedTests=false verify
./quality/run.sh
```

De exact benoemde Testcontainers failure-injection/restart-suite is verplicht; een wildcard,
hernoemde klasse of kleinere test vervangt haar niet. Leg per injectiepunt het resultaat vast en
gebruik geen filter dat de bestaande completiontests onbedoeld uitsluit. Er wordt nergens een test
geskipt.

### Canonieke volledige repositorygate per story

Voer voor de uiteindelijke gereviewde `REL-01`-commit én opnieuw op de gemergede default-branch-SHA
onvoorwaardelijk de volledige canonieke gate uit die plan 03 heeft opgeleverd:

```bash
mvn verify
./quality/run.sh
cd dashboard-frontend
flutter analyze
flutter test
cd ..
./factory build-images
```

Voer daarnaast, met de exacte commando's uit de plan-03-overdracht, de documentatie-audit inclusief
negatieve fixture, de agent-image-smoke en de branch-protectionaudit uit. Controleer de echte
`Repository verification` op de PR/review-SHA en na merge opnieuw op de gemergede SHA; een lokale
groene suite vervangt die check niet. Iedere ontbrekende of falende gate blokkeert de story. De root
`mvn verify` is verplicht en wordt nooit vervangen door een gerichte modulebuild of een commando met
testskip.

### Expliciet buiten scope

- Geen algemene eventbus, Kafka/RabbitMQ of distributed workflow-engine introduceren.
- Geen tracker-capabilitysplit (`ARC-04`) of brede module/packageverplaatsing uitvoeren.
- Geen dashboard application-refactor (`ARC-01`/`ARC-02`) uitvoeren; alleen minimale inspectie-
  informatie toevoegen waar recovery operationeel anders onzichtbaar blijft.
- Geen wijziging van agentrollen, fasebetekenis, mergebeleid, testbewijsbeleid of deploygedrag.
- Geen opruiming van historische migrations of herschrijven van bestaande migrationbestanden.
- Geen cleanup van andere god classes “omdat dependencies toch wijzigen”.

### Reviewer-aandachtspunten

- Controleer de transactiegrens van acceptatie en van lokaal idempotente effecten regel voor regel.
- Zoek specifiek naar het crashvenster na een extern effect maar vóór lokale stepbevestiging.
- Controleer dat payloadhashing canoniek is en geen secrets in logging/foutvelden lekt.
- Controleer databaseconstraints, leasequeries, `SKIP LOCKED`/CAS, indexen en klokinjectie.
- Bewijs dat additieve usage niet nog ergens buiten de idempotente stap wordt uitgevoerd.
- Controleer dat cleanup werkelijk als laatste staat en dat een permanent failurepad de workspace
  bewust bewaart of volgens gedocumenteerd beleid afhandelt.
- Weiger een generieke `runCatching { ... }.onFailure { log }` wanneer dit durable progress kan
  verliezen.
- Controleer de volledige storydiff en alle nieuwe/gewijzigde tests; geen verzwakte asserts,
  sleeps of toevallige timingafhankelijkheid accepteren.

### Tester-aandachtspunten

- Test de uiteindelijk gereviewde commit/SHA, niet een eerdere developercommit.
- Voer de failure-injectionmatrix onafhankelijk uit tegen een verse database én tegen een restart
  met behouden database/git/workspace.
- Controleer exacte aantallen in database, tracker, filesystem en fake GitHub; “uiteindelijk groen”
  zonder duplicatieasserts is onvoldoende.
- Test gelijktijdige identieke callbacks en twee reconcilers.
- Test minstens één werkelijk externe git/PR-simulatie waarbij het effect al bestaat vóór retry.
- Controleer dat pending/retry/permanent-failed operationeel zichtbaar zijn en fouttekst geredigeerd
  is.
- Iedere falende test, ook buiten completion, gaat terug naar de developer en blokkeert approval.

## Plan-afronding

Plan 04 is pas `AFGEROND` wanneer:

1. `REL-01` gemerged is zonder bypass en de default-branch-SHA bekend is;
2. reviewer en tester de uiteindelijke commit expliciet hebben goedgekeurd;
3. alle gerichte tests, de volledige failure-injectionmatrix en de volledige canonieke plan-03-gate
   (`mvn verify`, quality, Flutter, images, documentatie-audit, branch-protectionaudit en echte
   `Repository verification`) op review- én merge-SHA groen zijn;
4. geen testfailure als pre-existing of ongerelateerd is genegeerd;
5. database-, runtime-, endpoint- en recoverydocumentatie overeenkomen met de code;
6. `VOORTGANG.md` storykey, branch, PR, commits, testcommando's, tellingen en post-mergebewijs bevat;
7. er geen tijdelijke compatibilityfacade/TODO/featureflag zonder expliciete verwijderstory is
   achtergebleven;
8. de overdrachtscontrole hieronder groen is.

## Overdracht naar plan 05

Leg in `VOORTGANG.md` en het REL-01-worklog minimaal vast:

- de gemergede SHA en Flyway-versie(s);
- inbox- en stepstatussen met hun stabiele namen;
- idempotencykey per business-effect;
- lease-, retry-, backoff- en `FAILED_PERMANENT`-beleid plus de handmatige requeue-autorisatie en
  het auditrecord;
- dispatch-/actieve-faserecovery-/PR-monitoringguards per completionstatus;
- accepted-versus-completed HTTP-semantiek en de bewezen rollout-/rollbackprocedure;
- payloadlimieten, detailretentie, tombstoneretentie en purgecommando's;
- exacte failure-injectionmatrix en resultaten;
- welke completionclasses bewust klein zijn gemaakt en welke vervolgrefactors naar plan 05 gaan;
- alle gerichte en volledige testcommando's met exitcode/tellingen;
- open operationele aandachtspunten zonder ze als geaccepteerde rode tests te formuleren.

Controleer vóór overdracht:

```bash
git status --short
git log -1 --oneline
mvn verify
./quality/run.sh
cd dashboard-frontend
flutter analyze
flutter test
cd ..
./factory build-images
```

Voer bij deze overdrachtscontrole ook opnieuw de exacte plan-03-documentatie-audit en
branch-protectionaudit uit en leg de post-merge `Repository verification`-run vast.

Plan 05 mag pas starten wanneer plan 04 in `VOORTGANG.md` `AFGEROND` is en een nieuwe agent op de
actuele default branch kan aantonen dat completion-recovery en de volledige suite groen zijn.
