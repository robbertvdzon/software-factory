# SF-1068 - projects

## Story

projects

<!-- refined-by-factory -->

## Scope

Regressiebug: op het Projects-scherm in de dashboard-frontend wordt voor **alle** projecten een vraagteken en de badge "Geen productieversie beschikbaar" getoond, ook voor projecten die wél een `RestRestart`-deploy-configuratie hebben en normaal een geldige `Live: <branch> · <commitShort> (<commitDate>)`-regel + sync-badge (In sync met main / Loopt achter op main) zouden moeten laten zien.

De feature zelf is al gebouwd en gedocumenteerd (SF-890, `docs/factory/ux/screens/projects.md`): `GET /api/v1/projects` → `DashboardQueryService.projectsOverview()` haalt per project met `DeployConfig.RestRestart` de `prdVersion` op via `fetchPrdVersion`/`deployClient.fetchVersionBody(versionUrl)` en bepaalt `buildStatus.syncStatus` (`IN_SYNC`/`OUT_OF_SYNC`/`UNAVAILABLE`) via `buildStatusFor`. `UNAVAILABLE` (→ "Geen productieversie beschikbaar") hoort alleen te verschijnen voor projecten zónder deploy-config, of wanneer `prdVersion` of de laatste main-build-sha écht ontbreekt.

Scope van deze story:
- Diagnosticeer waarom dit voor **alle** deployments faalt (regressie t.o.v. eerder werkende staat) — kandidaat-oorzaken die de developer moet uitsluiten: `deployConfigFor`/config-lookup levert geen `RestRestart` meer op (bv. gewijzigde project-config-bron of naam-matching), `deployClient.fetchVersionBody` faalt structureel (netwerk/auth/timeout/URL), `parsePrdVersionJson` faalt op een gewijzigd `/version`-responseformaat, of `hasDeployConfig`/`buildStatusFor`-logica regressie.
- Fix de onderliggende oorzaak zodat projecten met een geldige deploy-config weer hun echte productieversie en sync-status tonen.
- Geen scope-wijziging aan het bestaande gedrag/de bestaande UI-states zelf (die staan al vast in `docs/factory/ux/screens/projects.md`); dit is een bugfix, geen nieuwe feature.

## Acceptance criteria

- Voor elk project met een geldige `deploy.type: rest-restart`-configuratie en een bereikbaar `/version`-endpoint toont het Projects-scherm weer de regel `Live: <branch> · <commitShort> (<commitDate>)` en een sync-badge (`In sync met main` of `Loopt achter op main`), niet langer een vraagteken/"Geen productieversie beschikbaar".
- "Geen productieversie beschikbaar" blijft correct getoond voor projecten zónder deploy-config, en voor projecten waar de vergelijking echt niet gemaakt kan worden (geen `prdVersion` op te halen, of geen bekende main-build-sha) — dit gedrag mag niet wijzigen.
- Root cause is geïdentificeerd en vastgelegd (bv. in de worklog/PR-beschrijving): welke laag faalde (config-lookup, HTTP-fetch, JSON-parsing, of sync-berekening) en waarom.
- Bestaande tests (`FactoryDashboardServiceTest`/`DashboardQueryService`-tests, `projects_screen_test.dart`) blijven slagen; een regressietest wordt toegevoegd die het gevonden defect afdekt (reproduceert het faalscenario en verifieert de fix).
- Geen wijziging aan het gedrag voor overige Projects-schermonderdelen (story-tellers, cost, actieve agents, build-status-badges, force-deploy-knop).

## Aannames

- De onderliggende deploy-configuratie (projects.yaml-equivalent) bestaat en is voor minstens één project correct ingevuld met `deploy.type: rest-restart` — het probleem is een regressie in gedrag, geen ontbrekende configuratie an sich (indien blijkt dat de config-bron zelf leeg/verwijderd is, is dat een omgevingsprobleem buiten codescope en moet de developer dat expliciet als blokkade melden i.p.v. code te patchen).
- Alle projecten zijn getroffen (niet slechts één), wat wijst op een gedeelde oorzaak (bv. `deployClient`, config-parsing, of de sync-berekening) in plaats van een per-project misconfiguratie.
- Geen wijziging is nodig aan de API-contractvelden (`prdVersion`, `hasDeployConfig`, `buildStatus`) — de fix zit in waarom deze velden momenteel altijd `null`/`UNAVAILABLE` zijn, niet in het datamodel zelf.

## Eindsamenvatting

Eindsamenvatting voor SF-1068 (Projects-productieversie-regressie) opgesteld en vastgelegd.

**Kernpunten:**
- **Root cause**: geen enkele individuele laag (config-lookup/HTTP-fetch/JSON-parsing/sync-berekening) was defect — het probleem was dat drie parallelle taken in `DashboardQueryService.projectsOverview()` (prdVersion-fetch, GitHub Actions-fetch, live-component kubectl-calls) zonder eigen `Executor` allemaal op het gedeelde `ForkJoinPool.commonPool()` draaiden. De blocking kubectl-calls verdrongen de prdVersion-fetch, die daardoor de 3s-timeout overschreed — voor alle projecten tegelijk.
- **Fix**: een dedicated cached `ExecutorService` toegevoegd aan `DashboardQueryService`, expliciet meegegeven aan alle drie de fan-out-calls. Geen wijziging aan API-contractvelden of andere schermonderdelen.
- **Getest**: nieuwe regressietest die het commonPool bewust verzadigt (faalt zonder fix, slaagt met fix), happy-path- en UNAVAILABLE-zonder-config-tests, volledig `mvn verify` groen (43 tests, 0 failures), diff-scope bevestigd als backend-only.
- **Bewust niet gedaan**: geen wijziging aan factory-docs/UX-spec, geen aanpak van `downloads()`/`builds()` (blijven op commonPool, buiten scope), geen browser/E2E-test (geen UI-wijziging, geen preview-omgeving).
