# Quality-ratchet

`./quality/run.sh` meet alle vijf Mavenmodules met Kotlin-productiecode en vergelijkt structurele
Detektbevindingen met `quality/baselines/plan-07-ratchet.json`. De auditnulmeting onder
`docs/verbetertraject-2026-07/baselines/quality-cc7cac2.json` blijft ongewijzigd.

De gate blokkeert nieuwe complexiteitsbevindingen en nieuwe of vervangende suppressies. Een finding
wordt geïdentificeerd door rule-id, genormaliseerde melding en genormaliseerde bronvorm. Daardoor
blijven een zuivere file-, package- en symboolrename neutraal. Meerdere mogelijke renamematches zijn
ambigu en falen gesloten. Een ontbrekende module, een ontbrekend rapport, ongeldige baseline of
Detektfout retourneert eveneens non-zero.

## Fingerprint-normalisatie

`fingerprint()` hasht `rule|genormaliseerde message|genormaliseerde bronregel`. `normalize_message()`
haalt in deze volgorde de ruis uit de Detekt-melding die een ongewijzigd knelpunt anders als nieuw
liet tellen (SF-1486):

1. gequote symbolen (`'naam'`, `` `naam` ``) worden `'ID'` — eerst, zodat een gequote symbool nooit
   als kale identifier overleeft;
2. elk haakjespaar wordt samengevouwen tot één marker `(ARGS)`, van binnen naar buiten zodat ook
   geneste haakjes meegaan. Daarmee tellen zowel de volledige parameterlijst van
   `LongParameterList` als metriek-haakjes (`(complexity: 23)`, `is too long (89)`) niet meer mee;
3. resterende ongequote getallen worden `NUM` (`has 3 return statements` → `has NUM return
   statements`).

Identifiers búiten de haakjes blijven staan. Dat is essentieel: `compare()` groepeert renames op
(module, rule, fingerprint) zonder path, dus twee verschillende functies in hetzelfde bestand moeten
een verschillende fingerprint houden. `normalize_shape()` op de bronregel is ongewijzigd.

Een functie die een regel langer wordt of er een parameter bij krijgt, houdt hierdoor dezelfde
fingerprint; alleen echt nieuwe knelpunten geven nog rood.

## Baselineformaat (`schemaVersion 2`)

`collect()` schrijft `schemaVersion: 2` en slaat per finding naast `fingerprint` ook de
genormaliseerde `message` en de genormaliseerde bronregel-`shape` op, zodat een volgende
algoritmewijziging uit de baseline zelf herleidbaar is.

`compare()` accepteert uitsluitend `schemaVersion 2`. Een `schemaVersion 1`-baseline (of een
ontbrekende versie) faalt met een expliciete fout die naar hermunten verwijst — nooit stille
acceptatie, want die baseline draagt fingerprints van het oude algoritme.

Hermunten is alleen toegestaan bij een algoritme-/formaatwijziging, niet om een regressie te
verbergen. De baseline wordt dan opnieuw gecollect op de tree waar hij aantoonbaar vandaan komt:

```bash
python3 quality/ratchet.py collect --root <worktree> \
  --output quality/baselines/plan-07-ratchet.json
```

met als bewijsplicht dat de multiset van (module, rule, path) over de `BLOCKING_RULES` en de
suppressies vóór én ná exact gelijk zijn. Alleen de hashes mogen veranderen, de verzameling
bevindingen niet.

## Draaien

Het enige commando is:

```bash
./quality/run.sh
```

Dat script draait eerst `python3 -m unittest discover quality` (de ratchet-unittests, log in
`qualityrun/<timestamp>/ratchet-tests.log`) en stopt bij een rode test met exit 1 vóór de
Detekt-stap. De ratchet-tests lopen daardoor automatisch mee in stap
`repository-quality-ratchet` van `tools/verify-repository`.

De mens- en machineleesbare delta staat daarna onder `qualityrun/<timestamp>/`. De baseline wordt
niet automatisch herschreven; een echte schuldreductie wordt in een afzonderlijke, gereviewde
baselinekrimp vastgelegd.

Draait niet (meer) in GitHub Actions — sinds 2026-07-24 alleen nog handmatig vanaf een laptop.
Reden: de ratchet stond vaak rood op ruis die niets met de betreffende PR te maken had, en dat
leidde eerder (SF-1075) tot een subtiele regressie waarbij een groene merge alsnog geen nieuwe
dashboard-images opleverde (zie de comment bij `repository-verification` in
`.github/workflows/verify.yml`).
