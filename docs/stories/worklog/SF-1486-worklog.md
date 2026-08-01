# SF-1486 - Worklog

## Story in eigen woorden

De quality-ratchet vergelijkt elke Detekt-run met een vastgelegde baseline via een hash
(`fingerprint`) per bevinding. Die hash bevat de ruwe Detekt-message, inclusief metriekwaarden
(`is too long (89)`, `has 3 return statements`, `(complexity: 23)`) en de volledige parameterlijst.
Eén regel erbij of één parameter erbij verandert dus de hash, en een ongewijzigd knelpunt telt als
"nieuw". Deze story maakt de fingerprint ongevoelig voor die ruis, tilt het baselineformaat naar
`schemaVersion 2` (met de genormaliseerde message en bronregel-shape erbij, zodat een volgende
algoritmewijziging herleidbaar is), hermunt de baseline en hangt de ratchet-unittests in de gate.
Normen worden niet versoepeld: de echte nieuwe bevindingen blijven zichtbaar en de gate blijft
terecht rood.

## Checklist

- [x]: issue, factory-docs en bestaande `quality/`-code gelezen
- [x]: `fingerprint()` ruisvrij gemaakt (quotes → haakjes → getallen, in die volgorde)
- [x]: `schemaVersion 2` in `collect()` (+ `message` en `shape` per finding) en harde versiecheck in
  `compare()` met verwijzing naar hermunting
- [x]: regressietests geschreven en gedraaid (18 tests groen, de negen bestaande ongewijzigd)
- [x]: baseline hermunt uit een losse worktree op de commit waar de baseline aantoonbaar vandaan komt
- [x]: machinale bewijsvoering multiset + suppressies
- [x]: `quality/run.sh` draait de unittests vóór Detekt, met harde exit
- [x]: `mvn verify` (volledig vangnet) gedraaid
- [x]: worklog bijgewerkt met bewijs en de resterende bevindingen
- [ ]: **twee afwijkingen t.o.v. de description/AC's voorgelegd aan de PO** (zie §"Escalatie")

## Wat is er gedaan

### 1. `quality/ratchet.py` — `fingerprint()`

Nieuwe helper `normalize_message()`, in de door de story voorgeschreven volgorde:

1. quote-normalisatie (`'naam'` / `` `naam` `` → `'ID'`) — eerst, zodat een gequote symbool nooit
   als kale identifier overleeft;
2. elk haakjespaar wordt samengevouwen tot `(ARGS)`, herhaald van binnen naar buiten zodat ook
   geneste haakjes (`build(a: Int, b: (Int) -> Unit)`) tot één marker vouwen;
3. resterende ongequote getallen → `NUM`.

Identifiers búiten de haakjes blijven staan, zodat twee functies in hetzelfde bestand een
verschillende fingerprint houden (`compare()` groepeert renames op (module, rule, fingerprint)
zonder path — daarom is dit essentieel). `normalize_shape()` op de bronregel is ongewijzigd.

### 2. `quality/ratchet.py` — schemaVersion 2

- Constante `SCHEMA_VERSION = 2`; `collect()` schrijft die en slaat per finding naast `fingerprint`
  ook `message` (genormaliseerd) en `shape` (genormaliseerde bronregel) op.
- `compare()` accepteert uitsluitend versie 2 en gooit op een versie 1-baseline (of ontbrekende
  versie) een `ValueError` die expliciet naar hermunten verwijst — geen stille acceptatie.
- De matchvolgorde (exact → shape → ambiguous faalt gesloten) en de `resolved`-berekening zijn
  ongewijzigd.

### 3. `quality/test_ratchet.py`

De `snapshot()`-helper is gemigreerd naar `SCHEMA_VERSION` (met optionele override voor de
versietest). De negen bestaande tests zijn inhoudelijk ongewijzigd en groen, inclusief
`test_equal_total_finding_swap_is_red`, `test_ambiguous_rename_is_red` en
`test_symbol_rename_keeps_shape`. Nieuw:

- gelijke fingerprint bij enkel een metriekwijziging: `(89)`→`(94)`, `has 3`→`has 4` return
  statements, `(complexity: 23)`→`(complexity: 25)`;
- gelijke fingerprint bij toegevoegde / verwijderde / hernoemde parameter, en bij een geneste
  parameterlijst;
- verschillende fingerprint voor twee verschillende functies op dezelfde bronregel-shape;
- een bevinding in een eerder schoon bestand blijft ROOD via `compare()`;
- een `schemaVersion 1`-baseline en een ontbrekende versie geven een expliciete fout.

`python3 -m unittest discover quality` → **Ran 18 tests — OK**.

### 4. Hermunte baseline — welke commit, en waarom niet `a6b3132`

De description schrijft hermunten voor op commit `a6b3132`. Dat is gedaan (losse worktree
`/tmp/ratchet-a6b3132`, `mvn -q -Pquality -pl factory-contracts,factory-common,softwarefactory,agentworker,dashboard-backend detekt:check`,
exit 0) **en het reproduceert de bestaande baseline niet**. Met het ónveranderde (oude) script gaf
`collect --root /tmp/ratchet-a6b3132`:

- blokkerend: 208 findings, maar **141** distincte (module, rule, path)-tupels i.p.v. 140, met een
  verschil van 2 tupels per kant:
  - alleen in de baseline: `ReturnCount` in `FactoryApiController.kt`, `ReturnCount` in
    `TrackerStoryApiController.kt`;
  - alleen op `a6b3132`: `TooManyFunctions` in `AgentCli.kt`, `ReturnCount` in `AuditSeeding.kt`;
- de enige suppressie staat op `a6b3132` op regel **292**, in de baseline op regel **291**.

`a6b3132` is dus niet de tree waar de baseline vandaan komt. De aanname dat het dat wél was, komt
waarschijnlijk doordat de checkout **shallow** was (`.git/shallow`; `a6b3132` was de oudste
zichtbare commit). Na `git fetch --deepen=60` wijst `git log -- quality/baselines/plan-07-ratchet.json`
de echte commit aan: **`0c6fac5` ("SF-1360: Software Factory changes (#240)")**.

Bewijs dat `0c6fac5` de baselinecommit is: Detekt in een losse worktree op `0c6fac5` +
`collect` met het **oude** script levert een bestand op dat **byte-identiek** is aan de
gecommitte `quality/baselines/plan-07-ratchet.json` (`old == new` → `True` op alle sleutels).
De hermunting is daarna met het **nieuwe** script op diezelfde worktree gedaan:

```
python3 quality/ratchet.py collect --root /tmp/ratchet-0c6fac5 \
  --output quality/baselines/plan-07-ratchet.json
```

Er is dus niets afgeleid uit de run van vandaag.

### 5. Machinale bewijsvoering (AC2, AC3, AC4)

Vergelijking oude baseline (v1) vs. hermunte baseline (v2):

```
schemaVersion 1 -> 2
modules equal: True
blockingRules equal: True
blocking findings: old 208 new 208 | distinct tuples old 140 new 140
MULTISET EQUAL: True
diff old-only {} new-only {}
ALL findings multiset equal: True 744 744
suppressions old [{'line': 291, 'path': '.../bridge/services/BridgeRequestHandler.kt', 'text': '@Suppress("unused")'}]
suppressions new [{'line': 291, 'path': '.../bridge/services/BridgeRequestHandler.kt', 'text': '@Suppress("unused")'}]
suppressions equal: True
fingerprints changed for 164 of 744
```

- AC2: exact dezelfde multiset van (module, rule, path) over de 10 `BLOCKING_RULES` — 208 findings,
  140 distincte tupels, per-tupel gelijk. (Sterker nog: ook over álle 744 findings gelijk.)
- AC3: nog steeds precies 1 suppressie, ongewijzigd qua path, regel en text.
- AC4: `schemaVersion: 2`, en elke finding heeft naast `fingerprint` nu `message` en `shape`, bv.:
  `"message": "The function mapPhase appears to be too complex based on Cyclomatic Complexity (ARGS). Defined complexity threshold for methods is set to 'ID'"`,
  `"shape": "IDIDID(ID:ID,ID:ID):ID?{"`.
- 164 van de 744 fingerprints veranderden door de nieuwe normalisatie; de verzameling bevindingen
  zelf niet.

### 6. `quality/run.sh`

`python3 -m unittest discover quality` draait nu vóór de Detekt-stap, met de testoutput in
`$OUT/ratchet-tests.log` en een harde `exit 1` bij falen. Daarmee lopen de ratchet-tests
automatisch mee in `tools/verify-repository` stap 2.

### 7. Uitkomst van `bash quality/run.sh` (AC5, AC6)

Exit 1 — rood, zoals bedoeld. `delta.json`: `ambiguous: []`, `newSuppressions: []`, `renamed: 0`,
`resolved: 0`, `findingCount: 756`, `suppressionCount: 1`, en **4** nieuwe blokkerende bevindingen
i.p.v. de 3 uit AC5:

| # | rule | bestand | regel | functie | afweging |
|---|------|---------|-------|---------|----------|
| 1 | `ReturnCount` | `softwarefactory/.../audit/services/AuditSeeding.kt` | 25 | `isSeeded` | echte bevinding uit AC5; oplossen valt buiten deze story |
| 2 | `ReturnCount` | `softwarefactory/.../telegram/services/TelegramAuditQuestionService.kt` | 24 | `notifyAuditQuestion` | echte bevinding uit AC5; idem |
| 3 | `TooManyFunctions` | `agentworker/.../agentworker/cli/AgentCli.kt` | 1 | 14 functies (drempel 11) | echte bevinding uit AC5; idem |
| 4 | `ReturnCount` | `softwarefactory/.../pipeline/service/DeploySubtaskHandler.kt` | 127 | `process` | **niet voorzien in AC5**; drift van main ná de refinement: `DeploySubtaskHandler.kt` is gewijzigd in `59810ce` (SF-1560, #330). De overige zes `ReturnCount`-bevindingen in dat bestand staan wél in de baseline; alleen `process` is nieuw. Wegwerken zou het oprekken van de baseline zijn en valt buiten scope. |

Geen ambiguous, geen nieuwe suppressies — de fingerprint-ruis is dus weg; wat overblijft zijn echte
bevindingen.

**Voor/na-meting van de ruis (zelfde Detekt-run van vandaag):**

| | nieuwe blokkerende bevindingen | ambiguous |
|---|---|---|
| oude fingerprint + oude baseline (v1) | **23** | 0 |
| nieuwe fingerprint + hermunte baseline (v2) | **4** | 0 |

De 19 verdwenen "nieuwe" bevindingen (o.a. `AgentPromptContracts.kt`, `ProjectConfiguration.kt`,
`AuditGatewayAdapter.kt`, `OrchestratorService.kt`) waren puur metriek-/parameterlijstruis en staan
onder hetzelfde (module, rule, path) al in de baseline.

AC7 (score): `quality/run.sh` faalt bij de check-stap (`set -e`) en komt daardoor — net als vóór
deze wijziging — niet toe aan het schrijven van `qualityrun/quality-score.json`; dat bestand bestaat
niet in de checkout en `qualityrun/` staat in `.gitignore`. De score is wél ongewijzigd van
definitie en telling: fingerprinting raakt alleen hashes, niet het aantal findings (756) of
suppressies (1). Buiten `quality/` raakt `git diff` alleen dit worklog.

### Vangnet

- `python3 -m unittest discover quality`: Ran 18 tests — OK.
- `mvn -B --no-transfer-progress clean verify` vanaf de repo-root: zie §"Vangnet-uitkomst" hieronder.
- `bash quality/run.sh`: exit 1 met de vier bevindingen hierboven — de verwachte, gewenste
  eindtoestand van deze story.

## Escalatie naar de PO

Twee punten wijken af van de description/AC's. Beide zijn gemeld in plaats van weggewerkt:

1. **Hermunt-commit**: de description pint `a6b3132`; die tree reproduceert de baseline aantoonbaar
   niet (zie §4). De echte baselinecommit is `0c6fac5`, machinaal bewezen door byte-identieke
   reproductie met het oude algoritme. De hermunting is daarom op `0c6fac5` gedaan.
2. **AC5 telt 3 nieuwe bevindingen, de run vindt er 4**: de vierde
   (`ReturnCount`/`DeploySubtaskHandler.kt` r127) is een echte, na de refinement op main ontstane
   bevinding (SF-1560). Hij verbergen kan alleen door de baseline op te rekken, wat expliciet
   verboden is.

## Vangnet-uitkomst

- `python3 -m unittest discover quality` → `Ran 18 tests … OK` (exit 0).
- `mvn -B --no-transfer-progress clean verify` vanaf de repo-root → **BUILD SUCCESS, exit 0**,
  0 failures / 0 errors (685 + 74 + 60 + 52 + 50 + 16 tests).
  De eerste poging viel om op een surefire-forkflake (`The forked VM terminated without properly
  saying goodbye … Process Exit Code: 0`, crashed test `FactoryApiControllerTest`) zonder enige
  test-failure; die klasse draait los groen (5/5) en de volledige herdraai is groen. Er is geen
  Kotlin-code gewijzigd in deze story, dus dit is omgevingsruis, geen regressie.
- `bash quality/run.sh` → exit 1 met de vier bevindingen hierboven; dat is de door de story
  gewenste eindtoestand (de gate mag niet groen gemaakt worden).

## Aangepaste specs in `docs/factory/`

Geen. Deze story raakt alleen `quality/` (ratchet-script, tests, baseline, gate-volgorde); de
functionele en technische specificaties beschrijven dit onderdeel niet, en er is geen
build-/testcommando gewijzigd (`quality/run.sh` blijft dezelfde ingang vanuit
`tools/verify-repository`; `.factory/verification.yaml` bevat de ratchet niet en is ongewijzigd).

## Review SF-1664 (01-08-2026)

Beoordeeld: volledige story-diff `git diff main...HEAD` (ratchet.py, test_ratchet.py, baseline,
run.sh, worklog — buiten `quality/` alleen dit worklog, bevestigd via `--stat`).

Zelf machinaal herverifieerd (niet alleen de claims uit het worklog overgenomen):

- AC2: over de 10 `BLOCKING_RULES` is de multiset (module, rule, path) van oude v1-baseline en
  hermunte v2-baseline **exact gelijk** — 208 findings, 140 distincte tupels, per-tupel gelijk,
  geen enkel verschil. Ook over álle 744 findings gelijk. `modules` en `blockingRules` ongewijzigd.
- AC3: precies 1 suppressie, identiek qua path, regel (291) en text.
- AC4: `schemaVersion: 2`; elke finding heeft `fingerprint`, `message` én `shape`.
- AC1: `python3 -m unittest discover quality` → Ran 18 tests, OK (zelf gedraaid).
- Hermunt-commit: `0c6fac5` raakt daadwerkelijk `quality/baselines/plan-07-ratchet.json`;
  `a6b3132` is een README/diagram-docscommit die het bestand niet raakt. De correctie van de
  in de description gepinde commit is daarmee onderbouwd, niet aangenomen.
- Specs: geen enkele doc in `docs/factory/` beschrijft de ratchet; `.factory/verification.yaml`
  ongewijzigd; `tools/verify-repository` r31 blijft dezelfde ingang. Geen spec-inconsistentie.

[info] `compare()` groepeert renames op (module, rule, fingerprint) zonder path. Door de grovere
message-normalisatie stijgt het aantal duplicaat-groepen in de baseline van 83 naar 85 (231 → 246
extra entries). Dat verhoogt het theoretische risico op een onterechte rename-koppeling licht, maar
de exacte match telt per path met een `Counter` en meerduidige groepen falen gesloten
(`ambiguous`), dus een nieuwe bevinding kan hierdoor niet stil wegvallen.
`test_ambiguous_rename_is_red` en `test_equal_total_finding_swap_is_red` bewaken dit.

[info] `normalize_message()` matcht quotes met `['\`][^'\`]+['\`]`; een message met een losse
apostrof (bv. "doesn't") kan daardoor met een verderop staand quoteteken paren. Detekt-messages in
deze baseline kennen dat patroon niet; geen actie nodig.

Openstaand voor de PO: AC5 eist precies 3 nieuwe bevindingen, de run levert er 4
(`ReturnCount` in `DeploySubtaskHandler.kt` r127, ontstaan na de refinement via SF-1560). Dit is
een AC-wijziging die de developer terecht heeft gemeld in plaats van weggewerkt.

### Review-ronde 2 (SF-1664, 01-08-2026) — akkoord

De PO heeft beide openstaande punten met "ja" beantwoord (issue-comment 2144): hermunten op
`0c6fac5` in plaats van `a6b3132` is akkoord, en AC5 wordt gelezen als **4** echte nieuwe
blokkerende bevindingen (de vierde is `ReturnCount` in `DeploySubtaskHandler.kt` r127 uit SF-1560).
Daarmee zijn er geen openstaande vragen meer.

Opnieuw zelf machinaal herbevestigd op de huidige HEAD:

- multiset (module, rule, path) over de 10 `BLOCKING_RULES`: 208 findings / 140 distincte tupels,
  oud vs. nieuw **exact gelijk** (verschil-Counter leeg); ook over álle 744 findings gelijk;
  `modules` en `blockingRules` ongewijzigd (AC2);
- 1 suppressie, identiek qua path, regel 291 en text (AC3);
- `schemaVersion: 2`, 0 findings zonder `message`/`shape` (AC4);
- `python3 -m unittest discover quality` → Ran 18 tests, OK (AC1);
- `git diff main...HEAD --stat` raakt buiten `quality/` alleen dit worklog (AC7);
- geen doc in `docs/factory/`, `tools/verify-repository`, `.factory/nightly/quality/prompt.md` of
  `.github/workflows/verify.yml` beschrijft het baselineformaat → geen spec-inconsistentie.

[info] Duplicaat-(module, rule, fingerprint)-groepen binnen de blocking rules stijgen van 8 naar 10
door de grovere normalisatie. `compare()` telt exacte matches per path met een `Counter` en laat
meerduidige groepen gesloten falen (`ambiguous`), dus een nieuwe bevinding kan hierdoor niet stil
wegvallen. Geen blocker.

Besluit: goedgekeurd. De rode `quality/run.sh` (exit 1, 4 echte bevindingen, 0 ambiguous, 0 nieuwe
suppressies) is de door de story gewenste eindtoestand.

## Test SF-1665 (01-08-2026) — story-brede test

Zelf gedraaid en machinaal nagerekend op HEAD `fad4860` (claims uit het worklog niet overgenomen):

- **AC1**: `python3 -m unittest discover quality` → `Ran 18 tests … OK`, exit 0.
- **AC2**: multiset (module, rule, path) over de 10 `BLOCKING_RULES` van `main`-baseline (v1) vs.
  hermunte baseline (v2): **exact gelijk**, 208 blokkerende findings vóór én ná, verschil-Counter
  leeg. Ook over álle 744 findings gelijk; `modules` en `blockingRules` ongewijzigd.
- **AC3**: precies 1 suppressie, identiek qua path, regel 291 en text (`@Suppress("unused")` in
  `BridgeRequestHandler.kt`).
- **AC4**: `schemaVersion: 2`; 0 van de 744 findings mist `fingerprint`, `message` of `shape`.
- **AC5/AC6**: `bash quality/run.sh` volledig gedraaid (incl. Detekt over de vijf modules) →
  **exit 1**, `qualityrun/2026-08-01T03-50-31/delta.json`: `findingCount: 756`,
  `suppressionCount: 1`, `ambiguous: []`, `newSuppressions: []`, `renamed: 0`, `resolved: 0`, en
  **4** nieuwe blokkerende bevindingen — de 3 uit AC5 (`AuditSeeding.kt`,
  `TelegramAuditQuestionService.kt`, `AgentCli.kt`) plus de door de PO (comment 2144) geaccordeerde
  vierde `ReturnCount` in `DeploySubtaskHandler.kt`. Rood is hier de gewenste eindtoestand.
- **Gate-gedrag `quality/run.sh` (scope-punt 5)**: gedragstest in een wegwerp-kopie
  (`/tmp/gatecheck.*`, na afloop verwijderd) met één opzettelijk falende test → exit 1 met
  "Ratchet-unittests rood — Detekt-stap overgeslagen"; alleen `ratchet-tests.log` geschreven, géén
  `detekt-console.log`. De harde exit vóór Detekt werkt dus echt.
- **AC7**: `git diff main...HEAD --stat` raakt buiten `quality/` alleen dit worklog.
  `qualityrun/quality-score.json` wordt — net als vóór deze story (zie de oudere run
  `qualityrun/2026-07-29T16-59-04`, die het bestand óók niet bevat) — niet geschreven omdat
  `ratchet.py check` bij een rode ratchet met `set -e` afbreekt vóór de score-stap. De
  score-definitie en -telling (756 findings + 1 suppressie) zijn ongewijzigd; fingerprinting raakt
  alleen hashes.
- **AC8**: worklog bevat de bevindingen én de uitkomst van criterium 2 en 3.
- `tools/audit-documentation` → `documentation-audit/v1: PASS`, exit 0.

Geen preview-omgeving/browser beschikbaar en geen UI-wijziging in deze story; screenshots niet van
toepassing. De story raakt geen Kotlin-productiecode, dus `repository-maven-verify` valt buiten de
`pathPrefixes` van `.factory/verification.yaml`; het revisiegebonden vangnet draait de harness na
deze run.

Besluit tester: goedgekeurd.
