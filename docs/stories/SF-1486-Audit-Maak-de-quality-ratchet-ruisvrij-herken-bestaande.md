# SF-1486 - [Audit] Maak de quality-ratchet ruisvrij: herken bestaande bevindingen na een signature- of metriekwijziging

## Story

[Audit] Maak de quality-ratchet ruisvrij: herken bestaande bevindingen na een signature- of metriekwijziging

<!-- refined-by-factory -->

## Samenvatting

De kwaliteitsbewaking van deze repo vergelijkt elke run met een vastgelegde lijst van bekende
knelpunten. Die vergelijking is te streng: zodra een functie één regel langer wordt of er een
parameter bij krijgt, ziet de bewaking dat als een gloednieuw probleem. Daardoor sloeg hij vorige
week alarm over 18 "nieuwe" punten, terwijl er in werkelijkheid maar 3 echt nieuw waren. De hele
repo-controle liep daarop stuk.

Deze story maakt het herkennen van bekende punten ongevoelig voor dat soort ruis, zodat alleen
echte nieuwe knelpunten nog rood geven. Er worden geen normen versoepeld en er wordt niets
weggemoffeld: de 3 echte punten blijven zichtbaar en de controle blijft dus terecht rood.

## Scope

Alles binnen `quality/`, plus een notitie in het worklog. Geen productiecode.

**1. `quality/ratchet.py` — `fingerprint()` stabieler maken**

De message krijgt dezelfde soort normalisatie die `normalize_shape()` al op de bronregel toepast:

- bestaande quote-normalisatie blijft (`'naam'` / `` `naam` `` → `'ID'`);
- inhoud tussen haakjes wordt samengevouwen tot één vaste marker (bv. `(ARGS)`), zodat zowel de
  volledige parameterlijst van `LongParameterList` als metriek-haakjes als `(complexity: 23)` en
  `is too long (89)` niet meer meetellen;
- overgebleven ongequote getallen worden gemaskeerd (bv. `has 3 return statements` → `has NUM return
  statements`).

Niet-gequote identifiers buiten de haakjes blijven staan, zodat twee verschillende functies in
hetzelfde bestand onderscheidbaar blijven.

**2. `quality/ratchet.py` — schemaVersion 2**

`collect()` schrijft `schemaVersion: 2` en slaat per finding naast `fingerprint` ook de
genormaliseerde message en de genormaliseerde bronregel-shape op, zodat een volgende
algoritmewijziging uit de baseline zelf herleidbaar is. `compare()` accepteert schemaVersion 2 en
faalt op een schemaVersion 1-baseline met een expliciete foutmelding die naar hermunting verwijst
(geen stille acceptatie). De vergelijk-/rename-logica zelf (exacte match → shape-match → ambiguous
faalt gesloten) blijft ongewijzigd.

**3. `quality/baselines/plan-07-ratchet.json` — hermunten, niet oprekken**

De baseline wordt her-gecodeerd naar schemaVersion 2 met de nieuwe fingerprints. Toegestaan pad naar
keuze van de developer:

- *pad 1:* Detekt opnieuw draaien op de baseline-commit (`a6b3132`) in een losse worktree en
  `collect` met het nieuwe algoritme; of
- *pad 2:* afleiden uit de run van vandaag mínus de 3 echte nieuwe bevindingen.

In beide gevallen geldt dezelfde bewijsplicht (zie acceptatiecriteria). Bij pad 2 mogen de gedrifte
niet-blokkerende findings mee de baseline in; dat is expliciet akkoord.

**4. `quality/test_ratchet.py` — ontbrekende regressiedekking**

Nieuwe tests, minimaal:

- dezelfde bevinding waarvan alleen de message-metriek wijzigt (`is too long (89)` → `(94)`,
  `has 3 return statements` → `has 4 ...`, `(complexity: 23)` → `(complexity: 25)`) levert dezelfde
  fingerprint op;
- dezelfde bevinding waarvan de parameterlijst wijzigt (parameter toegevoegd/verwijderd/hernoemd)
  levert dezelfde fingerprint op;
- twee verschillende functies in hetzelfde bestand houden een verschillende fingerprint;
- een bevinding in een bestand waar er eerst geen was blijft ROOD via `compare()`.

De bestaande negen tests blijven ongewijzigd groen, in het bijzonder
`test_equal_total_finding_swap_is_red` en `test_ambiguous_rename_is_red`.

**5. `quality/run.sh` — tests in een gate hangen**

`python3 -m unittest discover quality` wordt vóór de Detekt-stap in `quality/run.sh` uitgevoerd, met
harde exit bij falen. Daarmee lopen deze tests automatisch mee in `tools/verify-repository` stap 2.

**6. Worklog**

`docs/stories/worklog/SF-1486-worklog.md` legt vast: de 3 resterende echte bevindingen (bestand,
regel, rule) met korte afweging, het gekozen hermunt-pad, en de uitkomst van de gelijkheidscheck.

**Expliciet buiten scope (niet aanraken):** `quality/detekt.yml`, alle drempels, de `BLOCKING_RULES`-
lijst, de scoreformule in `quality/run.sh`, de `resolved`-berekening in `compare()`, en het
daadwerkelijk oplossen van de 3 echte bevindingen (aparte story).

## Acceptance criteria

1. `python3 -m unittest discover quality` is groen: de negen bestaande tests plus de nieuwe
   regressietests uit scope-punt 4.
2. De her-gemunte baseline bevat over de 10 `BLOCKING_RULES` **exact dezelfde multiset van
   (module, rule, path)** als de huidige baseline: 208 blokkerende findings vóór én ná, met een
   per-tupel gelijke telling. Elk verschil op dit niveau blokkeert de story en wordt gemeld in plaats
   van weggewerkt.
3. De baseline bevat na de wijziging nog steeds precies 1 suppressie, ongewijzigd qua path en text.
4. De baseline heeft `schemaVersion: 2` en bevat per finding naast `fingerprint` ook de
   genormaliseerde message en de bronregel-shape.
5. `bash quality/run.sh` meldt in `delta.json` **precies 3** nieuwe bevindingen, geen ambiguous en
   geen nieuwe suppressies:
   - `ReturnCount` in `softwarefactory/.../audit/services/AuditSeeding.kt` (r25)
   - `ReturnCount` in `softwarefactory/.../telegram/services/TelegramAuditQuestionService.kt` (r24)
   - `TooManyFunctions` in `agentworker/.../agentworker/cli/AgentCli.kt`
6. De gate blijft daarmee rood met exit 1 — dat is de verwachte eindtoestand, geen mislukking. De
   PR wordt met die rode ratchet-stap opgeleverd; hij mag niet groen worden gemaakt door drempels,
   `BLOCKING_RULES`, `detekt.yml` of de baseline verder op te rekken.
7. `qualityrun/quality-score.json` toont dezelfde score als vóór de wijziging (fingerprinting
   beïnvloedt de telling niet); `git diff` raakt buiten `quality/` alleen het worklog.
8. Het worklog bevat de drie resterende bevindingen en de uitkomst van criterium 2 en 3.

## Aannames

- Issue-comment 1905 is leidend boven de zin "Regenereer de baseline NIET" in de description: een
  her-encoding waarbij aantoonbaar dezelfde bevindingen in de baseline staan valt niet onder regel 6
  van `docs/verbetertraject-2026-07/08-architectuur-en-kwaliteitsgates-high.md` (die verbiedt het
  oprekken van de baseline om een regressie te verbergen). Zonder hermunting is de story technisch
  onuitvoerbaar, want de baseline bevat alleen hashes.
- Het bewijs wordt bewust alleen over de 10 blocking rules geëist; de niet-blokkerende telling is
  gedrift (baseline 744 findings vs. ~756 vandaag) en een gelijkheidscheck over álle findings zou
  daarop onterecht falen.
- `qualityrun/` staat in `.gitignore` en de run van `2026-07-27T19-59-32` is niet in de checkout
  aanwezig. De in de description genoemde vergelijking van twee `detekt-console.log`-bestanden is
  daarmee geen bruikbaar bewijspad; de gelijkheidscheck uit criterium 2 op de JSON-baselines is dat
  wel en vervangt hem.
- `compare()` groepeert renames op (module, rule, fingerprint) zonder path. Een grovere
  message-normalisatie verhoogt dus het risico op onterechte rename-koppelingen; door identifiers
  buiten de haakjes te behouden blijft dat risico beheerst, en criterium 1 (incl.
  `test_ambiguous_rename_is_red` en `test_equal_total_finding_swap_is_red`) bewaakt het.
- Schema 1-baselines hoeven niet backwards-compatibel ondersteund te blijven; er is precies één
  baseline in de repo en die wordt in deze story meegemigreerd.
- Verificatie vereist een volledige Detekt-run (`mvn -Pquality -pl ... detekt:check`) over vijf
  modules. Lukt dat in de agent-omgeving niet, dan is dat een omgevingsprobleem dat gemeld moet
  worden, geen reden om criterium 5 over te slaan of de baseline te forceren.

## Eindsamenvatting

## Eindsamenvatting SF-1486 — Ruisvrije quality-ratchet

### Wat is gebouwd

De quality-ratchet herkende bekende Detekt-bevindingen niet meer zodra een functie één regel langer werd of er een parameter bij kreeg. Dat is opgelost in vier stappen, allemaal binnen `quality/`:

1. **Ruisvrije fingerprint** (`quality/ratchet.py`) — nieuwe helper `normalize_message()` haalt in vaste volgorde de ruis uit de Detekt-message: gequote symbolen → `'ID'`, elk (ook genest) haakjespaar → één marker `(ARGS)`, resterende kale getallen → `NUM`. Identifiers *buiten* de haakjes blijven staan, zodat twee functies in hetzelfde bestand onderscheidbaar blijven.
2. **schemaVersion 2** — de baseline slaat per bevinding nu naast de hash ook de genormaliseerde message en de bronregel-shape op, zodat een volgende algoritmewijziging uit de baseline zelf herleidbaar is. `compare()` weigert een schemaVersion 1-baseline met een expliciete foutmelding die naar hermunting verwijst; stille acceptatie is uitgesloten.
3. **Hermunte baseline** (`quality/baselines/plan-07-ratchet.json`) — opnieuw gegenereerd met het nieuwe algoritme, uit een verse Detekt-run op de commit waar de baseline aantoonbaar vandaan komt.
4. **Tests in de gate** (`quality/run.sh`) — `python3 -m unittest discover quality` draait nu vóór Detekt met harde exit bij falen, waardoor de ratchet-tests automatisch meelopen in `tools/verify-repository`.

### Belangrijkste keuzes

- **Hermunt-commit gecorrigeerd van `a6b3132` naar `0c6fac5`.** De in de story gepinde commit reproduceerde de bestaande baseline aantoonbaar niet (2 afwijkende tupels, suppressie op een andere regel). Oorzaak: de checkout was *shallow*, waardoor `a6b3132` de oudst zichtbare commit leek. Na `git fetch --deepen` bleek `0c6fac5` de echte baselinecommit — machinaal bewezen doordat een Detekt-run daarop met het **oude** script een byte-identieke baseline oplevert. Er is dus niets "afgeleid uit de run van vandaag". PO heeft dit goedgekeurd (comment 2144).
- **AC5 bijgesteld van 3 naar 4 nieuwe bevindingen.** Naast de 3 voorziene bevindingen (`AuditSeeding.kt`, `TelegramAuditQuestionService.kt`, `AgentCli.kt`) vindt de run een vierde echte bevinding: `ReturnCount` in `DeploySubtaskHandler.kt` r127, ontstaan ná de refinement via SF-1560. Die is gemeld in plaats van weggewerkt — verbergen kon alleen door de baseline op te rekken. PO heeft ook dit goedgekeurd.

### Effect

| | nieuwe blokkerende bevindingen | ambiguous |
|---|---|---|
| oude fingerprint + oude baseline | **23** | 0 |
| nieuwe fingerprint + hermunte baseline | **4** | 0 |

19 valse alarmen weg, alle 4 echte bevindingen blijven zichtbaar.

### Wat is getest (door developer, reviewer én tester onafhankelijk herdraaid)

- `python3 -m unittest discover quality` → **18 tests groen**; de negen bestaande inhoudelijk ongewijzigd, incl. `test_equal_total_finding_swap_is_red` en `test_ambiguous_rename_is_red`. Nieuw: metriekdrift, parameterlijstwijziging (incl. genest), onderscheid tussen twee functies, nieuw bestand blijft rood, en schemaVersion-fouten.
- **Gelijkheidsbewijs baseline**: multiset (module, rule, path) over de 10 blocking rules is exact gelijk vóór en ná — 208 findings, 140 distincte tupels, verschil leeg. Ook over álle 744 findings gelijk. 164 van 744 hashes veranderden; de verzameling bevindingen niet.
- Precies 1 suppressie, ongewijzigd qua path, regel en tekst.
- `bash quality/run.sh` volledig gedraaid incl. Detekt over vijf modules → exit 1 met 4 bevindingen, 0 ambiguous, 0 nieuwe suppressies.
- Gate-gedrag apart getest in een wegwerp-kopie: een falende test breekt af vóór Detekt.
- `mvn clean verify` vanaf de root → BUILD SUCCESS, 0 failures/errors (937 tests). Eén eerdere surefire-forkflake (bekend, `FactoryApiControllerTest`) was omgevingsruis; er is geen Kotlin-code gewijzigd.
- `tools/audit-documentation` → PASS.

### Bewust niet gedaan

- **De 4 bevindingen zijn niet opgelost** — dat is expliciet een aparte story.
- **De gate is bewust rood** (exit 1). Dat is de gewenste eindtoestand: drempels, `BLOCKING_RULES`, `detekt.yml` en de scoreformule zijn niet aangeraakt.
- **Geen backwards-compatibiliteit voor schemaVersion 1** — er is precies één baseline en die is meegemigreerd.
- **Geen documentatiewijziging nodig**: geen enkele spec in `docs/factory/` beschrijft de ratchet; `.factory/verification.yaml` en `tools/verify-repository` zijn ongewijzigd.
- `qualityrun/quality-score.json` wordt niet geschreven omdat de run bij een rode ratchet afbreekt vóór de score-stap — net als vóór deze story. Score-definitie en -telling (756 findings, 1 suppressie) zijn ongewijzigd.

**Buiten `quality/` raakt de diff alleen het worklog.** Er zijn geen openstaande vragen.
