# Software Factory — topologie naar OpenShift

Status: toekomstontwerp, nog niet ingepland

Peildatum: 2026-09-10

Doelrepository: `softwarefactory`

Ingangseis: de overstap naar Agent Runtime v2 is afgerond en werkt aantoonbaar, zie
[stappenplan.md](stappenplan.md)

## Doel

De Software Factory draait vandaag in twee helften: een lokale orchestrator op de MacBook met de
database ernaast, en een dunne `dashboard-backend` op OpenShift die via een uitgaande WebSocket met
die lokale factory praat. Dit document beschrijft hoe die twee helften er één worden, op OpenShift,
zodat er niets van de Software Factory meer op een laptop hoeft te draaien.

Dit is bewust **losgetrokken** van de runtimewissel. Het is niet nodig om die af te ronden, en het
is het enige stuk met een databaseverhuizing en met werk buiten deze repository. Eerst zorgen we dat
de factory op de nieuwe agent runtime werkt; daarna pas dit.

## Waarom het na de runtimewissel logisch wordt

Zolang de factory zelf agentcontainers start en story-workspaces met checkouts op schijf bijhoudt,
is er een technische reden om lokaal te draaien. Na de overstap is die reden weg: het repositorywerk
gebeurt in tijdelijke checkouts van de Agent Runtime-worker, en die worker mag gewoon op de MacBook
blijven draaien. Wat er in de factory overblijft is een gewone Spring-applicatie met een database.

## Huidige situatie

- De lokale `softwarefactory`-module bevat tracker, pipeline, orchestrator, audits, Telegram,
  kennis, maintenance en de dashboard-services.
- Postgres draait lokaal via `docker/docker-compose.yml`, met een lokaal volume
  `software-factory-postgres-data`.
- Op OpenShift staan alleen `softwarefactory-dashboard-backend` en
  `softwarefactory-dashboard-frontend` (`deploy/base/`). De backend is dun: 12 bestanden, 2.087
  regels, met authenticatie via Google-id-token, health, en de bridge.
- De bridge bestaat uit `BridgeHub`, `BridgeApiController` en `BridgeWebSocketConfig` in de backend,
  en `BridgeClient` plus `BridgeRequestHandler` (723 regels) aan de factorykant.
- `ProductFactoryIntegrationApi` en `ProductFactoryIntegrationV2Api` in de backend sturen hun
  verzoeken over diezelfde bridge door naar de lokale factory.
- De frontend praat met `dashboard.vdzonsoftware.nl`.

## Gewenste eindsituatie

- Eén deployable op OpenShift die zowel de publieke API's als de factorylogica bevat.
- Postgres op de cluster, met de afgeronde stories uit de lokale database.
- Geen WebSocketbridge meer, in geen van beide richtingen.
- `software-factory-backend` en `software-factory-frontend` als namen van module, artifact, image,
  Deployment en Service.
- `softwarefactory.vdzonsoftware.nl` als primaire host, met `dashboard.vdzonsoftware.nl` tijdelijk
  als redirect of alias.
- De Agent Runtime-worker blijft op de MacBook; dat valt niet onder deze verhuizing.

## Randvoorwaarden die aandacht vragen

Deze zaken werken vandaag omdat de factory op een ingerichte laptop draait. Op de cluster moeten ze
expliciet geregeld worden:

- **`gh` CLI en een GitHub-token.** `GitHubCliClient` en `AuditJobsReader` roepen `gh` aan. Het image
  moet `gh` bevatten en het token moet als secret worden aangeboden; er is geen interactieve
  `gh auth login` op een pod.
- **`kubectl` of de OpenShift-API.** `KubectlDeploymentStatusProbe` en `PreviewEnvironmentCleaner`
  gebruiken `kubectl`. Vanuit de cluster gaat dat via een ServiceAccount met de juiste rechten in de
  betrokken namespaces, niet via een kubeconfig van de laptop.
- **`projects.yaml` en de operationele secrets.** Nu lokale bestanden; op de cluster worden dat een
  ConfigMap en Sealed Secrets, in dezelfde vorm als de bestaande `deploy/base/sealed-secret-*`.
- **Uitgaande verbindingen.** Telegram, GitHub en de Agent Runtime-server moeten vanaf de pod
  bereikbaar zijn.
- **Tijdzone en schedulers.** Audits starten op een ingestelde tijd; controleer dat de pod dezelfde
  tijdzone hanteert als nu.

Het projectlokale, gitignored `secrets.env` van targetprojecten blijft buiten beeld: dat werd al
niet door de factory gelezen en verandert hier niet.

## Stappen

| Stap | Naam |
|---:|---|
| 1 | Database op de cluster |
| 2 | Factorylogica in de backend-deployable |
| 3 | Bridge verwijderen |
| 4 | Datamigratie |
| 5 | Rename |
| 6 | Host en toegang |
| 7 | Oplevering |

### Stap 1 — Database op de cluster

1. Richt Postgres in de namespace `software-factory` in, met persistent volume en back-up.
2. Voeg de connectiegegevens als Sealed Secret toe aan `deploy/base`.
3. Laat de Flyway-migraties vanaf leeg op die database slagen.
4. Houd de lokale database voorlopig intact als bron voor stap 4.

**Klaar wanneer:** een lege cluster-database volledig gemigreerd is en de applicatie er lokaal tegen
kan opstarten.

### Stap 2 — Factorylogica in de backend-deployable

1. Neem de `softwarefactory`-module op in de deployable van `dashboard-backend`, of voeg de
   factory-modules toe aan die composition root.
2. Zet configuratie, schedulers, `projects.yaml` en secrets om naar ConfigMap en Sealed Secrets.
3. Voeg `gh` en `kubectl` toe aan het image en richt de ServiceAccount en rechten in.
4. Laat de applicatie op de cluster starten, migreren en health rapporteren, met de schedulers nog
   uit.
5. Zet daarna de schedulers aan en controleer dat audits, retentie en reconciliatie draaien.

**Klaar wanneer:** de volledige factory op de cluster draait tegen de cluster-database, en een story
handmatig gestart kan worden.

### Stap 3 — Bridge verwijderen

1. Laat `ProductFactoryIntegrationApi` en `ProductFactoryIntegrationV2Api` rechtstreeks de
   factory-services aanroepen in plaats van de bridge.
2. Verwijder `BridgeHub`, `BridgeApiController` en `BridgeWebSocketConfig` uit de backend.
3. Verwijder `BridgeClient` en `BridgeRequestHandler` uit de factory.
4. Verwijder de bridgeconfiguratie, de reconnect- en offline-afhandeling en de bijbehorende
   statusweergave in het dashboard.
5. Controleer dat alle publieke API-paden ongewijzigd blijven.

**Klaar wanneer:** er geen WebSocketverbinding meer bestaat tussen twee Software Factory-onderdelen
en alle bestaande endpoints hetzelfde antwoorden.

### Stap 4 — Datamigratie

1. Exporteer uit de lokale database de afgeronde stories: publieke key, titel, status,
   oplevercommit en samenvatting. De rest hoeft niet mee.
2. Draai de import op een kopie van de cluster-database en controleer de aantallen en een steekproef.
3. Maak een back-up van beide databases.
4. Draai de import op de cluster-database.
5. Controleer dat bestaande deeplinks naar die stories blijven werken.

**Klaar wanneer:** de afgeronde stories zichtbaar zijn in het dashboard op de cluster, met een
bewezen back-up ervoor.

### Stap 5 — Rename

1. Hernoem `dashboard-backend` naar `software-factory-backend`: module, artifact, image, Deployment
   en Service.
2. Hernoem `dashboard-frontend` naar `software-factory-frontend`, inclusief image, Deployment,
   Service en de documentatie.
3. Werk de workflows `dashboard-backend-image.yml` en `dashboard-frontend-image.yml` bij, plus de
   image-bump-automatisering.
4. Werk `deploy/base` en de overlays bij.
5. Behoud alle API-paden en deeplinks.

**Klaar wanneer:** de pipeline groen is, de nieuwe images gebouwd worden en de deployment onder de
nieuwe naam gezond draait.

### Stap 6 — Host en toegang

1. Voeg `softwarefactory.vdzonsoftware.nl` toe als OpenShift-route met certificaat; Robbert regelt
   zo nodig de DNS in Cloudflare.
2. Pas de SSO-redirectconfiguratie aan zodat inloggen op de nieuwe host werkt.
3. Zet `DASHBOARD_API_BASE_URL` in de frontend-buildworkflow om naar de nieuwe host.
4. Houd `dashboard.vdzonsoftware.nl` gedurende een afgesproken periode als redirect of alias, met
   behoud van pad en querystring.
5. Test inloggen, deeplinks en bookmarks op beide hosts.

**Klaar wanneer:** de nieuwe host volledig werkt en de oude host netjes doorverwijst.

### Stap 7 — Oplevering

1. Draai één echte story end-to-end vanaf de cluster, inclusief merge en deploy.
2. Draai een audit en controleer de Telegram-meldingen.
3. Controleer de Product Factory-integratie vanaf de cluster.
4. Zet de lokale factory en de lokale Postgres uit; bewaar de back-up.
5. Werk `README.md`, `runbook.md`, `docs/installation.md` en de technische documentatie bij: een
   ontwikkellaptop is geen runtimeonderdeel meer van de Software Factory.

**Klaar wanneer:** er geen Software Factory-proces meer op een laptop draait, alleen de Agent
Runtime-worker daar nog staat, en één story, één audit en de Product Factory-integratie aantoonbaar
vanaf de cluster werken.

## Risico's

| Risico | Beheersing |
|---|---|
| `gh` of `kubectl` werkt niet vanaf een pod | Stap 2 richt image, token en ServiceAccount expliciet in en test beide vóór de schedulers aangaan. |
| Deploys of previews falen door andere rechten | Controleer per targetnamespace welke rechten de ServiceAccount nodig heeft; test met een echte deploy naar een testtarget. |
| Datamigratie gaat mis | Alleen afgeronde stories, eerst op een kopie, back-up van beide kanten vooraf. |
| Rename breekt bookmarks of API-clients | API-paden blijven stabiel; de oude host blijft tijdelijk als redirect. |
| Inloggen breekt door de nieuwe host | SSO-redirect wordt in stap 6 aangepast en op beide hosts getest voordat de oude host verdwijnt. |
| Schedulers draaien dubbel tijdens de overgang | De lokale factory gaat uit voordat de cluster-schedulers aangaan; nooit beide tegelijk. |

## Buiten scope

- De agent runtime of zijn worker verplaatsen; die blijft op de MacBook.
- Het domeinmodel of de modulegrenzen wijzigen.
- Een aparte deployable per capability.
- Andere data dan afgeronde stories migreren.
