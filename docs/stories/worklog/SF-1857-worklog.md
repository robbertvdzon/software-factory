# SF-1857 - Worklog

Story-context bij eerste pickup:
rg→grep in contracttests, rg-controle verbreden, gate-stap en doc bijwerken

Voer alle vier stappen uit het plan in docs/stories/worklog/SF-1857-worklog.md uit. (1) Vervang in tools/test-verify-repository (r18), tools/test-audit-documentation (r5-6) en tools/test-audit-branch-protection (r7-8) `rg -F --quiet <patroon> <bestand>` door `grep -qF <patroon> <bestand>` met identieke patronen/bestanden, en voeg in elk van de drie een HELE commentaarregel toe die begint met `# Bewust grep i.p.v. rg:` en de reden noemt (ontbrekende rg = exit 127, niet te onderscheiden van een echte bevinding); geen trailing comments. (2) Til de controle uit tools/test-check-composition-roots:13-14 op naar een lus over zeven paden: tools/verify-repository, tools/audit-documentation, tools/audit-branch-protection en de vier tools/test-*-scripts. Behoud de filter `grep -v '^[[:space:]]*#'` ongewijzigd en laat de foutmelding het betreffende pad noemen. Let op de zelf-treffer: tools/test-check-composition-roots:14 bevat `fail 'script roept nog rg aan'` - code, geen commentaar, matcht het patroon; herformuleer die melding (bv. 'roept nog ripgrep aan'). Het bestand uitzonderen van de lus is niet acceptabel. Een apart tools/test-no-ripgrep mag, mits het in de gate uit (3) hangt. (3) Voeg aan tools/verify-repository één `run`-regel toe met een eigen command-id in bestaande stijl (bv. repository-contract-tests) die de vier tools/test-*-scripts draait, vooraan vóór de trage stappen; expliciete lijst van vier paden heeft de voorkeur boven een glob. Voeg de stap NIET toe aan de bewaakte lijst in tools/test-verify-repository:10-17 en NIET aan .factory/verification.yaml of .github/workflows/verify.yml (bewust buiten scope). (4) Werk de passage in docs/technical/modules.md (~r244-250) bij die stelt dat check-composition-roots 'in geen enkele gate' hangt; laat de door tools/audit-documentation bewaakte strings ('met 12 directe packages' en '`bridge`, `config`, `core`, `knowledge`, `merge`') intact. Verifieer zelf: alle vier test-scripts los via `bash <script>` exit 0 met hun bestaande slotregels; `bash -n tools/verify-repository`; `tools/verify-repository --version` geeft exact `repository-verification/v1`; `bash tools/audit-documentation` en `bash tools/check-composition-roots` onveranderd PASS; `grep -rn 'rg -F' tools/` geen treffers; fail-closed aantonen door tijdelijk `rg -F --quiet x y` toe te voegen aan één bewaakt script (moet rood met pad in de melding) en tijdelijk een commentaarregel met `rg ` (mag geen vals alarm geven), beide daarna terugdraaien; `git diff --stat` raakt uitsluitend tools/ en docs/. Draai tools/verify-repository NIET integraal. Sluit af met een eigen reviewronde op de diff en werk het worklog bij.

## Story in eigen woorden (SF-1871, developer)

Drie van de vier `tools/test-*`-contracttests riepen `rg` (ripgrep) aan. Ripgrep is in deze repo en
op de GitHub-runner niet geïnstalleerd, dus die scripts braken meteen af met exit 127 — niemand
controleerde nog of de bijbehorende gate-scripts klopten. Deze story vervangt `rg` door `grep`,
verbreedt de bestaande rg-bewaking van één naar zeven scriptpaden zodat het niet opnieuw kan
insluipen, hangt de vier contracttests als één stap aan `tools/verify-repository`, en werkt de
verouderde passage in `docs/technical/modules.md` bij. Geen productiecode geraakt.

Stappenplan:
[x]: read issue and target docs
[x]: (1) rg → grep in de drie contracttests, elk met een hele `# Bewust grep i.p.v. rg:`-regel
[x]: (2) rg-controle in tools/test-check-composition-roots verbreed naar zeven paden
[x]: (3) gate-stap `repository-contract-tests` vooraan in tools/verify-repository
[x]: (4) docs/technical/modules.md bijgewerkt
[x]: run relevant tests (vier contracttests, beide audits, fail-closed-bewijs, mvn verify)
[x]: update story-log with results

## Done / rationale

1. **rg → grep (1-op-1).** `tools/test-verify-repository:18`, `tools/test-audit-documentation:5-6`
   en `tools/test-audit-branch-protection:7-8` gebruiken nu `grep -qF <patroon> <bestand>` met
   exact dezelfde patronen en bestanden. `-qF` is de directe tegenhanger van `-F --quiet`: literal
   matching, stille exit-code, geen recursie of globs in het spel. In elk van de drie staat nu een
   eigen volledige commentaarregel `# Bewust grep i.p.v. rg: ...` (geen trailing comment, anders
   slaat de controle uit punt 2 erop aan), naar analogie van `tools/audit-documentation:7-8`.
2. **rg-controle verbreed.** De losse `if`-check in `tools/test-check-composition-roots` is een lus
   (blok `1b`) geworden over zeven expliciet opgesomde paden: `tools/verify-repository`,
   `tools/audit-documentation`, `tools/audit-branch-protection` en de vier `tools/test-*`-scripts.
   De commentaarfilter `grep -v '^[[:space:]]*#'` is ongewijzigd overgenomen — noodzakelijk, want de
   nieuwe commentaarregels uit punt 1 noemen `rg` letterlijk. De foutmelding noemt nu het pad
   (`"$guarded roept nog ripgrep aan"`); die herformulering van `rg` → `ripgrep` was nodig omdat de
   oude melding zelf code was en het patroon matchte, waardoor het bestand over zichzelf rood zou
   staan. Het bestand uitzonderen was expliciet geen optie. Extra vangnetje: ontbreekt een bewaakt
   pad, dan faalt de lus in plaats van stil over te slaan.
3. **Gate-stap.** `tools/verify-repository` heeft één nieuwe `run`-regel met command-id
   `repository-contract-tests`, vóór `repository-maven-verify`, die de vier testscripts in een
   expliciete lijst (geen glob) achter elkaar draait en bij de eerste rode stopt. Bewust NIET
   toegevoegd aan de bewaakte lijst in `tools/test-verify-repository:10-17`, aan
   `.factory/verification.yaml` of aan `.github/workflows/verify.yml` — dat staat buiten scope.
4. **Documentatie.** De passage in `docs/technical/modules.md` beschrijft niet langer dat
   `check-composition-roots` "in geen enkele gate" hangt, maar dat de contracttest via de stap
   `repository-contract-tests` in `tools/verify-repository` meedraait, met de handmatige aanroep als
   alternatief. De door `tools/audit-documentation` bewaakte strings zijn onaangeroerd (audit is
   groen). Geen andere doc noemt de vier contracttests of hun gate-status; `docs/factory/`-specs
   hoefden dus niet mee (geen functionele of stackwijziging).

## Bewijs

- Vier contracttests los, elk exit 0 met de bestaande slotregel: `verify-repository contract v1 is
  valid`, `documentation audit contract: PASS`, `branch-protection audit contract is valid`,
  `composition-root contract: PASS`.
- `bash -n tools/verify-repository` slaagt; `tools/verify-repository --version` geeft exact
  `repository-verification/v1`.
- `bash tools/audit-documentation` → `documentation-audit/v1: PASS`;
  `bash tools/check-composition-roots` → `composition-root-boundaries/v1: PASS (27 exact paths)`.
- `grep -rn 'rg -F' tools/` geeft geen treffers.
- Fail-closed aangetoond: tijdelijk `rg -F --quiet x y` toegevoegd aan `tools/audit-branch-protection`
  → `FAIL: tools/audit-branch-protection roept nog ripgrep aan` (exit 1), daarna teruggedraaid.
  Omgekeerd een tijdelijke commentaarregel met `rg ` in `tools/verify-repository` → nog steeds PASS,
  dus geen vals alarm; ook teruggedraaid.
- De nieuwe gate-stap zelf gedraaid als losstaand `bash -c '...'`: exit 0, alle vier slotregels.
- `git diff --stat` raakt uitsluitend `tools/` en `docs/`; geen enkele regel onder `*/src/main/**`.
- Volledig vangnet `mvn -B --no-transfer-progress clean verify` vanaf repo-root: **BUILD SUCCESS,
  exit 0**, 0 failures / 0 errors (16 + 55 + 719 + 78 + 61 + 52 tests). Geen bestaande rode tests
  aangetroffen, dus geen boyscout-herstel nodig.
- `tools/verify-repository` is bewust NIET integraal gedraaid (die doet o.a. `./quality/run.sh`,
  flutter en een docker build); dat staat zo in de aannames van de story.
