# Quality-ratchet

`./quality/run.sh` meet alle vijf Mavenmodules met Kotlin-productiecode en vergelijkt structurele
Detektbevindingen met `quality/baselines/plan-07-ratchet.json`. De auditnulmeting onder
`docs/verbetertraject-2026-07/baselines/quality-cc7cac2.json` blijft ongewijzigd.

De gate blokkeert nieuwe complexiteitsbevindingen en nieuwe of vervangende suppressies. Een finding
wordt geïdentificeerd door rule-id, genormaliseerde melding en genormaliseerde bronvorm. Daardoor
blijven een zuivere file-, package- en symboolrename neutraal. Meerdere mogelijke renamematches zijn
ambigu en falen gesloten. Een ontbrekende module, een ontbrekend rapport, ongeldige baseline of
Detektfout retourneert eveneens non-zero.

Lokaal en in CI is het enige commando:

```bash
./quality/run.sh
```

De mens- en machineleesbare delta staat daarna onder `qualityrun/<timestamp>/`. GitHub Actions
publiceert deze map ook wanneer de ratchet faalt. De baseline wordt niet automatisch herschreven;
een echte schuldreductie wordt in een afzonderlijke, gereviewde baselinekrimp vastgelegd.
