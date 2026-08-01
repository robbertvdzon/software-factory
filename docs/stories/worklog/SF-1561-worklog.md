# SF-1561 - Worklog

Story-context bij eerste pickup:
Script op grep zetten en composition-root-register exact maken

Twee bestanden, geen productiecode.

1) tools/check-composition-roots regels 10-11: vervang de `rg -l`-aanroep door `grep -rlE --include='*.kt' 'System\.getenv|ProcessBuilder|HttpClient\.new' softwarefactory/src/main agentworker/src/main dashboard-backend/src/main factory-common/src/main | sort > "$actual"`. Zet er een kort commentaar boven in de stijl van tools/audit-documentation:7-8 ('# Bewust grep i.p.v. rg: ...ontbrekende rg geeft exit 127 en is niet te onderscheiden van een echte bevinding'). Laat de rest van het script (awk, beide comm-vergelijkingen, duplicaatcheck, PASS-regel) letterlijk ongewijzigd.

2) architecture/composition-root-boundaries.txt: breng de dataregels op de 27 paden uit de grep-uitkomst. Behoud de headerregel, het formaat exact-path|runtime|capability|reason en alfabetische volgorde (gebruik dezelfde `sort` als het script).
- Vijf paden corrigeren, runtime/capability/reason ongewijzigd overnemen: config/OrchestratorSettingsFactory.kt -> config/services/OrchestratorSettingsFactory.kt; core/DeploymentStatusProbe.kt -> core/contracts/DeploymentStatusProbe.kt; telegram/ClaudeAssistantClient.kt -> telegram/clients/ClaudeAssistantClient.kt; telegram/TelegramClient.kt -> telegram/clients/TelegramClient.kt; nightly/NightlyJobsReader.kt -> audit/services/AuditJobsReader.kt (reden-tekst mag 'nightly' -> 'audit' herformuleren; runtime/capability blijven softwarefactory|environment).
- Eén regel verwijderen: web/controllers/FactoryApiController.kt.
- Vijf regels toevoegen, alle softwarefactory|http, met een reden in dezelfde stijl als de bestaande HTTP-regels (nu regel 13-15), alle onder softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/: maintenance/services/GitHubPackageCleanupClient.kt, maintenance/services/GitHubProtectedShaSource.kt, maintenance/services/GitHubReleaseCleanupClient.kt, pipeline/service/StoryDeployReconciler.kt, telegram/services/TelegramResultNotifyPoller.kt.

Afronden: draai `bash tools/check-composition-roots` en bevestig de output 'composition-root-boundaries/v1: PASS (27 exact paths)'. Controleer met `git diff --stat` dat uitsluitend tools/check-composition-roots en architecture/composition-root-boundaries.txt gewijzigd zijn en geen enkel .kt-bestand. Draai `mvn --batch-mode verify` als regressiecontrole. Doe daarna een eigen review op scope en formaat.

Buiten scope: het script in een gate hangen (tools/verify-repository, .factory/verification.yaml, workflows), elke wijziging in Kotlin-code, en documentatiewijzigingen in docs/technical/modules.md, docs/technical/external-systems.md of docs/verbetertraject-2026-07/VOORTGANG.md.

Stappenplan:
[x]: read issue and target docs
[x]: tools/check-composition-roots van `rg -l` naar `grep -rlE --include='*.kt'` omzetten
[x]: architecture/composition-root-boundaries.txt exact maken (27 paden)
[x]: contracttest tools/test-check-composition-roots schrijven
[x]: run relevant tests (script, contracttest, mvn verify, documentatie-audit)
[x]: update story-log with results

Done / rationale (SF-1646, developer):
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `tools/check-composition-roots` regel 10-11: `rg -l` vervangen door
  `grep -rlE --include='*.kt'` over exact dezelfde vier mappen en met exact hetzelfde
  patroon, met een `# Bewust grep i.p.v. rg`-comment in de stijl van
  `tools/audit-documentation:7-8` (ripgrep staat hier niet geïnstalleerd; exit 127 was niet
  te onderscheiden van een echte bevinding). De rest van het script (awk, beide
  `comm`-vergelijkingen, duplicaatcheck, PASS-regel) is letterlijk ongewijzigd.
- `architecture/composition-root-boundaries.txt` sluit nu exact op de 27 grep-paden:
  vijf verhuisde paden gecorrigeerd met ongewijzigde runtime/capability
  (config/services/OrchestratorSettingsFactory.kt, core/contracts/DeploymentStatusProbe.kt,
  telegram/clients/ClaudeAssistantClient.kt, telegram/clients/TelegramClient.kt en
  nightly/NightlyJobsReader.kt -> audit/services/AuditJobsReader.kt met "nightly" -> "audit"
  in de reden-tekst); `web/controllers/FactoryApiController.kt` verwijderd (bevat geen van de
  drie patronen meer); vijf `softwarefactory|http`-regels toegevoegd voor de constructor-default
  `HttpClient.newHttpClient()` in GitHubPackageCleanupClient, GitHubProtectedShaSource,
  GitHubReleaseCleanupClient, StoryDeployReconciler en TelegramResultNotifyPoller.
  Headerregel, `exact-path|runtime|capability|reason` en de `sort`-volgorde zijn behouden.
- Nieuw: `tools/test-check-composition-roots` (test-only, naar analogie van de bestaande
  `tools/test-audit-documentation`). Het dekt af: geen `rg`-aanroep meer + `grep -rlE
  --include='*.kt'` met hetzelfde patroon; registerformaat (header, vier velden, alfabetisch,
  elk pad bestaat en bevat nog directe I/O); PASS-regel en telling op de echte checkout; en
  vier negatieve/positieve paden in een wegwerp-git-repo (niet-geregistreerde I/O, verouderde
  regel, dubbel pad -> rood; sluitend register -> groen). Zonder deze test zou de wijziging
  alleen door handmatig draaien gedekt zijn.

Bewijs (2026-08-01, branch ai/SF-1561):
- `bash tools/check-composition-roots` -> `composition-root-boundaries/v1: PASS (27 exact paths)` (exit 0).
- `bash tools/test-check-composition-roots` -> `composition-root contract: PASS` (exit 0).
- `mvn -B --no-transfer-progress clean verify` vanaf de repo-root -> BUILD SUCCESS in 05:36 min,
  alle modules groen, 0 failures / 0 errors.
- `tools/audit-documentation` -> `documentation-audit/v1: PASS` (exit 0).
- `git diff --stat` raakt uitsluitend `tools/check-composition-roots` en
  `architecture/composition-root-boundaries.txt`; geen enkel `.kt`-bestand gewijzigd.
  Nieuw/untracked: dit worklog en `tools/test-check-composition-roots`.
- Niet gedraaid: `./quality/run.sh` en `tools/generate-module-dependencies --check` uit
  `tools/verify-repository`. Beide staan structureel rood op main en zijn pre-existent; deze
  story wijzigt geen Kotlin-code en geen moduleafhankelijkheden, dus ze kunnen er niet door
  geraakt zijn. Ze zitten ook niet in `.factory/verification.yaml`.
- `docs/factory/`-specs niet aangepast: er verandert geen product-/buildgedrag; het register en
  het script worden kwalitatief beschreven in `docs/technical/*` en die teksten noemen geen
  aantallen (expliciet buiten scope gehouden in de story).

## Review (SF-1646, reviewer, 2026-08-01)

Beoordeeld: volledige story-diff `git diff main...HEAD` (4 bestanden, geen `.kt`).

Eigen gerichte hercontroles in deze checkout:
- `bash tools/check-composition-roots` -> `composition-root-boundaries/v1: PASS (27 exact paths)` (exit 0).
- `bash tools/test-check-composition-roots` -> `composition-root contract: PASS` (exit 0).
- `tools/audit-documentation` -> `documentation-audit/v1: PASS` (exit 0).
- Register: 1 header + 27 dataregels, alle vier velden, `sort`-volgorde klopt in de omgevingslocale.
- Alle vijf toegevoegde paden bevatten `HttpClient.newHttpClient()` als constructor-default;
  `AuditJobsReader.kt:153` heeft de `System.getenv("SF_GITHUB_TOKEN")`-achtervang;
  `web/controllers/FactoryApiController.kt` bevat 0 treffers -> terecht verwijderd.
- Verhuisde regels behouden runtime/capability (environment-doc, process-doc, process-environment,
  http, environment). Vijf toegevoegde regels zijn `softwarefactory|http`.
- `docs/technical/modules.md:213` en `docs/technical/external-systems.md:72` beschrijven het
  register kwalitatief zonder aantallen -> blijven correct, geen spec-inconsistentie.

Bevindingen:
- [info] `tools/test-check-composition-roots` valt buiten de letterlijke 2-bestandsscope, maar is
  test-only, volgt het bestaande `tools/test-*`-patroon en hangt in geen enkele gate. AC7
  (geen `.kt` gewijzigd) blijft voldaan. Akkoord.
- [suggestie] De sorteerassertie in `tools/test-check-composition-roots` (regel 30-31) gebruikt de
  omgevingslocale. Onder `LC_ALL=C` staat `github/clients/GitHubCliClient.kt` na
  `git/services/ProcessRunner.kt` en zou die assertie rood worden. Het hoofdscript zelf is hier
  ongevoelig voor (beide `comm`-kanten worden in dezelfde shell gesorteerd), dus dit raakt geen
  gate. Overweeg bij een vervolgstory `LC_ALL=C sort` vast te pinnen in zowel register als test.
- [suggestie] `tools/test-audit-documentation` gebruikt zelf nog `rg` (exit 127 hier). Buiten scope
  van deze story; kandidaat voor de vervolgstory die de developer al signaleerde.
