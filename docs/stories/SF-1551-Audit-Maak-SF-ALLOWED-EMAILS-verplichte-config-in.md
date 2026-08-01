# SF-1551 - [Audit] Maak SF_ALLOWED_EMAILS verplichte config in dashboard-backend

## Story

[Audit] Maak SF_ALLOWED_EMAILS verplichte config in dashboard-backend

<!-- refined-by-factory -->

## Samenvatting

Het dashboard kan nu opstarten zonder dat iemand heeft ingesteld wie er mag
inloggen. Gebeurt dat, dan valt de service terug op één e-mailadres dat in de
broncode staat. Dat is niet wat we hebben afgesproken: wie toegang heeft, hoort
een bewuste instelling te zijn.

Na deze wijziging weigert het dashboard te starten als de lijst met toegestane
e-mailadressen ontbreekt of leeg is, met een duidelijke foutmelding die de naam
van de instelling noemt. Voor bestaande omgevingen verandert er niets: die
hebben de lijst allemaal al ingesteld.

## Scope

Alleen deze twee bestanden:

- `dashboard-backend/src/main/kotlin/nl/vdzon/softwarefactory/dashboard/config/DashboardConfig.kt`
  - Laad `SF_ALLOWED_EMAILS` via `required(...)` in plaats van `optional(...)` (regel 51).
  - Verwijder de constante `DEFAULT_ALLOWED_EMAIL` en het bijbehorende
    `private companion object` als dat daarmee leeg achterblijft (regels 88-90).
  - Laat het laden ook falen wanneer de waarde wél gezet is maar na `parseAllowedEmails`
    een lege set oplevert (bijv. `","` of `" , "`).
- `dashboard-backend/src/test/kotlin/nl/vdzon/softwarefactory/dashboard/config/DashboardSecretsLoaderTest.kt`
  - Vervang de test `defaults the allowlist to robbert when omitted` (regels 27-35) door
    fail-tests, in dezelfde vorm als de bestaande fail-tests op regels 47-67.

Buiten scope: documentatie, deploy-/compose-/secretsbestanden, `AuthService` en de
overige dashboard-backend-tests.

## Acceptance criteria

1. `DashboardSecretsLoader.load()` faalt wanneer `SF_ALLOWED_EMAILS` ontbreekt in zowel
   het secrets-bestand als de environment; de foutmelding bevat de string
   `SF_ALLOWED_EMAILS`.
2. `load()` faalt eveneens wanneer `SF_ALLOWED_EMAILS` wel gezet is maar na parsing geen
   enkel adres oplevert (bijvoorbeeld `","`, `" , "`); ook die foutmelding bevat
   `SF_ALLOWED_EMAILS`.
3. Beide gevallen gooien hetzelfde exceptietype als de bestaande verplichte keys
   (`IllegalStateException`, via `error(...)`), zodat de nieuwe test dezelfde
   `assertFailsWith<IllegalStateException>`-vorm kan gebruiken als de tests op regels 47-67.
4. De constante `DEFAULT_ALLOWED_EMAIL` en het e-mailadres `robbert@vdzon.com` komen niet
   meer voor in `DashboardConfig.kt`.
5. Bij een geldige waarde blijft het gedrag exact gelijk: comma-gescheiden splitsen,
   trimmen, lowercasen, dedupliceren. De tests
   `loads dashboard secrets from environment` en
   `parses a comma-separated allowlist and normalises whitespace and casing` blijven
   ongewijzigd en groen.
6. De tests `startup fails when google client id is omitted` en
   `startup fails when remember secret is omitted` blijven ongewijzigd en groen — dus ook
   zonder `SF_ALLOWED_EMAILS` in hun environment moet de melding nog steeds over die
   andere key gaan (volgorde: `SF_DASHBOARD_REMEMBER_SECRET`, `SF_GOOGLE_CLIENT_ID`,
   daarna `SF_ALLOWED_EMAILS`).
7. `mvn verify` vanaf de repo-root is groen (0 failures, 0 errors).
8. Er zijn geen andere bestanden gewijzigd dan de twee genoemde (plus het
   `docs/stories/worklog/SF-1551-worklog.md`-worklog, conform de standaardwerkwijze).

## Aannames

- De foutmelding voor beide faalgevallen volgt de bestaande formulering
  `Missing required dashboard configuration: SF_ALLOWED_EMAILS`; voor het lege-set-geval mag
  de developer een eigen, beschrijvende variant kiezen zolang de key erin voorkomt en het
  exceptietype `IllegalStateException` blijft.
- Geen enkel bestaand uitrolpad breekt: `docker/docker-compose.yml:29`
  (`${SF_ALLOWED_EMAILS:-robbert@vdzon.com}`), `deploy/base/sealed-secret-dashboard.yaml`,
  `deploy/secrets-cluster.env.example:14`, `secrets.env.example:39` en
  `docker/smoke-local-quickstart.sh:29` zetten de variabele al; er is geverifieerd dat er
  geen Spring-context-test in `dashboard-backend` bestaat die zonder deze env-var opstart.
- Alleen `DashboardConfig.kt` gebruikt `DEFAULT_ALLOWED_EMAIL`; verwijderen raakt geen
  andere code (geverifieerd via repo-brede grep).
- Documentatie blijft ongewijzigd, conform de opdracht. Opgemerkt maar bewust buiten scope:
  `docs/factory/secrets-local.md:46-47` beschrijft de allowlist nog met
  "(default `robbert@vdzon.com`)" en wordt door deze wijziging feitelijk onjuist — dit is
  een kandidaat voor een aparte opvolgstory.
</proposed-description:end -->

Let op: de sluitmarker hierboven staat verkeerd; hieronder de correcte afsluiting.

## Eindsamenvatting

## Eindsamenvatting SF-1551 — SF_ALLOWED_EMAILS verplicht maken in dashboard-backend

### Wat is gebouwd
Het dashboard kon tot nu toe opstarten zonder ingestelde allowlist en viel dan terug op één hardgecodeerd e-mailadres (`robbert@vdzon.com`) in de broncode. Vanaf nu is "wie mag inloggen" een bewuste instelling:

- `SF_ALLOWED_EMAILS` wordt geladen via `required(...)` in plaats van `optional(...)` met fallback. Ontbreekt de waarde in zowel het secrets-bestand als de environment, dan start de backend niet en meldt hij `Missing required dashboard configuration: SF_ALLOWED_EMAILS`.
- Ook een gezette-maar-lege waarde (`","`, `" , "`) wordt geweigerd, met de melding `Empty dashboard configuration: SF_ALLOWED_EMAILS contains no e-mail addresses`. Dat geval passeert `required(...)` namelijk wél (de string is niet leeg) en wordt pas ná het parsen gevangen.
- De constante `DEFAULT_ALLOWED_EMAIL` en het daardoor lege `private companion object` zijn verwijderd; het e-mailadres komt niet meer voor in `DashboardConfig.kt`.

### Gemaakte keuzes
- **Zelfde faalgedrag als de andere verplichte keys**: beide gevallen gooien `IllegalStateException` via `error(...)`, zodat de foutafhandeling en de teststijl gelijk blijven aan `SF_DASHBOARD_REMEMBER_SECRET` en `SF_GOOGLE_CLIENT_ID`.
- **Aanroepvolgorde bewust intact gelaten** (`SF_DASHBOARD_REMEMBER_SECRET` → `SF_GOOGLE_CLIENT_ID` → `SF_ALLOWED_EMAILS`), zodat bestaande fail-tests nog steeds over hún eigen key melden.
- **Gedrag bij een geldige waarde is ongewijzigd**: comma-splitsen, trimmen, lowercasen, dedupliceren.

### Wat is getest
- Volledige `mvn verify` vanaf de repo-root: **BUILD SUCCESS**, 938 tests over alle modules, 0 failures / 0 errors / 0 skipped. Geen flakes.
- De weggevallen default-test is vervangen door twee fail-tests (ontbrekend, en gezet-maar-leeg); de vier bestaande loader-tests zijn ongewijzigd en groen.
- **Gedragstest op de echt gebouwde jar**, gedraaid in een lege werkdir met `env -i` zodat geen host-secrets meeliftten: zonder de variabele → exit 1 met de juiste melding; met `","` en `" , "` → exit 1; met een geldige waarde start de applicatie normaal op. Ook geverifieerd dat de remember-secret in geen van de logs terechtkomt.
- **Uitrolpaden gecontroleerd**: `docker/docker-compose.yml`, `deploy/base/sealed-secret-dashboard.yaml`, `deploy/secrets-cluster.env.example`, `secrets.env.example` en `docker/smoke-local-quickstart.sh` zetten de variabele alle vijf — geen bestaand deploy-pad breekt door deze wijziging.

### Bewust niet gedaan
- Documentatie, docker-/deploy-/secretsbestanden en `AuthService` bleven buiten scope. De diff raakt precies twee bronbestanden plus het worklog.
- **Aandachtspunt voor de PO**: `docs/factory/secrets-local.md` beschrijft de allowlist nog als "(default `robbert@vdzon.com`)" en is door deze wijziging feitelijk onjuist geworden. Daarnaast houdt `docker/docker-compose.yml` met `${SF_ALLOWED_EMAILS:-robbert@vdzon.com}` nog een fallback in het compose-pad. Het eerste punt is werk voor de documentation-subtaak SF-1661; het tweede is een kandidaat voor een opvolgstory als de PO die fallback ook weg wil.
