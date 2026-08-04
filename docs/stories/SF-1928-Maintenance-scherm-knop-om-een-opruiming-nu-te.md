# SF-1928 - Maintenance-scherm: knop om een opruiming nu te draaien

## Story

Maintenance-scherm: knop om een opruiming nu te draaien

<!-- refined-by-factory -->

## Samenvatting

De factory ruimt op vijf plekken vanzelf op, maar alleen op eigen ritme: 's nachts, elk uur, of elke paar seconden. Wil je een opruiming zien draaien of controleren wat een gewijzigde instelling doet, dan moet je wachten tot de volgende ronde.

Op het Opruimen-scherm komt daarom per soort een "Nu draaien"-knop, plus één knop die alles achter elkaar draait. Je klikt, de ronde start op de achtergrond en verschijnt daarna in dezelfde lijst — herkenbaar als handmatig gestart. Gaat er iets mis, dan zie je dat als foutmelding en als foutregel in de lijst.

Draait dezelfde opruiming al (handmatig of via het schema), dan krijg je een nette melding in plaats van een tweede ronde. De bestaande schema's blijven ongewijzigd; de knop komt ernaast.

## Scope

### Datamodel
- Migratie `V32` op `maintenance_cleanup_runs`: kolom `trigger TEXT NOT NULL DEFAULT 'scheduled'` met de afgesproken waarden `scheduled` en `manual`. Vrije TEXT met de waarden als afspraak in code, net als `kind` in `V31`. Bestaande rijen krijgen `scheduled`.
- `MaintenanceCleanupRunRecord` / `NewMaintenanceCleanupRun` krijgen het veld; `trigger` gaat mee in de lees-DTO's van `maintenance.cleanupsList` en `maintenance.cleanupDetail`.
- Retentie op deze tabel (`sf.maintenance.run-retention-days`) blijft ongewijzigd en dekt de nieuwe rijen automatisch.

### Backend — één ronde, twee triggers
- De handmatige run roept dezelfde code aan als de scheduler; er komt geen tweede implementatie. Concreet betekent dat per soort een aanroepbaar "doe één ronde"-entrypoint met een trigger-parameter, waar zowel de `@Scheduled`-methode als de handmatige route op uitkomt:
  - `github-releases`: de bestaande tick van `MaintenanceCleanupScheduler` (alle projecten, inclusief dry-run-stand en `details`-uitsplitsing).
  - `agent-events`, `agent-runs`, `workspaces`: de bestaande `cleanupOnce()`-methodes.
  - `completion-payloads`: de bestaande payload-purge die nu inline in de completion-recovery hangt.
- De vijf entrypoints worden bereikbaar gemaakt voor `dashboard` via root-package-poorten, volgens het bestaande precedent (`runtime.AgentLogApi`, `pipeline.DeployTargetStatusApi`): een poort in `runtime` (root) voor de vier factory-brede opruimers en een poort in `maintenance` (root) voor de GitHub-cleanup, geïmplementeerd in de bestaande `…services`/`…workspaces`-klassen. `bridge` blijft buiten `maintenance`/`runtime` en loopt via `dashboard`. `ModulithArchitectureTest` moet groen blijven zonder de bestaande `allowedDependencies` op te rekken met interne subpackages.
- Een handmatige ronde negeert de `enabled`-vlag van het betreffende mechanisme niet: staat een opruimer uitgezet (`SF_AGENT_EVENT_RETENTION_ENABLED`, `SF_AGENT_RUN_RETENTION_ENABLED`, `SF_WORK_CLEANUP_ENABLED`), dan doet de knop niets en levert dat een nette melding op, geen stille no-op.

### Backend — dubbel-draaien-bescherming
- Eén in-memory bewaking per soort, in de factory-JVM, die zowel het handmatige als het geplande pad afdekt. Loopt er al een ronde van dat soort, dan start er geen tweede en komt er een expliciete "draait al"-uitkomst terug (voor de handmatige route) respectievelijk een overgeslagen tick met een logregel (voor de scheduler).
- De "alles draaien"-knop start de soorten die vrij zijn en slaat de soorten over die al lopen; het antwoord vertelt wat er gestart is en wat is overgeslagen.

### Backend — asynchroon + logging
- De handmatige start is niet-blokkerend: het verzoek zet de ronde weg op een executor en antwoordt meteen. De 30s-timeout van de bridge mag nooit een lange GitHub-ronde afkappen.
- Antwoordvorm volgens het `audit.runNow`-precedent: HTTP 200 met een statusveld in plaats van een foutcode voor "geweigerd". Statussen: `started`, `already_running`, `disabled`, `unknown_kind`. Echte fouten (onbekende parameters, factory offline) blijven het bestaande `BridgeError`-pad volgen.
- Elke handmatige ronde levert altijd een rij in `maintenance_cleanup_runs` op, ook bij 0 opgeruimde items — anders is het acceptatiecriterium "zie je de ronde daarna in de lijst" niet haalbaar. De bestaande onderdrukkingsregel in `CleanupLogWriter` (alleen loggen bij werk of fout) blijft gelden voor `scheduled`-rondes; die is er om de ~2s-payload-purge en de uurpollers de log niet te laten verzuipen.
- Faalt een handmatige ronde, dan komt de rij er met gevulde `error` en `trigger = manual`.

### Bridge / API
- Nieuwe schrijfoperatie `maintenance.runNow` met parameter `kind` (een waarde uit `CleanupKinds`, of de afgesproken "alles"-waarde), aangehangen in het bestaande `dispatchSystemAction`-blok van `BridgeRequestHandler` — geen nieuw vijfde when-blok (LongMethod-ratchet).
- Nieuw endpoint `POST /api/v1/maintenance/run` in `BridgeApiController`, met autorisatie en foutvertaling volgens het bestaande `POST /api/v1/audits/run-now`-patroon.
- `maintenance.cleanupsList` geeft naast `runs` ook de soorten terug die op dit moment draaien, zodat het scherm knoppen kan uitzetten en kan blijven pollen tot de ronde klaar is.

### Frontend
- In `maintenance_screen.dart` een knoppenrij met per soort een "Nu draaien" plus één "Alles draaien", qua widget en look-and-feel gelijk aan de `Wrap` met `TextButton`s in `audit_screen.dart` (regels 221-245).
- Terugkoppeling via `showActionResult` (SnackBar, rood bij mislukken), met een begrijpelijke tekst per status — hetzelfde patroon als `_runNowMessage` in `audit_screen.dart`.
- Een knop is uitgeschakeld zolang er een verzoek loopt én zolang de backend meldt dat dat soort draait; twee keer snel klikken kan dus geen tweede ronde starten, en ook niet als het scherm in een tweede tab openstaat.
- De lijst ververst na het starten en blijft licht pollen zolang er nog een ronde draait, zodat de afgeronde ronde vanzelf verschijnt; daarna stopt het pollen. Het scherm heeft daarvoor een `GlobalKey<DataScreenState>` nodig, zoals `audit_screen.dart` die gebruikt.
- Rijen met `trigger = manual` krijgen in de lijst en op de detailpagina een badge "handmatig", naast de bestaande soort-, dry-run- en fout-badges.

### Documentatie
- `docs/technical/scheduled-jobs.md` beschrijft alle vijf de mechanismen (§5, §7, §8) en de gedeelde opruim-log; daar komt de handmatige route, het `trigger`-veld en de dubbel-draaien-bescherming bij. Cron- en poll-schema's blijven in die documentatie ongewijzigd.

### Buiten scope
- Geen Telegram-meldingen; dit blijft puur zichtbaar in het dashboard.
- Geen wijziging van bestaande retentie-defaults, cron-expressies of poll-intervallen.
- Geen handmatige run per afzonderlijk project voor de GitHub-cleanup.
- Geen nieuwe opruimsoorten, geen wijziging aan wat een opruiming precies weggooit.

## Acceptance criteria

1. Op het Opruimen-scherm staat per opruimsoort een "Nu draaien"-knop plus één knop die alle soorten draait, in dezelfde stijl als de "Run now"-knoppen op het Audits-scherm.
2. Een klik start de ronde zonder dat de UI blokkeert; ook een lange GitHub-ronde levert direct een antwoord en loopt niet in een timeout.
3. Na afloop verschijnt de ronde in de lijst zonder dat de gebruiker handmatig hoeft te verversen, met een zichtbare markering "handmatig".
4. Ook een handmatige ronde die niets opruimde, levert een rij in de lijst op; geplande rondes van de vaak draaiende mechanismen blijven ongelogd bij 0 items zonder fout.
5. Twee keer snel klikken start één ronde; de tweede klik levert een zichtbare melding "draait al" op. Hetzelfde geldt als de scheduler dat soort net draait.
6. Draait een handmatige ronde terwijl de scheduler voor dat soort afgaat, dan slaat de scheduler die tick over in plaats van parallel te draaien.
7. Een mislukte handmatige ronde toont een foutmelding in de UI en komt als foutregel (gevulde `error`, markering handmatig) in de lijst en op de detailpagina.
8. Een handmatige run van een uitgezette opruimer start niet en meldt dat zichtbaar.
9. De cron-expressie en de poll-intervallen van alle vijf de mechanismen zijn ongewijzigd, en het scherm blijft de bestaande soort-filter- en detailfunctionaliteit houden.
10. Unit-tests dekken: starten per soort, "alles draaien", de dubbel-draaien-bescherming (handmatig + handmatig én handmatig + scheduler), het altijd-loggen van een handmatige ronde, het foutpad, en de nieuwe bridge-operatie op zowel factory- als dashboard-backend-kant. Een migratietest dekt `V32` (bestaande rijen worden `scheduled`).
11. Een widget-test in `maintenance_screen_test.dart` dekt de knop: klikken doet de POST, toont de melding en zet de knop uit terwijl de ronde loopt.
12. `mvn verify`, `flutter analyze`, `flutter test` en de kwaliteitsratchet zijn groen; `ModulithArchitectureTest` blijft groen zonder interne subpackages aan `allowedDependencies` toe te voegen.

## Aannames

- **GitHub-cleanup draait factory-breed, niet per project.** De story noemt een projectparameter als optie ("eventueel"); de bestaande tick loopt over alle projecten en er is geen per-project entrypoint. Een handmatige `github-releases`-run doet daarom exact dezelfde ronde als de cron: alle projecten met een release-cleanup-configuratie, elk met een eigen rij in de log. Dat houdt "geen tweede implementatie" letterlijk waar.
- **De log-retentie (`purgeOldRuns`) blijft aan de cron hangen** en wordt niet meegedraaid door een handmatige knop; het is opruiming van de log zelf, geen opruimsoort in het scherm.
- **De bewaking is in-geheugen, per soort, binnen de factory-JVM.** De factory draait als één proces en zowel de schedulers als de bridge-afhandeling zitten daarin; een DB-lock is daarvoor niet nodig. Draait de factory ooit met meerdere instanties, dan is dit het punt om te herzien.
- **"Foutmelding in de UI" bij een asynchrone run betekent twee dingen**: een directe melding bij een verzoek dat meteen geweigerd wordt (draait al / uitgezet / onbekend soort / factory offline), en de foutregel in de lijst zodra een gestarte ronde faalt. Een gestarte ronde kan per definitie niet meer synchroon terugmelden.
- **De dry-run-stand (`sf.maintenance.dry-run`) geldt ook voor een handmatige GitHub-run**; de knop is geen ontsnapping aan die instelling en de rij krijgt gewoon de dry-run-markering.
- **De soort-sleutels blijven de bestaande `CleanupKinds`-waarden**, ook als knoplabel/parameter — geen nieuwe vertaallaag, in lijn met de keuze in SF-1921 om de sleutel zelf te tonen.
- **`V32` is het eerstvolgende vrije migratienummer** (`V31__maintenance_cleanup_kinds.sql` is de laatste op `main`).

## Eindsamenvatting

# Eindsamenvatting SF-1928 — Maintenance-scherm: knop om een opruiming nu te draaien

## Wat is er gebouwd

Het Opruimen-scherm kan nu per opruimsoort een ronde handmatig starten, plus één knop die alle soorten achter elkaar draait. De vijf mechanismen (`github-releases`, `agent-events`, `agent-runs`, `workspaces`, `completion-payloads`) blijven daarnaast gewoon op hun eigen schema draaien.

- **Datamodel:** migratie `V32` voegt een `trigger`-kolom toe (`scheduled`/`manual`, bestaande rijen worden `scheduled`); het veld gaat mee in de lijst- en detailweergave.
- **Backend:** per soort één gedeeld "doe één ronde"-entrypoint dat zowel de scheduler als de handmatige route gebruikt — geen tweede implementatie. Een in-memory bewaking per soort voorkomt dat er twee rondes tegelijk lopen, in beide richtingen (handmatig+handmatig, handmatig+scheduler).
- **API:** nieuwe bridge-operatie `maintenance.runNow` en `POST /api/v1/maintenance/run`, niet-blokkerend: het verzoek antwoordt direct met een status (`started`, `already_running`, `disabled`, `unknown_kind`), ook bij een lange GitHub-ronde. De lijstquery geeft nu ook de op dat moment draaiende soorten terug.
- **Frontend:** knoppenrij in dezelfde stijl als de Run now-knoppen op het Audits-scherm, meldingen per status, knoppen uit zolang een soort draait, automatisch bijwerken van de lijst (pollen elke 3s, stopt vanzelf) en een `handmatig`-badge in lijst én detail.

Omvang: 45 bestanden, ~2000 regels toegevoegd.

## Belangrijke keuzes

- **Geen tweede implementatie.** De bestaande pollers en de GitHub-tick zijn achter één gedeelde vorm gezet; de payload-purge is uit een te grote klasse gehaald naar een eigen component zodat hij van buitenaf aanroepbaar werd. Gedrag ongewijzigd.
- **Bewaking in geheugen, niet in de database.** De factory draait als één proces; bij meerdere instanties is dit het punt om te herzien.
- **"Draait al" is geen fout** (niet-rode melding): de payload-purge bezet de bewaking elke ~2 seconden kort, dus dit is een normale uitkomst.
- **Handmatige rondes worden altijd gelogd**, ook bij 0 opgeruimde items; voor geplande rondes blijft de bestaande onderdrukking gelden zodat de log niet volloopt.
- **Modulith-grenzen intact:** toegang via root-package-poorten volgens bestaand precedent, zonder interne subpackages aan de toegestane afhankelijkheden toe te voegen.

## Wat is getest

- `mvn verify` vanaf de repo-root: BUILD SUCCESS, 0 failures/errors (1060 tests over alle modules).
- `flutter analyze`: geen issues. `flutter test`: 131 tests groen, waarvan 17 op het Opruimen-scherm.
- Nieuwe dekking: starten per soort, "alles draaien" met overgeslagen soorten, dubbel-draaien in beide richtingen, altijd-loggen, foutpad, uitgezette opruimer, onbekende soort, de bridge-operatie op factory- én dashboard-kant, en een migratietest op `V32`.
- Kwaliteitsratchet gedraaid: de twee resterende meldingen zijn aantoonbaar bestaande drift op `main` (gereproduceerd op een schone worktree), niet uit deze story. De baseline is niet opgerekt.
- Documentatie- en architectuurchecks (`audit-documentation`, `check-composition-roots`, `module-dependencies --check`) groen.

## Bewust niet gedaan

- Geen Telegram-meldingen; alles blijft zichtbaar in het dashboard.
- Geen wijziging aan cron-expressies, poll-intervallen of retentie-defaults — geverifieerd dat de diff geen enkel schema raakt.
- Geen handmatige run per afzonderlijk project voor de GitHub-cleanup; die ronde doet net als de cron alle projecten.
- Geen nieuwe opruimsoorten en geen wijziging aan wat er precies wordt opgeruimd.
- De log-retentie (het opruimen van de opruimlog zelf) blijft aan de cron hangen en zit niet achter een knop.

## Aandachtspunten (niet blokkerend)

- In dezelfde tab levert een tweede snelle klik geen "draait al"-melding op, omdat de knop dan al uitstaat — dat is wat de scope vroeg; het "draait al"-pad geldt voor een tweede tab of een lopende scheduler-ronde.
- `docs/technical/scheduled-jobs.md` §8 noemt de payload-purge nog op de oude plek; SF-1933 (documentatie) kan dat rechttrekken.
- Een handmatige GitHub-ronde in een factory zonder project met release-cleanup levert geen rij op, omdat die logregel per project wordt geschreven.
- Er was geen preview-omgeving beschikbaar, dus de UI is geverifieerd via widget-tests en niet via een draaiend scherm.

<!-- deploy-summary:start -->
Op het Opruimen-scherm kun je vanaf nu zelf een opruiming starten, per soort of allemaal tegelijk, zonder te wachten tot de factory dat vanzelf doet. De lijst werkt daarna automatisch bij en laat zien welke rondes je zelf hebt gestart. Draait er al een opruiming, of staat er een uit, dan krijg je dat netjes te zien in plaats van een dubbele ronde.
<!-- deploy-summary:end -->
