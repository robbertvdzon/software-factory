# SF-1561 - [Audit] Maak tools/check-composition-roots draaibaar en breng het composition-root-register weer in overeenstemming met de code

## Story

[Audit] Maak tools/check-composition-roots draaibaar en breng het composition-root-register weer in overeenstemming met de code

<!-- refined-by-factory -->

## Samenvatting

De factory houdt een lijstje bij van precies welke bestanden zelf met de buitenwereld praten (omgevingsvariabelen, processen starten, HTTP-verkeer). Dat lijstje hoort altijd exact te kloppen, en er is een controlescript dat dat bewaakt.

Twee dingen zijn stuk. Het controlescript gebruikt een zoekprogramma dat nergens in deze repo geïnstalleerd staat, dus het script kan helemaal niet draaien. En doordat niemand het al die tijd kon draaien, is het lijstje uit de pas gelopen met de code: er staan bestanden in die verplaatst of verdwenen zijn, en er ontbreken bestanden die er wel in horen.

Deze story repareert beide: het script krijgt een zoekprogramma dat er wél is, en het lijstje wordt weer in overeenstemming gebracht met de werkelijkheid. Er verandert geen productiecode.

## Scope

**In scope**

1. `tools/check-composition-roots` (regels 10-11): vervang de `rg -l`-aanroep door een gelijkwaardige `grep -rlE --include='*.kt'`-aanroep over exact dezelfde vier mappen (`softwarefactory/src/main`, `agentworker/src/main`, `dashboard-backend/src/main`, `factory-common/src/main`) en exact hetzelfde patroon `System\.getenv|ProcessBuilder|HttpClient\.new`. Voeg een korte `# Bewust grep i.p.v. rg`-toelichting toe naar analogie van `tools/audit-documentation:7-8`. Verder blijft het script ongewijzigd (dezelfde `sort`, `comm`-vergelijkingen, duplicaatcheck en PASS-regel).

2. `architecture/composition-root-boundaries.txt`: breng het register in overeenstemming met de 27 gevonden paden. Formaat `exact-path|runtime|capability|reason` en alfabetische volgorde blijven behouden; de headerregel blijft staan.
   - **Vijf paden corrigeren** (bestand verhuisd; `runtime`, `capability` en `reason` ongewijzigd overnemen):
     - `config/OrchestratorSettingsFactory.kt` → `config/services/OrchestratorSettingsFactory.kt`
     - `core/DeploymentStatusProbe.kt` → `core/contracts/DeploymentStatusProbe.kt`
     - `telegram/ClaudeAssistantClient.kt` → `telegram/clients/ClaudeAssistantClient.kt`
     - `telegram/TelegramClient.kt` → `telegram/clients/TelegramClient.kt`
     - `nightly/NightlyJobsReader.kt` → `audit/services/AuditJobsReader.kt` (het `nightly`-package bestaat niet meer; `AuditJobsReader.kt:153` heeft dezelfde `System.getenv("SF_GITHUB_TOKEN")`-achtervang, dus de reden-tekst verhuist mee, eventueel met `nightly` → `audit` in de formulering)
   - **Eén regel verwijderen**: `web/controllers/FactoryApiController.kt` — bevat geen van de drie patronen meer.
   - **Vijf regels toevoegen**, alle vijf `softwarefactory|http`, elk met een `HttpClient.newHttpClient()` als constructor-default; schrijf de reden in dezelfde stijl als de bestaande HTTP-regels (nu regel 13-15). Alle vijf onder `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/`:
     - `maintenance/services/GitHubPackageCleanupClient.kt`
     - `maintenance/services/GitHubProtectedShaSource.kt`
     - `maintenance/services/GitHubReleaseCleanupClient.kt`
     - `pipeline/service/StoryDeployReconciler.kt`
     - `telegram/services/TelegramResultNotifyPoller.kt`

**Buiten scope**

- Het script alsnog in een gate hangen (`tools/verify-repository`, `.factory/verification.yaml`, GitHub-workflows). `tools/verify-repository` staat nu al rood op `./quality/run.sh` en meldt drift op `tools/generate-module-dependencies --check`; `.factory/verification.yaml` draait bij elke agent-run. Welke gate leidend is, is een menselijke beslissing.
- Elke wijziging in productiecode (Kotlin), inclusief het opheffen van de geregistreerde boundaries zelf.
- Documentatiewijzigingen: `docs/technical/modules.md:212-214`, `docs/technical/external-systems.md:71-73` en `docs/verbetertraject-2026-07/VOORTGANG.md:400` beschrijven het register kwalitatief en noemen geen aantallen, dus ze blijven correct.

## Acceptance criteria

1. `bash tools/check-composition-roots` draait zonder fout en print exact `composition-root-boundaries/v1: PASS (27 exact paths)`.
2. `tools/check-composition-roots` bevat geen `rg`-aanroep meer; de zoekstap gebruikt `grep -rlE --include='*.kt'` met hetzelfde patroon en dezelfde vier mappen, met een comment die de bewuste keuze voor `grep` toelicht.
3. `architecture/composition-root-boundaries.txt` bevat 27 dataregels plus de bestaande headerregel; elke dataregel heeft vier `|`-gescheiden velden en de dataregels staan alfabetisch op pad gesorteerd (dezelfde volgorde als `sort`).
4. Elk van de 27 paden bestaat als bestand op schijf en bevat minstens één van `System.getenv`, `ProcessBuilder`, `HttpClient.new`; er is geen pad in het register dat niet in de grep-uitkomst zit en omgekeerd.
5. De vijf toegevoegde regels hebben runtime `softwarefactory` en capability `http`, met een reden in dezelfde stijl als de bestaande HTTP-regels.
6. De vijf verhuisde regels behouden hun oorspronkelijke `runtime`- en `capability`-waarden; alleen het pad (en waar nodig een pakketnaam in de reden-tekst) wijzigt.
7. `git diff --stat` raakt uitsluitend `tools/check-composition-roots` en `architecture/composition-root-boundaries.txt`; geen `.kt`-bestand is gewijzigd.
8. `mvn --batch-mode verify` blijft groen (gewone regressiecontrole; er verandert geen productiecode).

## Aannames

- De grep-variant is een pure toolwissel zonder betekenisverandering: met en zonder `--include='*.kt'` levert grep exact dezelfde 27 paden op (geverifieerd in de checkout), en die 27 zijn dezelfde set die `rg -l` zou opleveren. `--include='*.kt'` blijft staan omdat het het script ongevoelig maakt voor toekomstige niet-Kotlin-bestanden onder `src/main`.
- `AuditJobsReader.kt` is de functionele opvolger van `NightlyJobsReader.kt` (zelfde `SF_GITHUB_TOKEN`-achtervang), dus dit telt als pad-correctie en niet als "regel verwijderen + nieuwe regel toevoegen"; de reden-tekst mag daarbij van `nightly` naar `audit` herschreven worden zonder dat capability of runtime wijzigt.
- `VOORTGANG.md:400` schrijft "alleen krimpen" voor dit register. Dat wordt gelezen als een verbod op het oprekken van een overtredingsallowlist, niet als een verbod op het registreren van werkelijk bestaande boundaries. Het register moet exact zijn; groeien van 23 naar 27 is de enige manier om dat te bereiken zonder productiecode te wijzigen. Er wordt géén boundary weggerefactord om het aantal te drukken.
- Het script wordt nergens aangeroepen vanuit een gate, dus het groen worden ervan verandert het gedrag van geen enkele bestaande pipeline; de winst is dat het script handmatig draaibaar en betrouwbaar wordt.

## Eindsamenvatting

# Eindsamenvatting SF-1561

**[Audit] `tools/check-composition-roots` weer draaibaar maken en het composition-root-register in overeenstemming brengen met de code**

## Wat is er gebouwd

Het register `architecture/composition-root-boundaries.txt` legt vast welke bestanden zélf met de buitenwereld praten (omgevingsvariabelen, processen starten, HTTP). Het bewakende controlescript kon niet draaien en het register was daardoor uit de pas gelopen. Beide zijn gerepareerd — zonder één regel productiecode te wijzigen.

1. **Script draaibaar gemaakt** — `tools/check-composition-roots` gebruikte `rg` (ripgrep), dat nergens in deze repo/runners geïnstalleerd staat. De zoekstap is vervangen door `grep -rlE --include='*.kt'` met exact hetzelfde patroon en dezelfde vier mappen, met een korte toelichting waarom bewust voor `grep` is gekozen (een ontbrekende `rg` geeft exit 127 en is niet te onderscheiden van een echte bevinding). De rest van het script — sortering, beide `comm`-vergelijkingen, duplicaatcheck en de PASS-regel — is letterlijk ongewijzigd.

2. **Register exact gemaakt** — van 23 naar 27 regels, sluitend op de werkelijkheid:
   - **5 paden gecorrigeerd** (bestanden waren verhuisd), met ongewijzigde runtime en capability: `OrchestratorSettingsFactory`, `DeploymentStatusProbe`, `ClaudeAssistantClient`, `TelegramClient` en `NightlyJobsReader` → `audit/services/AuditJobsReader.kt`.
   - **1 regel verwijderd**: `web/controllers/FactoryApiController.kt` bevat geen van de drie patronen meer.
   - **5 regels toegevoegd** (alle `softwarefactory|http`, elk met een `HttpClient.newHttpClient()`-constructor-default): `GitHubPackageCleanupClient`, `GitHubProtectedShaSource`, `GitHubReleaseCleanupClient`, `StoryDeployReconciler` en `TelegramResultNotifyPoller`.

3. **Contracttest toegevoegd** — `tools/test-check-composition-roots` (test-only, naar analogie van het bestaande `tools/test-audit-documentation`). Zonder deze test zou de wijziging alleen door handmatig draaien gedekt zijn.

## Gemaakte keuzes

- **Groeien van 23 → 27 regels is bewust.** De richtlijn "dit register mag alleen krimpen" is gelezen als een verbod op het oprekken van een overtredingsallowlist, niet als verbod op het registreren van werkelijk bestaande boundaries. Er is géén boundary weggerefactord om het aantal te drukken — dat zou productiecode raken.
- **`AuditJobsReader` telt als pad-correctie**, niet als verwijderen + toevoegen: het is de functionele opvolger van `NightlyJobsReader` met dezelfde `SF_GITHUB_TOKEN`-achtervang. Alleen de woordkeuze "nightly" → "audit" in de reden-tekst is meeverhuisd.
- **De contracttest valt strikt genomen buiten de 2-bestandsscope**, maar is test-only, volgt het bestaande `tools/test-*`-patroon en hangt in geen enkele gate. Door reviewer akkoord bevonden.

## Wat is getest

| Controle | Uitkomst |
|---|---|
| `bash tools/check-composition-roots` | `composition-root-boundaries/v1: PASS (27 exact paths)` (exit 0) |
| `bash tools/test-check-composition-roots` | `composition-root contract: PASS` (exit 0) |
| `mvn -B clean verify` | BUILD SUCCESS, alle modules groen, 0 failures / 0 errors |
| `tools/audit-documentation` | `documentation-audit/v1: PASS` |
| Alle 8 acceptatiecriteria | zelfstandig nagelopen door de tester, alle voldaan |

De 27 registerpaden zijn stuk voor stuk geverifieerd: elk bestand bestaat, bevat minstens één van de drie patronen, en de vergelijking met de grep-uitkomst is in beide richtingen leeg. `git diff` raakt geen enkel `.kt`-bestand.

## Bewust niet gedaan

- **Het script in een gate hangen** (`tools/verify-repository`, `.factory/verification.yaml`, GitHub-workflows). Welke gate leidend moet zijn is een menselijke beslissing; het script is nu betrouwbaar handmatig draaibaar.
- **Productiecode wijzigen** — de geregistreerde boundaries zelf zijn niet opgeheven.
- **Documentatie aanpassen** — `docs/technical/modules.md`, `external-systems.md` en `VOORTGANG.md` beschrijven het register kwalitatief zonder aantallen en blijven correct.
- **`./quality/run.sh` en `tools/generate-module-dependencies --check`** — staan pre-existent rood op `main`, zitten niet in de verificatie-config, en kunnen door deze wijziging niet geraakt zijn.

## Aandachtspunten voor vervolgwerk

1. **Flaky test gevonden (pre-existent, niet door deze story veroorzaakt).** `FactoryApiControllerTest` liet één keer de surefire-fork omvallen. Oorzaak achterhaald: de happy-path-test gebruikt een echte `FactoryProcessService`; een geslaagde `/api/restart` start `scheduleExit()`, die na een vertraging `Runtime.halt(0)` doet en zo de testfork mid-run kan afschieten. Isolatie-hercontrole en een tweede volledige run waren groen. Structurele fix (stub-processervice of `scheduleExit()` injecteerbaar maken) verdient een eigen story.
2. **Sorteervolgorde is locale-afhankelijk** — onder `LC_ALL=C` zou de sorteerassertie in de contracttest rood worden. Het hoofdscript is hier ongevoelig voor. Overweeg `LC_ALL=C sort` vast te pinnen.
3. **`tools/test-audit-documentation` gebruikt zelf nog `rg`** en faalt hier met exit 127 — dezelfde kwaal, buiten scope gehouden.
