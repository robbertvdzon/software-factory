# SF-2277 - [Audit] Werk de documentatie bij voor de vervangen deploy-samenvattingsketen, en corrigeer de TLS-zin in de technical-spec

## Story

[Audit] Werk de documentatie bij voor de vervangen deploy-samenvattingsketen, en corrigeer de TLS-zin in de technical-spec

<!-- refined-by-factory -->

## Scope

Uitsluitend tekstwijzigingen in documentatie. Geen code, geen configuratie, geen migratie, geen testwijzigingen.

### 1. TLS-zin in de technical-spec corrigeren (eerst, kleinst, urgentst)

`docs/factory/technical-spec.md` (r51-52) beschrijft de OpenShift-route als
`insecureEdgeTerminationPolicy: Redirect`. De feitelijke en bewust gekozen waarde is `Allow`
(`deploy/base/softwarefactory-dashboard-frontend-route.yaml:18`, mét toelichtende comment).
Herschrijf die zin naar `Allow` met dezelfde onderbouwing als `deploy/README.md` §"HTTPS
enforcement": Cloudflare termineert publieke https en benadert de origin over plain http, dus
`Redirect` stuurt de client terug naar dezelfde publieke url — een lus die het dashboard én de
`/bridge`-websocket breekt; publieke http-naar-https-afdwinging hoort bij Cloudflare.
Het HSTS-deel van dezelfde zin (`Strict-Transport-Security: max-age=31536000`, bewust zonder
`includeSubDomains` en zonder `preload`) klopt en blijft ongewijzigd staan.

### 2. De vervallen deploy-samenvattingsketen uit de documentatie halen

De keten "markerblok `<!-- deploy-summary:start/end -->` → `FactoryOperations.deploySummaryFor` →
terugval op `## Samenvatting`" bestaat niet meer. `TelegramResultNotifyPoller` leest uitsluitend
`story.shortDescriptionSummary` (kolom uit migratie V36), strip't dat via `ControlJsonStripper` en
kapt af op 1000 tekens. Werk in deze volgorde bij:

- **`docs/factory/agents/summarizer.md` én de byte-identieke skeleton-kopie**
  `factory-common/src/main/resources/docs-skeleton/docs/factory/agents/summarizer.md` — dit eerst,
  want het is een instructie aan een productie-agent. Vervang het markerblok (r13-26) door de twee
  velden zoals `AgentPromptContracts.summarizerPrompt()` ze vraagt:
  - `descriptionSummary`: max. 10 zinnen, gewone taal, geen jargon — probleem, waarom, wat verandert,
    impact/geraakte onderdelen;
  - `shortDescriptionSummary`: max. 3 zinnen, "for dummies", geen jargon, geen technische details,
    geen bestands- of klassenamen; gaat naar de deploy-melding én de publieke changelog en moet dus
    op zichzelf begrijpelijk zijn.

  Maak de laatste regel `{"phase":"summarized","descriptionSummary":"…","shortDescriptionSummary":"…"}`
  en vermeld dat beide velden verplicht zijn bij `summarized`. Noem ook de route
  `{"phase":"summary-with-questions","questions":[…]}` (velden dan weglaten), waar het bestand nu
  helemaal over zwijgt. De huidige eindregel `{"phase":"summary-finished"}` vervalt daarmee.
  Beide kopieën blijven na afloop byte-identiek; verifieer met `diff`.

- **`docs/factory/technical-spec.md` (r444-451)** — vervang de drietrapsbron plus
  `FactoryOperations.deploySummaryFor` / `deploySummaryFrom` door de ene bron
  `short_description_summary`. Laat vermelden dat het strippen via `ControlJsonStripper` en het
  afkappen op 1000 tekens wél gebleven zijn.
- **`docs/technical/scheduled-jobs.md` (r234-237)** — idem, inclusief het schrappen van de vervallen
  bewering dat de poller `FactoryOperations` als extra dependency krijgt.
- **`docs/technical/modules.md` (r207-209)** — idem.
- **`docs/technical/overview.md` (r95-97)** — idem, plus schrappen van de vervallen zin dat het blok
  "onderdeel blijft van de ruwe summarizer-tekst en dus ook zichtbaar is in de tracker-comment, het
  einddocument en het dashboard".
- **`docs/factory/functional-spec.md` (r240-241)** — schrap de terugval "ontbreekt die, dan de
  `## Samenvatting` uit de story zelf". Ontbreekt de samenvatting, dan bestaat het bericht alleen uit
  de kop (+ eventuele link), zoals de rest van diezelfde bullet al correct zegt.
- **`docs/ontwerp-bridge-dashboard.md` (r102)** — schrap in dezelfde beweging de vermelding van de
  niet meer bestaande leesmethode `deploySummaryFor(key)` op de poort `core/FactoryOperations`;
  `testerReportFor(key)` blijft staan. Dit is de zevende vindplaats van dezelfde onwaarheid, gevonden
  tijdens refinement en niet in de oorspronkelijke opsomming.

### 3. Het publieke changelog-endpoint documenteren

- **`docs/technical/endpoints.md`** — voeg `GET /api/v1/public/changelog/{projectName}`
  (`web/controllers/ChangelogController.kt`) toe als eigen rij/tabel met doel: per project de
  `shortDescriptionSummary` van elke story die er een heeft, nieuwste eerst, met `timestamp`. Vul de
  paragraaf "Authenticatie" (r34-36) aan met het onderscheid dat de KDoc zelf maakt: bij de andere
  auth-vrije endpoints is netwerkisolatie de grens, hier is de inhoud zelf bewust publiek (bedoeld
  zodat andere apps via hun eigen backend een "wat is er nieuw"-lijst kunnen tonen). Vermeld dat de
  eigen dashboard-frontend hiervoor juist de geauthenticeerde bridge-route gebruikt.
- **`docs/ontwerp-bridge-dashboard.md` (Reads-tabel, r209-222)** — voeg de bijbehorende
  bridge-operatie `changelog.for` toe (`BridgeRequestHandler.kt:113` →
  `dashboardService.changelogFor(name)`, parameter `name`).

### Buiten scope

- Herschrijven of herstructureren van de specs zelf.
- De endpoint-tellingen in `docs/technical/README.md` (r10: "39") en de intro-telling in
  `docs/technical/endpoints.md` (r3: "6", feitelijk 12 mapping-annotaties) — eigen, losstaande
  opruiming.
- De ontbrekende bridge-operatie `projects.recentCommits` in `docs/ontwerp-bridge-dashboard.md`.
- Elke wijziging aan code, testcode, `deploy/`, `runbook.md` of `deploy/README.md` (die twee zijn al
  correct).
- Bestanden onder `docs/stories/` (historische verslagen; die mogen de oude markers houden).

## Acceptance criteria

1. `docs/factory/technical-spec.md` beschrijft `insecureEdgeTerminationPolicy: Allow` met de
   Cloudflare-onderbouwing; `grep -n "insecureEdgeTerminationPolicy: Redirect" docs/` geeft nul
   treffers buiten `docs/stories/`. De HSTS-zin staat er inhoudelijk ongewijzigd.
2. `grep -rn "deploy-summary\|deploySummaryFor\|deploySummaryFrom" docs/ factory-common/src/main/resources/docs-skeleton/`
   geeft nul treffers buiten `docs/stories/`.
3. `docs/factory/agents/summarizer.md` beschrijft `descriptionSummary` (max. 10 zinnen) en
   `shortDescriptionSummary` (max. 3 zinnen, "for dummies", deploy-melding + publieke changelog),
   eindigt op `{"phase":"summarized","descriptionSummary":"…","shortDescriptionSummary":"…"}` met de
   vermelding dat beide velden daar verplicht zijn, en noemt de route
   `{"phase":"summary-with-questions","questions":[…]}`. `{"phase":"summary-finished"}` komt er niet
   meer in voor.
4. `diff docs/factory/agents/summarizer.md factory-common/src/main/resources/docs-skeleton/docs/factory/agents/summarizer.md`
   geeft geen verschil.
5. Elk van de zes plekken uit scope-punt 2 (technical-spec, scheduled-jobs, modules, overview,
   functional-spec, ontwerp-bridge-dashboard r102) noemt `short_description_summary` als enige bron,
   zonder drietrapsbron, zonder `FactoryOperations`-dependency-bewering en zonder
   `## Samenvatting`-terugval; `ControlJsonStripper` en de 1000-tekengrens blijven expliciet vermeld
   op de plekken waar ze nu staan.
6. `docs/technical/overview.md` bevat de zin over zichtbaarheid in tracker-comment/einddocument/
   dashboard niet meer.
7. `docs/technical/endpoints.md` bevat `GET /api/v1/public/changelog/{projectName}` met doel en
   responsevorm, en de auth-paragraaf benoemt het onderscheid netwerkisolatie versus bewust publieke
   inhoud.
8. De Reads-tabel in `docs/ontwerp-bridge-dashboard.md` bevat een rij `changelog.for` die naar
   `changelogFor(name)` verwijst.
9. `git diff --name-only` toont uitsluitend `.md`-bestanden onder `docs/` en
   `factory-common/src/main/resources/docs-skeleton/`, plus het worklog. Geen `.kt`, `.dart`,
   `.yaml`, `.sql` of `.sh`.
10. Alle nieuwe/aangepaste regel- en bestandsverwijzingen in de tekst kloppen met de checkout op het
    moment van de wijziging.

## Aannames

- Verificatie is puur documentair: teksten lezen en `grep`/`diff` draaien. Er is geen build of test
  die deze documenten valideert, en er wordt er ook geen toegevoegd.
- De regelnummers in deze story zijn gecontroleerd op de huidige checkout en kloppen; wijken ze bij
  implementatie af, dan is de geciteerde tekst leidend, niet het nummer.
- Bestanden onder `docs/stories/` zijn historische verslagen van afgeronde stories en worden niet
  aangepast, ook al bevatten ze `deploy-summary`-markers.
- `deploy/README.md` (§HTTPS enforcement) en `runbook.md` staan al op `Allow` en blijven ongemoeid;
  de technical-spec neemt hun onderbouwing over, niet andersom.
- De correctie in `docs/ontwerp-bridge-dashboard.md:102` is toegevoegd omdat het exact dezelfde
  onjuistheid is als de zes opgesomde plekken en het schrappen van één naam geen speculatie vraagt;
  het is geen uitbreiding naar een herschrijving van dat document.
- De stale telling "6 HTTP endpoints" in `docs/technical/endpoints.md:3` blijft staan: hij is nu al
  fout (12 mapping-annotaties) om redenen los van deze story en hoort bij de aparte
  endpoint-tellingopruiming.
</!-- proposed-description:end -->

## Eindsamenvatting

Ik heb `.task.md`, de story-diff en het volledige worklog (developer, reviewer, tester) gelezen. Hieronder de eindsamenvatting.

## Eindsamenvatting SF-2277 — Documentatie-audit: TLS-zin, vervallen deploy-samenvattingsketen, publiek changelog-endpoint

**Wat is gebouwd**

Puur een documentatiecorrectie: 9 `.md`-bestanden plus het worklog, geen enkele regel code, config, migratie of test (`git diff --name-only main...HEAD` bevat alleen `.md` onder `docs/` en `factory-common/src/main/resources/docs-skeleton/`).

1. **TLS-zin gecorrigeerd** — `docs/factory/technical-spec.md` beschreef de OpenShift-route als `insecureEdgeTerminationPolicy: Redirect`; dat is feitelijk `Allow` (`deploy/base/softwarefactory-dashboard-frontend-route.yaml:18`). Herschreven met dezelfde onderbouwing als `deploy/README.md` §HTTPS enforcement: Cloudflare termineert de publieke https en benadert de origin over plain http, dus `Redirect` maakt een lus die het dashboard én de `/bridge`-websocket breekt. De HSTS-zin is inhoudelijk ongewijzigd.
2. **Vervallen deploy-samenvattingsketen geschrapt** — het markerblok `<!-- deploy-summary:start/end -->` → `FactoryOperations.deploySummaryFor` → terugval op `## Samenvatting` bestaat niet meer. Op alle zeven vindplaatsen (technical-spec, scheduled-jobs, modules, overview, functional-spec, ontwerp-bridge-dashboard r102 en de summarizer-instructie) vervangen door de ene bron `short_description_summary` (kolom uit V36, gelezen door `TelegramResultNotifyPoller`). `ControlJsonStripper` en de 1000-tekengrens blijven expliciet vermeld; de onjuiste bewering over een extra `FactoryOperations`-dependency en de zichtbaarheidszin in `overview.md` zijn weg.
3. **Summarizer-instructie op het echte contract gezet** — `docs/factory/agents/summarizer.md` beschrijft nu `descriptionSummary` (max. 10 zinnen) en `shortDescriptionSummary` (max. 3 zinnen, "for dummies", deploy-melding + publieke changelog), eindigt op `{"phase":"summarized",…}` met beide velden verplicht, en noemt de tot nu toe ongenoemde route `{"phase":"summary-with-questions","questions":[…]}`. `{"phase":"summary-finished"}` is verdwenen. De docs-skeleton-kopie is byte-identiek bijgewerkt.
4. **Publiek changelog-endpoint gedocumenteerd** — `GET /api/v1/public/changelog/{projectName}` toegevoegd aan `docs/technical/endpoints.md` met doel en responsevorm (`timestamp` + `shortDescriptionSummary`, nieuwste eerst), en de auth-paragraaf benoemt nu het onderscheid netwerkisolatie versus bewust publieke inhoud. In `docs/ontwerp-bridge-dashboard.md` is de rij `changelog.for` aan de Reads-tabel toegevoegd.

**Gemaakte keuzes**

- De summarizer-instructie is als eerste aangepast: het is een live instructie aan een productie-agent.
- Bij de rij `changelog.for` is bewust geen delegate-klassenaam genoemd; de injectie is `DashboardQueries`, niet `FactoryDashboardService` zoals in oudere rijen.
- Bij afwijkende regelnummers is de geciteerde tekst leidend gehouden, niet het nummer.

**Wat is getest**

Verificatie is documentair (er is geen build of test die deze documenten valideert), aangevuld met een kruiscontrole van elke bewering tegen de checkout. Alles groen: `mvn -B verify` BUILD SUCCESS (0 failures/errors), `tools/audit-documentation` PASS, greps op `deploy-summary|deploySummaryFor|deploySummaryFrom`, `insecureEdgeTerminationPolicy: Redirect` en `summary-finished` geven 0 treffers buiten `docs/stories/`, `diff` van de twee summarizer-bestanden is leeg. Reviewer en tester vonden geen blockers; alle 10 acceptatiecriteria zijn afgevinkt.

**Bewust niet gedaan**

De stale endpoint-tellingen in `docs/technical/README.md` ("39") en `docs/technical/endpoints.md` ("6", feitelijk 12 mapping-annotaties), de ontbrekende bridge-operatie `projects.recentCommits`, en `docs/stories/**` (historische verslagen houden hun oude markers). Eén informatieve reviewopmerking: de telling "6 HTTP endpoints" wordt met de nieuwe tabel wat zichtbaarder — kandidaat voor de aparte opruimstory.
