# SF-1822 - Worklog

Story-context bij eerste pickup:
SF_ALLOWED_EMAILS verplicht maken in docker-compose

Vervang in docker/docker-compose.yml (service softwarefactory-dashboard-backend, environment-blok, regel 29) `SF_ALLOWED_EMAILS: ${SF_ALLOWED_EMAILS:-robbert@vdzon.com}` door exact `SF_ALLOWED_EMAILS: ${SF_ALLOWED_EMAILS:?set in secrets.env}`, zodat de vorm en foutboodschap identiek zijn aan SF_GOOGLE_CLIENT_ID (r28), SF_DASHBOARD_REMEMBER_SECRET (r30) en SF_BRIDGE_TOKEN (r33). Controleer daarna dat de string `robbert@vdzon.com` niet meer in docker/docker-compose.yml voorkomt. Loop docs/factory/secrets-local.md (r46-51, r167), docs/installation.md (r28) en runbook.md (r96) na: die beschrijven SF_ALLOWED_EMAILS al als verplicht zonder default - pas ze alleen aan als er alsnog een tegenstrijdige zin blijkt te staan. Wijzig geen Kotlin-bronnen of tests, en laat deploy/secrets-cluster.env.example, het Kubernetes-/sealed-secret-pad en docker/smoke-local-quickstart.sh ongemoeid. Verifieer waar mogelijk met `docker compose -f docker/docker-compose.yml config`: zonder gezette (of met lege) SF_ALLOWED_EMAILS moet het falen met een melding die de variabelenaam noemt; met een gezette niet-lege waarde moet het slagen en die waarde in de uitvoer bevatten. Is docker compose niet beschikbaar in de omgeving, meld dat dan expliciet in het worklog in plaats van het stil over te slaan. Draai de bestaande buildketen (tools/verify-repository) en doe een eigen review tegen de acceptance criteria. Werk docs/stories/worklog/SF-1822-worklog.md bij met wat je gedaan en geverifieerd hebt.

In eigen woorden: docker-compose vulde stilletjes een persoonlijk e-mailadres in als
allowlist voor het dashboard, waardoor de backend ook opstartte als je de allowlist
vergat te zetten. Die stille default gaat eruit; ontbreken of leeg zijn moet meteen
falen, net als bij de drie buurvariabelen.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes (compose-regel omgezet naar de `:?`-vorm)
[x]: documentatie (secrets-local.md, installation.md, runbook.md) nagelopen
[x]: verificatie met `docker compose config` (unset / leeg / gezet / --env-file)
[x]: run relevant tests (volledig vangnet uit docs/factory/development.md)
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `docker/docker-compose.yml` r29: `SF_ALLOWED_EMAILS: ${SF_ALLOWED_EMAILS:-robbert@vdzon.com}`
  vervangen door `SF_ALLOWED_EMAILS: ${SF_ALLOWED_EMAILS:?set in secrets.env}` — exact dezelfde
  vorm en foutboodschap als SF_GOOGLE_CLIENT_ID, SF_DASHBOARD_REMEMBER_SECRET en SF_BRIDGE_TOKEN.
  Dit is de enige codewijziging; Kotlin-bronnen/tests, `deploy/secrets-cluster.env.example`,
  het Kubernetes-pad en `docker/smoke-local-quickstart.sh` zijn ongemoeid gelaten.
- `grep -rn "robbert@vdzon.com" docker/` geeft nu geen treffers meer (AC1). Buiten docker/ staat
  het adres nog in `deploy/secrets-cluster.env.example` — expliciet buiten scope, en daar een
  voorbeeldwaarde, geen effectieve default.
- Documentatie nagelopen (AC6), geen wijziging nodig: `docs/factory/secrets-local.md` (r46-51,
  r167) noemt SF_ALLOWED_EMAILS al verplicht zonder fallback inclusief de lege-lijst-fout,
  `docs/installation.md` (r28) geeft een neutraal voorbeeldadres in de in te vullen secrets, en
  `runbook.md` (r96) noemt "niet-lege allowlist; geen default meer sinds SF-1551". Geen
  tegenstrijdige zin gevonden.
- Geen nieuwe tests toegevoegd: er is geen testharnas voor compose-interpolatie en de fail-fast
  in `DashboardConfig`/`DashboardSecretsLoader` is al door bestaande unittests gedekt (SF-1551).
  In plaats daarvan is het gedrag machinaal geverifieerd met compose zelf (zie hieronder).

Verificatie (AC3/AC4) — de agent-container heeft geen docker-CLI, dus is de standalone
compose-binary v2.39.1 (docker/compose release, linux-aarch64) buiten de checkout in /tmp
gebruikt; `config` doet alleen parsing/interpolatie en heeft de daemon niet nodig.
Buurvariabelen (SF_GOOGLE_CLIENT_ID, SF_DASHBOARD_REMEMBER_SECRET, SF_BRIDGE_TOKEN) waren
telkens gezet, anders faalt config al op de eerste:

- SF_ALLOWED_EMAILS niet gezet: exit 1 met
  `error while interpolating services.softwarefactory-dashboard-backend.environment.SF_ALLOWED_EMAILS: required variable SF_ALLOWED_EMAILS is missing a value: set in secrets.env`
- SF_ALLOWED_EMAILS leeg gezet: zelfde foutmelding, exit 1.
- `SF_ALLOWED_EMAILS=dev@example.com`: exit 0 en de uitvoer bevat `SF_ALLOWED_EMAILS: dev@example.com`.
- De `--env-file`-route van `docker/smoke-local-quickstart.sh` nagebootst (zelfde env-file-inhoud,
  variabele NIET in de shell-omgeving): exit 0 met `SF_ALLOWED_EMAILS: smoke@example.com`, dus de
  smoke blijft werken.

Vangnet (AC5), alle stappen uit `tools/verify-repository` handmatig gedraaid, alle exit 0:

- `mvn -B --no-transfer-progress clean verify` (repo-root): BUILD SUCCESS in 05:26 min,
  0 failures / 0 errors over alle vijf modules.
- `./quality/run.sh`: `ok: true`, `new: []` — ratchet groen.
- `tools/generate-module-dependencies --check`: "Moduledependency-metadata en documentatie zijn actueel."
- `bash docker/test-prepare-mini-reactor.sh`: mini-reactor tests PASS.
- `dashboard-frontend`: `flutter pub get` (pubspec.lock ongewijzigd), `flutter analyze`
  ("No issues found!"), `flutter test` (114 tests groen).
- `tools/audit-documentation`: `documentation-audit/v1: PASS`.
- Niet gedraaid: `agent-image-build-stage` (`docker build --target build -f Dockerfile.agent .`) —
  de docker-CLI ontbreekt in de agent-container en dit commando staat in
  `.factory/verification.yaml` bewust als `agentRunnable: false`; CI verifieert het apart.

Specs in `docs/factory/`: geen aanpassing nodig — `secrets-local.md` beschreef SF_ALLOWED_EMAILS
al als verplicht zonder default, dus de specs weerspiegelen de codebase na deze wijziging correct.
