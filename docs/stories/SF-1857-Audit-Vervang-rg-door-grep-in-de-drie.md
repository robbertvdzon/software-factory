# SF-1857 - [Audit] Vervang rg door grep in de drie tools/test-*-scripts en breid de bestaande rg-contracttest uit naar alle vier

## Story

[Audit] Vervang rg door grep in de drie tools/test-*-scripts en breid de bestaande rg-contracttest uit naar alle vier

<!-- refined-by-factory -->

## Samenvatting

Drie van de vier zelftests in de map `tools/` kunnen op dit moment niet draaien: ze gebruiken een
zoekprogramma dat in deze repo nergens geïnstalleerd is, waardoor ze meteen afbreken. Daardoor
controleert niemand meer of de bijbehorende scripts nog kloppen.

Deze story vervangt dat zoekprogramma door het standaardalternatief dat elders in de repo al
bewust gebruikt wordt, breidt de bestaande bewaking uit zodat het probleem niet opnieuw kan
insluipen, en hangt de vier zelftests aan de bestaande repositorycontrole zodat ze voortaan
echt meedraaien.

Er verandert geen productiecode; het gaat uitsluitend om hulpscripts en documentatie.

## Scope

In scope:

1. **`rg` → `grep` in drie scripts.** Vervang `rg -F --quiet <patroon> <bestand>` door
   `grep -qF <patroon> <bestand>` op `tools/test-verify-repository:18`,
   `tools/test-audit-documentation:5-6` en `tools/test-audit-branch-protection:7-8`. Eén-op-één:
   zelfde literal-matching, zelfde stille exit-code, geen recursie of globs in het spel. Voeg in
   elk van de drie een eigen commentaarregel `# Bewust grep i.p.v. rg: ...` toe, naar analogie van
   `tools/audit-documentation:7-8`. Die regel moet een **hele** regel zijn (geen trailing comment),
   anders slaat de controle uit punt 2 erop aan.
2. **rg-controle verbreden.** De controle uit `tools/test-check-composition-roots:13-14`
   (`grep -v '^[[:space:]]*#' "$script" | grep -qE '(^|[^-[:alnum:]])rg[[:space:]]'`) moet lopen
   over zeven paden: `tools/verify-repository`, `tools/audit-documentation`,
   `tools/audit-branch-protection` en de vier `tools/test-*`-scripts. Vorm vrij: een lus in
   `tools/test-check-composition-roots` (eenvoudigst) of een apart `tools/test-no-ripgrep`, mits
   dat laatste dan ook in de gate uit punt 3 hangt. De bestaande commentaarfilter
   `grep -v '^[[:space:]]*#'` blijft ongewijzigd, want de commentaarregels uit punt 1 noemen `rg`
   letterlijk.
   Let op: `tools/test-check-composition-roots:14` bevat zelf de tekst `fail 'script roept nog rg
   aan'`. Dat is géén commentaarregel en matcht het patroon, dus de controle over dat bestand is
   nu rood. Herformuleer die foutmelding zodat de losse token `rg` er niet meer in staat
   (bv. "roept nog ripgrep aan"); het bestand uitzonderen van de lus is geen acceptabele oplossing.
3. **Vier contracttests in de repositorygate.** Voeg aan `tools/verify-repository` één `run`-regel
   toe die de vier `tools/test-*`-scripts draait. Ze raken geen netwerk, database of build en
   duren samen onder de seconde; plaats de stap vooraan zodat hij vóór de trage stappen faalt.
4. **Documentatie bijwerken.** `docs/technical/modules.md` (paragraaf over
   `check-composition-roots`, rond r244-250) stelt dat het script "in geen enkele gate" hangt en
   handmatig gedraaid moet worden. Na punt 3 klopt dat niet meer: `test-check-composition-roots`
   voert het echte script uit (r33) en draait voortaan mee in `tools/verify-repository`. Werk die
   passage bij.

Buiten scope (bewust):

- Het verschil dat `tools/test-verify-repository:10-17` zeven commando's bewaakt terwijl
  `tools/verify-repository:30-38` er negen heeft (`./quality/run.sh` en
  `tools/audit-documentation` ontbreken). Dat is een menselijke beslissing, geen refactor.
  De nieuwe gate-stap uit punt 3 wordt om dezelfde reden **niet** aan die bewaakte lijst
  toegevoegd.
- Het opnemen van de nieuwe stap in `.factory/verification.yaml` of `.github/workflows/verify.yml`.
  De headercomment van `tools/verify-repository` suggereert een 1-op-1-correspondentie met
  `verification.yaml`, maar die klopt nu al niet (quality-ratchet en module-dependency-drift
  ontbreken daar). Dat rechttrekken hoort bij dezelfde openstaande beslissing.
- Elke wijziging aan productiecode of aan het gedrag van `tools/verify-repository`,
  `tools/audit-documentation`, `tools/audit-branch-protection` en
  `tools/check-composition-roots` zelf.

## Acceptance criteria

1. `grep -rn 'rg -F' tools/` geeft geen treffers meer; de drie genoemde aanroepen zijn
   `grep -qF <patroon> <bestand>` met identieke patronen en bestanden als nu.
2. Elk van `tools/test-verify-repository`, `tools/test-audit-documentation` en
   `tools/test-audit-branch-protection` bevat een eigen volledige commentaarregel die begint met
   `# Bewust grep i.p.v. rg:` en de reden noemt (ontbrekende rg = exit 127, niet te onderscheiden
   van een echte bevinding).
3. Los gedraaid geven alle vier de scripts exit 0 met hun bestaande slotregel:
   `verify-repository contract v1 is valid`, `documentation audit contract: PASS`,
   `branch-protection audit contract is valid`, `composition-root contract: PASS`.
4. De rg-controle draait over de zeven genoemde paden en is groen op de huidige checkout — inclusief
   `tools/test-check-composition-roots` zelf.
5. De controle is aantoonbaar fail-closed: voeg tijdelijk een regel `rg -F --quiet x y` toe aan één
   van de zeven bewaakte scripts en stel vast dat de controle rood wordt met een melding die het
   betreffende pad noemt; draai die wijziging daarna terug. Voeg omgekeerd tijdelijk een
   commentaarregel toe die `rg ` bevat en stel vast dat die géén vals alarm geeft.
6. `tools/verify-repository` bevat één `run`-regel die de vier `tools/test-*`-scripts uitvoert, en
   `bash -n tools/verify-repository` slaagt. `tools/verify-repository --version` geeft nog steeds
   exact `repository-verification/v1`.
7. `bash tools/audit-documentation` en `bash tools/check-composition-roots` geven onveranderd
   exit 0 met `documentation-audit/v1: PASS` respectievelijk
   `composition-root-boundaries/v1: PASS (<n> exact paths)`.
8. `docs/technical/modules.md` beschrijft niet langer dat `check-composition-roots` in geen enkele
   gate hangt, maar dat de contracttest via `tools/verify-repository` meedraait.
9. `git diff --stat` raakt uitsluitend bestanden onder `tools/` en `docs/`; geen enkele regel
   productiecode (`*/src/main/**`) verandert.

## Aannames

- `tools/verify-repository` wordt niet integraal gedraaid ter verificatie (die doet mvn,
  `./quality/run.sh`, flutter en een docker build). Verificatie loopt via de vier `test-*`-scripts
  los, `bash -n tools/verify-repository`, plus `tools/audit-documentation` en
  `tools/check-composition-roots`.
- De nieuwe gate-stap krijgt een eigen command-id in de bestaande `run <id> ...`-stijl
  (bv. `repository-contract-tests`); de exacte naam is vrij.
- Een expliciete lijst van de vier scriptpaden heeft de voorkeur boven een glob `tools/test-*`,
  zodat een toekomstig nieuw testscript een bewuste toevoeging blijft. Een glob is acceptabel mits
  hij geen niet-testbestanden kan raken.
- CI (`.github/workflows/verify.yml`) draait `tools/verify-repository` niet en de tester-runner
  gebruikt `.factory/verification.yaml`; deze story verandert dus niets aan CI- of testerbudget.
- Naast `docs/technical/modules.md` is er geen andere doc die de vier contracttests of hun
  gate-status beschrijft (nul verwijzingen buiten `docs/stories/`); komt de developer er toch een
  tegen, dan wordt die in dezelfde lijn bijgewerkt.

## Eindsamenvatting

Alles gelezen: `.task.md`, worklog, reviewer- en tester-notities, en de volledige story-diff.

## Eindsamenvatting SF-1857

**Probleem.** Drie van de vier zelftests in `tools/` riepen `rg` (ripgrep) aan. Ripgrep staat niet in deze repo/runner, dus die scripts stopten meteen met exit 127 — niet te onderscheiden van een echte bevinding. Gevolg: de contracten rond `verify-repository`, `audit-documentation` en `audit-branch-protection` werden feitelijk niet meer bewaakt.

**Wat is gebouwd (7 bestanden, uitsluitend `tools/` en `docs/`):**

1. **`rg` → `grep`, 1-op-1.** In `tools/test-verify-repository`, `tools/test-audit-documentation` en `tools/test-audit-branch-protection` is `rg -F --quiet` vervangen door `grep -qF` met exact dezelfde patronen en bestanden. Elk script kreeg een volledige commentaarregel `# Bewust grep i.p.v. rg: …` met de reden.
2. **rg-bewaking verbreed van 1 naar 7 paden.** De losse check in `tools/test-check-composition-roots` is een lus geworden over de drie gate-scripts plus de vier contracttests. De commentaarfilter is ongewijzigd, zodat de nieuwe uitleg-regels geen vals alarm geven. De foutmelding noemt nu het betreffende pad. De zelf-treffer (de oude melding `'script roept nog rg aan'` matchte zijn eigen patroon) is opgelost door herformulering naar "roept nog ripgrep aan" — niet door het bestand uit te zonderen. Extra vangnet: ontbreekt een bewaakt pad, dan faalt de lus in plaats van stil over te slaan.
3. **Gate-stap.** `tools/verify-repository` heeft één nieuwe `run`-regel `repository-contract-tests` die de vier testscripts draait, vooraan vóór de trage stappen, met een expliciete lijst (geen glob) en fail-fast bij de eerste rode.
4. **Documentatie.** De verouderde passage in `docs/technical/modules.md` ("hangt in geen enkele gate") beschrijft nu de deelname via `repository-contract-tests`.

**Getest (developer, reviewer én tester, alle 9 AC's afgevinkt).** Vier contracttests los: exit 0 met hun bestaande slotregels. `bash -n tools/verify-repository` ok; `--version` geeft nog exact `repository-verification/v1`. Beide audits onveranderd PASS (`documentation-audit/v1: PASS`, `composition-root-boundaries/v1: PASS (27 exact paths)`). `grep -rn 'rg -F' tools/` geeft geen treffers. Fail-closed is aantoonbaar bewezen in een wegwerp-kopie: een `rg`-regel in **elk** van de zeven bewaakte paden maakt de controle rood met het pad in de melding; een commentaarregel met `rg ` geeft géén vals alarm; een ontbrekend pad faalt. Volledige `mvn clean verify` vanaf repo-root: BUILD SUCCESS, 0 failures/errors (981 tests).

**Bewust niet gedaan.** Geen productiecode geraakt. De nieuwe gate-stap is niet toegevoegd aan de bewaakte commandolijst in `tools/test-verify-repository`, niet aan `.factory/verification.yaml` en niet aan `.github/workflows/verify.yml` — die scheefstand (7 vs. 9 bewaakte commando's) is een openstaande menselijke beslissing, geen refactor. `tools/verify-repository` is niet integraal gedraaid (doet maven, quality-run, flutter en een docker build); verificatie liep conform de story-aannames via de losse scripts. Geen preview-URL en geen UI-oppervlak, dus geen browser-/E2E-scenario's of screenshots.

**Openstaand risico:** geen blockers. Eén info-opmerking van de reviewer: de commentaarregel in `tools/test-verify-repository` staat boven de `for`-lus in plaats van direct bij de `grep -qF` — inhoudelijk correct en AC-conform.

<!-- deploy-summary:start -->
De ingebouwde zelfcontroles van de repository werkten niet meer en zijn hersteld, zodat er weer automatisch gewaakt wordt over de kwaliteitschecks. Ze draaien nu ook automatisch mee bij elke controleronde in plaats van alleen handmatig. Aan de applicatie zelf is niets veranderd.
<!-- deploy-summary:end -->
