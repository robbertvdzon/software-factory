# SF-1822 - Worklog

Story-context bij eerste pickup:
SF_ALLOWED_EMAILS strikt maken in docker-compose

Vervang in docker/docker-compose.yml in het environment-blok van de service softwarefactory-dashboard-backend de regel `SF_ALLOWED_EMAILS: ${SF_ALLOWED_EMAILS:-robbert@vdzon.com}` door `SF_ALLOWED_EMAILS: ${SF_ALLOWED_EMAILS:?set in secrets.env}`, exact dezelfde vorm en foutboodschap als SF_GOOGLE_CLIENT_ID, SF_DASHBOARD_REMEMBER_SECRET en SF_BRIDGE_TOKEN in datzelfde blok. Na de wijziging mag de string robbert@vdzon.com niet meer in docker/docker-compose.yml voorkomen. Wijzig geen Kotlin-bronnen of tests; er worden geen tests toegevoegd omdat compose-interpolatie geen testharnas heeft. Loop docs/factory/secrets-local.md, docs/installation.md en runbook.md na: die beschrijven SF_ALLOWED_EMAILS al als verplicht zonder default, dus pas ze alleen aan als er alsnog een tegenstrijdige zin staat. Raak docker/smoke-local-quickstart.sh, deploy/secrets-cluster.env.example en het Kubernetes-/sealed-secret-pad niet aan. Verifieer zelf met `docker compose -f docker/docker-compose.yml config`: zonder gezette (of met lege) SF_ALLOWED_EMAILS faalt het commando met een melding die SF_ALLOWED_EMAILS noemt, met een gezette niet-lege waarde slaagt het en bevat de uitvoer die waarde. Sluit af met een eigen review tegen de acceptance criteria en houd tools/verify-repository groen. Werk docs/stories/worklog/SF-1822-worklog.md bij met wat je gedaan hebt en welke verificatie je gedraaid hebt.

In eigen woorden: docker-compose vulde stilletjes een persoonlijk e-mailadres in
als er geen `SF_ALLOWED_EMAILS` gezet was, waardoor het dashboard lokaal ook
zonder bewust ingestelde toegangslijst startte. Die stille default gaat eruit, in
dezelfde `:?`-vorm als de drie buurgeheimen, zodat opstarten met een duidelijke
foutmelding stopt.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

## Done / rationale (SF-1837, developer)

- `docker/docker-compose.yml` r29: `${SF_ALLOWED_EMAILS:-robbert@vdzon.com}` ->
  `${SF_ALLOWED_EMAILS:?set in secrets.env}`. Enige codewijziging van deze
  subtaak; exact dezelfde vorm en foutboodschap als `SF_GOOGLE_CLIENT_ID`,
  `SF_DASHBOARD_REMEMBER_SECRET` en `SF_BRIDGE_TOKEN` in hetzelfde blok.
- Geen Kotlin-bronnen of tests gewijzigd: de fail-fast in `DashboardConfig.kt`
  (`required(...)` + lege-lijst-check, SF-1551) blijft ongewijzigd en er is geen
  testharnas voor compose-interpolatie. Ook `docker/smoke-local-quickstart.sh`,
  `deploy/secrets-cluster.env.example` en het Kubernetes-/sealed-secret-pad zijn
  niet aangeraakt.
- Documentatie nagelopen, geen wijziging nodig (dus ook geen aanpassing in
  `docs/factory/`): `docs/factory/secrets-local.md` r46-50 noemt
  `SF_ALLOWED_EMAILS` al **verplicht** zonder fallback inclusief de
  lege-lijst-fout, `runbook.md` r96 zegt "niet-lege allowlist; geen default meer
  sinds SF-1551" en `docs/installation.md` r28 zet de variabele gewoon in het
  in te vullen `secrets.env`-blok. Geen tegenstrijdige zin gevonden.

## Verificatie

Docker-CLI ontbrak in de sandbox terwijl `/var/run/docker.sock` wel werkte
(Docker Desktop 4.43, engine 28.3.0, arm64); tijdelijk buiten de checkout een
statische docker-client 28.3.0 + compose-plugin v2.39.1 uitgepakt (`/tmp`,
`~/.docker/cli-plugins`) om de gate inclusief image-build en `docker compose
config` echt te draaien.

`docker compose -f docker/docker-compose.yml config` (buurvariabelen gezet):

| geval | resultaat |
| --- | --- |
| `SF_ALLOWED_EMAILS` niet gezet | exit 1, `required variable SF_ALLOWED_EMAILS is missing a value: set in secrets.env` |
| `SF_ALLOWED_EMAILS=` (leeg) | exit 1, zelfde melding |
| `SF_ALLOWED_EMAILS=dev@example.com` | exit 0, uitvoer bevat `SF_ALLOWED_EMAILS: dev@example.com` |
| via `--env-file` zoals de smoke doet (`smoke@example.com`) | exit 0, uitvoer bevat `SF_ALLOWED_EMAILS: smoke@example.com` |

`grep -c 'robbert@vdzon.com' docker/docker-compose.yml` -> 0.

Volledig vangnet `tools/verify-repository`: **exit 0 (groen)**, alle stappen
inclusief `repository-maven-verify` (`mvn -B --no-transfer-progress clean
verify`, 0 failures / 0 errors), `repository-quality-ratchet`,
`repository-module-dependency-drift`, de drie flutter-stappen (114 tests, All
tests passed), `agent-mini-reactor-smoke` (PASS), `agent-image-build-stage`
(Successfully built) en `repository-documentation-audit` (PASS).

Eigen review tegen de acceptance criteria: AC1 t/m AC6 alle voldaan (AC4 via de
`--env-file`-variant van `config`, omdat alleen interpolatie verandert; AC5 via
een schone diff met alleen `docker/docker-compose.yml` + dit worklog).
