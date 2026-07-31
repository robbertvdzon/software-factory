# SF-1550 - [Audit] Image-build-workflows alleen laten starten op een push uit deze repo

## Story

[Audit] Image-build-workflows alleen laten starten op een push uit deze repo

<!-- refined-by-factory -->

## Samenvatting

De twee workflows die de dashboard-images bouwen, starten nu zodra de verificatie-workflow slaagt op een branch die "main" heet. Ze kijken niet of die branch écht uit deze repo komt. Omdat de repo openbaar is, kan iemand met een pull request vanuit een eigen kopie van de repo die verificatie zelf laten slagen en zo een build op eigen code afdwingen — met toegang tot de bouwrechten en de Android-ondertekensleutel.

Deze story sluit dat gat door de startvoorwaarde van drie build-jobs aan te scherpen: bouwen mag alleen nog na een echte push binnen deze repo zelf.

Voor de dagelijkse gang van zaken verandert er niets: een push naar main bouwt beide images en maakt de gebruikelijke versie-bump aan.

## Scope

In scope — uitsluitend de `workflow_run`-tak van drie `if`-condities in twee bestanden:

- `.github/workflows/dashboard-backend-image.yml`, job `build` (regel 25-27)
- `.github/workflows/dashboard-frontend-image.yml`, job `build` (regel 28-30)
- `.github/workflows/dashboard-frontend-image.yml`, job `build-apk` (regel 70-72)

Aan elk van die drie condities worden, náást de bestaande `github.event.workflow_run.conclusion == 'success'` en `github.event.workflow_run.head_branch == 'main'`, deze twee eisen toegevoegd:

```
github.event.workflow_run.event == 'push' &&
github.event.workflow_run.head_repository.full_name == github.repository
```

Buiten scope:

- De `github.event_name == 'workflow_dispatch'`-tak van dezelfde condities blijft ongewijzigd.
- De jobs `bump-manifests` in beide bestanden: die hebben geen eigen `if` en worden al indirect afgeschermd via `needs: [build]`.
- `.github/workflows/verify.yml`, `.github/scripts/bump-images.sh`, de `permissions`-blokken, de checkout-`ref`s en alle overige stappen.
- Geen wijzigingen aan applicatiecode, tests of documentatie.

## Acceptance criteria

1. In `dashboard-backend-image.yml` bevat de `if` van job `build` alle vier de eisen in de `workflow_run`-tak: `conclusion == 'success'`, `head_branch == 'main'`, `event == 'push'` en `head_repository.full_name == github.repository`.
2. Idem voor de `if` van job `build` in `dashboard-frontend-image.yml`.
3. Idem voor de `if` van job `build-apk` in `dashboard-frontend-image.yml`.
4. De drie condities behouden hun bestaande structuur: `github.event_name == 'workflow_dispatch' || (<alle vier de workflow_run-eisen>)`, zodat handmatig dispatchen van de image-workflow zelf blijft werken.
5. Beide bestanden blijven geldige YAML en geldige GitHub Actions-workflows; buiten de drie `if`-blokken is de diff leeg.
6. `bash tools/verify-repository` (of de CI-equivalent) slaagt onveranderd.
7. Na merge naar `main`: de eerstvolgende push naar `main` laat `Repository verification` slagen, waarna beide image-workflows draaien, beide images gepusht worden, de APK-release aangemaakt wordt en de manifest-bump-PR's verschijnen. Deze verificatie gebeurt na de merge en is expliciet onderdeel van het opleveren van deze story.

## Aannames

- Er is geen legitiem scenario waarin een image gebouwd moet worden op basis van een `workflow_run` die niet uit deze repo komt; forks bouwen geen productie-images.
- `verify.yml` triggert op `pull_request`, `push: branches: [main]` en `workflow_dispatch`. Na deze fix passeert alleen de push-variant de gate. Een handmatige dispatch van `verify.yml` start dus niet langer een image-build; de bedoelde handmatige route blijft de eigen `workflow_dispatch`-trigger van de image-workflows zelf. Dit wordt als gewenst gedrag beschouwd.
- Bij `workflow_dispatch` op de image-workflow zelf zijn de `github.event.workflow_run.*`-velden leeg; doordat die vergelijkingen achter de `||`-tak staan, blijft dat pad onaangetast.
- De genoemde regelnummers zijn geverifieerd tegen de huidige `main`; wijkt het bestand bij implementatie af, dan is de job-naam (`build`, `build-apk`) leidend boven het regelnummer.
- Dit is een pure beveiligingsfix zonder functionele gedragswijziging voor de normale flow; er worden geen nieuwe geautomatiseerde tests toegevoegd, omdat de repo geen testharnas voor workflow-condities kent.

## Eindsamenvatting

## Eindsamenvatting SF-1550 — Image-build-workflows alleen laten starten op een push uit deze repo

### Wat er gebouwd is
Een pure beveiligingsfix in de CI-configuratie. De twee workflows die de dashboard-images bouwen, startten tot nu toe zodra de verificatie-workflow slaagde op een branch met de naam `main` — zonder te controleren of die branch écht uit deze (openbare) repo kwam. Daarmee kon iemand via een pull request vanuit een eigen fork een build op eigen code afdwingen, mét toegang tot de GHCR-pushrechten en de Android-ondertekensleutel.

De `workflow_run`-tak van drie `if`-condities is aangescherpt met twee extra eisen (`event == 'push'` en `head_repository.full_name == github.repository`):

- `.github/workflows/dashboard-backend-image.yml` — job `build`
- `.github/workflows/dashboard-frontend-image.yml` — jobs `build` en `build-apk`

Voor de dagelijkse gang van zaken verandert er niets: een push naar `main` bouwt beide images, maakt de APK-release en de manifest-bump-PR's aan.

### Gemaakte keuzes
- **Structuur bewust behouden** als `github.event_name == 'workflow_dispatch' || (<vier workflow_run-eisen>)`, zodat handmatig dispatchen van de image-workflows zelf blijft werken.
- **Handmatige dispatch van `verify.yml` start geen image-build meer.** Bewuste consequentie: de bedoelde handmatige route is de eigen `workflow_dispatch`-trigger van de image-workflows.
- **`bump-manifests` niet aangeraakt** — die jobs hebben geen eigen `if` en worden al afgeschermd via `needs: [build]`.
- **Eén boyscout-wijziging buiten scope:** `docs/technical/module-dependencies.md` is opnieuw gegenereerd. Deze gegenereerde doc stond al rood op `main` (een eerdere story voegde `support` toe zonder de doc bij te werken) en de regeneratie was nodig om de drift-gate groen te krijgen. Review en test hebben dit expliciet geaccordeerd.

### Wat getest is
- Beide workflows met een echte YAML-parser (js-yaml en SnakeYAML) geladen: alle drie de condities vouwen tot precies één geldige expressie met alle vier de eisen; de dispatch-tak is ongewijzigd.
- Waarheidstabel over 8 scenario's: push naar `main` in eigen repo → build; fork-PR met branchnaam `main` (de aanval), push in een fork, eigen PR, dispatch van `verify.yml`, gefaalde verify en feature-branch → allemaal geblokkeerd; dispatch van de image-workflow zelf → build.
- Vangnet: `repository-documentation-audit` PASS, `repository-maven-verify` BUILD SUCCESS, Flutter analyze/test groen (106 tests), module-dependency-drift groen na regeneratie.

### Bewust niet gedaan
- **Geen geautomatiseerde tests toegevoegd** — de repo kent geen testharnas voor workflow-condities; in plaats daarvan is het gefoldede YAML-resultaat via parsers geverifieerd.
- **Geen applicatiecode, `verify.yml`, permissions-blokken, checkout-refs of `bump-images.sh` aangeraakt.**
- **Acceptatiecriterium 7 (verificatie ná merge naar `main`)** is per definitie niet in deze runs te toetsen en volgt bij de merge/deploy-subtaken.

### Openstaand punt voor de PO
`quality/run.sh` (detekt-ratchet) is structureel rood op `main` met **21 blocking findings** in o.a. `AgentPromptContracts.kt`, `AgentCli.kt`, `ProjectConfiguration.kt`, `AuditGatewayAdapter.kt`, `AuditScheduler.kt`, `OrchestratorService.kt` en `DashboardQueryService.kt`. Dit is bestaande schuld — deze story bevat nul Kotlin-regels — en u heeft akkoord gegeven om er hier niets aan te doen. Advies van de reviewer: maak een aparte opruimstory met de keuze *baseline regenereren* versus *refactoren*; zolang die openstaat, zien volgende stories een misleidend rood vangnet.
