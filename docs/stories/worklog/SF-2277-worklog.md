# SF-2277 - Worklog

Story-context bij eerste pickup:
Documentatie corrigeren: TLS-zin, vervallen deploy-samenvattingsketen en publiek changelog-endpoint

Uitsluitend .md-wijzigingen; geen code, config, migratie of testcode.

Volgorde:
1. docs/factory/technical-spec.md (r51-52): herschrijf de OpenShift-route-zin van 'insecureEdgeTerminationPolicy: Redirect' naar 'Allow', met dezelfde onderbouwing als deploy/README.md §HTTPS enforcement (Cloudflare termineert publieke https en benadert de origin over plain http; Redirect stuurt de client terug naar dezelfde publieke url - een lus die het dashboard en de /bridge-websocket brak; publieke http->https-afdwinging hoort bij Cloudflare). Laat het HSTS-deel van dezelfde zin inhoudelijk ongewijzigd.
2. docs/factory/agents/summarizer.md: vervang het markerblok <!-- deploy-summary:start/end --> (r13-26) door de twee velden zoals AgentPromptContracts.summarizerPrompt() (r285-303) ze vraagt: descriptionSummary (max. 10 zinnen, gewone taal, geen jargon - probleem, waarom, wat verandert, impact/geraakte onderdelen) en shortDescriptionSummary (max. 3 zinnen, 'for dummies', geen jargon/technische details/bestands- of klassenamen; gaat naar de deploy-melding EN de publieke changelog en moet op zichzelf begrijpelijk zijn). Maak de laatste regel {"phase":"summarized","descriptionSummary":"…","shortDescriptionSummary":"…"} en vermeld dat beide velden daar verplicht zijn. Noem ook de route {"phase":"summary-with-questions","questions":[…]} (velden dan weglaten). De huidige eindregel {"phase":"summary-finished"} vervalt. Werk factory-common/src/main/resources/docs-skeleton/docs/factory/agents/summarizer.md identiek bij en verifieer met diff dat beide byte-identiek blijven. Doe dit vóór de overige docs: het is een live instructie aan een productie-agent.
3. Haal de vervallen keten (markerblok -> FactoryOperations.deploySummaryFor/deploySummaryFrom -> terugval op '## Samenvatting') uit de zes resterende plekken en vervang hem door de ene bron short_description_summary (kolom uit V36), gelezen door TelegramResultNotifyPoller:
   - docs/factory/technical-spec.md r444-451: drietrapsbron + deploySummaryFor/deploySummaryFrom eruit; vermeld dat ControlJsonStripper en het afkappen op 1000 tekens wél gebleven zijn.
   - docs/technical/scheduled-jobs.md r234-237: idem, inclusief schrappen van de bewering dat de poller FactoryOperations als extra dependency krijgt.
   - docs/technical/modules.md r207-209: idem.
   - docs/technical/overview.md r95-97: idem, plus schrappen van de zin dat het blok 'onderdeel blijft van de ruwe summarizer-tekst en dus ook zichtbaar is in de tracker-comment, het einddocument en het dashboard'.
   - docs/factory/functional-spec.md r240-241: schrap de terugval 'ontbreekt die, dan de ## Samenvatting uit de story zelf'; ontbreekt de samenvatting, dan bestaat het bericht alleen uit de kop (+ eventuele link).
   - docs/ontwerp-bridge-dashboard.md r102: schrap de leesmethode deploySummaryFor(key) op de poort core/FactoryOperations; testerReportFor(key) blijft staan.
4. docs/technical/endpoints.md: voeg GET /api/v1/public/changelog/{projectName} (web/controllers/ChangelogController.kt) toe als eigen rij/tabel met doel en responsevorm (per project de shortDescriptionSummary van elke story die er een heeft, nieuwste eerst, met timestamp). Vul de paragraaf 'Authenticatie' aan met het onderscheid dat de KDoc zelf maakt: bij de andere auth-vrije endpoints is netwerkisolatie de grens, hier is de inhoud zelf bewust publiek (zodat andere apps via hun eigen backend een 'wat is er nieuw'-lijst kunnen tonen); vermeld dat de eigen dashboard-frontend hiervoor juist de geauthenticeerde bridge-route gebruikt.
5. docs/ontwerp-bridge-dashboard.md Reads-tabel (r209-222): voeg de rij changelog.for toe (BridgeRequestHandler.kt:113 -> dashboardService.changelogFor(name), parameter name).

Buiten scope, niet aanraken: docs/technical/README.md r10 ('39'), de intro-telling in endpoints.md r3 ('6', feitelijk 12), de ontbrekende operatie projects.recentCommits, herschrijven/herstructureren van de specs zelf, docs/stories/** (historische verslagen mogen hun oude markers houden), en alle code, testcode, deploy/, runbook.md en deploy/README.md.

Afsluitende zelfreview vóór oplevering: grep -rn 'deploy-summary|deploySummaryFor|deploySummaryFrom' docs/ factory-common/src/main/resources/docs-skeleton/ = 0 treffers buiten docs/stories/; grep -n 'insecureEdgeTerminationPolicy: Redirect' docs/ = 0 treffers buiten docs/stories/; diff van de twee summarizer.md-bestanden is leeg; git diff --name-only toont uitsluitend .md onder docs/ en docs-skeleton/ plus het worklog. Bij afwijkende regelnummers is de geciteerde tekst leidend, niet het nummer; controleer dat nieuwe/aangepaste bestands- en regelverwijzingen kloppen met de checkout.

Stappenplan:
[x]: read issue and target docs
[x]: TLS-zin in docs/factory/technical-spec.md corrigeren naar `Allow`
[x]: summarizer.md + byte-identieke docs-skeleton-kopie herschrijven naar de twee summary-velden
[x]: vervallen deploy-samenvattingsketen uit de zes resterende docs-plekken halen
[x]: publiek changelog-endpoint + bridge-operatie `changelog.for` documenteren
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `docs/factory/technical-spec.md` (§dashboard-frontend): `insecureEdgeTerminationPolicy: Redirect`
  → `Allow`, met de onderbouwing uit `deploy/README.md` §HTTPS enforcement (Cloudflare termineert
  publieke https en benadert de origin over plain http; `Redirect` maakt een lus die het dashboard
  én de `/bridge`-websocket breekt). Het HSTS-deel van dezelfde zin is inhoudelijk ongewijzigd.
- `docs/factory/agents/summarizer.md`: het markerblok `<!-- deploy-summary:start/end -->` vervangen
  door `descriptionSummary` (max. 10 zinnen) en `shortDescriptionSummary` (max. 3 zinnen, "for
  dummies", deploy-melding + publieke changelog), conform `RolePrompts.summarizerPrompt()`. De
  eindregel is nu `{"phase":"summarized","descriptionSummary":"…","shortDescriptionSummary":"…"}`
  met de vermelding dat beide velden daar verplicht zijn, plus de tot nu toe ongenoemde route
  `{"phase":"summary-with-questions","questions":[…]}`. `{"phase":"summary-finished"}` is weg.
  `factory-common/src/main/resources/docs-skeleton/docs/factory/agents/summarizer.md` is identiek
  bijgewerkt; `diff` tussen beide is leeg. Dit eerst gedaan omdat het een live instructie aan een
  productie-agent is.
- Vervallen keten (markerblok → `FactoryOperations.deploySummaryFor/deploySummaryFrom` → terugval
  op `## Samenvatting`) vervangen door de ene bron `short_description_summary` (kolom uit migratie
  V36, gelezen door `TelegramResultNotifyPoller`) in: `docs/factory/technical-spec.md`,
  `docs/technical/scheduled-jobs.md` (incl. de vervallen bewering dat de poller `FactoryOperations`
  als extra dependency krijgt), `docs/technical/modules.md`, `docs/technical/overview.md` (incl. de
  zin over zichtbaarheid in tracker-comment/einddocument/dashboard), `docs/factory/functional-spec.md`
  (terugval geschrapt) en `docs/ontwerp-bridge-dashboard.md` (`deploySummaryFor(key)` van de poort
  `core/FactoryOperations`; `testerReportFor(key)` blijft). `ControlJsonStripper` en de
  1000-tekengrens staan nog expliciet in technical-spec, scheduled-jobs.
- `docs/technical/endpoints.md`: nieuwe sectie voor `GET /api/v1/public/changelog/{projectName}`
  (`web/controllers/ChangelogController.kt`) met doel en responsevorm (`timestamp` +
  `shortDescriptionSummary`, nieuwste eerst). De paragraaf "Authenticatie" benoemt nu het
  onderscheid uit de KDoc: bij de andere auth-vrije endpoints is netwerkisolatie de grens, hier is
  de inhoud zelf bewust publiek; de eigen dashboard-frontend gebruikt juist de geauthenticeerde
  bridge-route.
- `docs/ontwerp-bridge-dashboard.md`: rij `changelog.for` toegevoegd aan de Reads-tabel
  (`BridgeRequestHandler.kt:113` → `dashboardService.changelogFor(name)`, parameter `name`).
  Bewust geen klassenaam voor de delegate genoemd: de injectie is `DashboardQueries`, niet
  `FactoryDashboardService` zoals in oudere rijen.

Specs bijgewerkt: `docs/factory/technical-spec.md` (TLS-zin + samenvattingsbron) en
`docs/factory/functional-spec.md` (terugval in de deploy-melding) — beide beschreven de codebase
niet meer correct; dat corrigeren ís deze story.

Bewijs (22-08-2026):
- `mvn -B --no-transfer-progress verify` vanaf de repo-root: BUILD SUCCESS, exitcode 0,
  0 failures / 0 errors (softwarefactory 04:43 min, totaal 05:20 min).
- `tools/audit-documentation`: `documentation-audit/v1: PASS`, exitcode 0.
- `grep -rn 'deploy-summary\|deploySummaryFor\|deploySummaryFrom' docs/
  factory-common/src/main/resources/docs-skeleton/` → 0 treffers buiten `docs/stories/`.
- `grep -rn 'insecureEdgeTerminationPolicy: Redirect' docs/` → 0 treffers buiten `docs/stories/`.
- `grep -rn 'summary-finished' docs/ .../docs-skeleton/` → 0 treffers buiten `docs/stories/`.
- `diff` van de twee `summarizer.md`-bestanden: leeg.
- `git status --porcelain`: uitsluitend `.md` onder `docs/` en de docs-skeleton, plus dit worklog.
  Geen `.kt`, `.dart`, `.yaml`, `.sql` of `.sh`.

Niet gedaan (bewust buiten scope): de endpoint-tellingen in `docs/technical/README.md` (r10 "39")
en `docs/technical/endpoints.md` (r3 "6"), de ontbrekende bridge-operatie `projects.recentCommits`,
en `docs/stories/**` (historische verslagen houden hun oude markers).
