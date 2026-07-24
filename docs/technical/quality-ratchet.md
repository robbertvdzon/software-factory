# Quality-ratchet

`./quality/run.sh` meet alle vijf Mavenmodules met Kotlin-productiecode en vergelijkt structurele
Detektbevindingen met `quality/baselines/plan-07-ratchet.json`. De auditnulmeting onder
`docs/verbetertraject-2026-07/baselines/quality-cc7cac2.json` blijft ongewijzigd.

De gate blokkeert nieuwe complexiteitsbevindingen en nieuwe of vervangende suppressies. Een finding
wordt geïdentificeerd door rule-id, genormaliseerde melding en genormaliseerde bronvorm. Daardoor
blijven een zuivere file-, package- en symboolrename neutraal. Meerdere mogelijke renamematches zijn
ambigu en falen gesloten. Een ontbrekende module, een ontbrekend rapport, ongeldige baseline of
Detektfout retourneert eveneens non-zero.

Het enige commando is:

```bash
./quality/run.sh
```

De mens- en machineleesbare delta staat daarna onder `qualityrun/<timestamp>/`. De baseline wordt
niet automatisch herschreven; een echte schuldreductie wordt in een afzonderlijke, gereviewde
baselinekrimp vastgelegd.

Draait niet (meer) in GitHub Actions — sinds 2026-07-24 alleen nog handmatig vanaf een laptop.
Reden: de ratchet stond vaak rood op ruis die niets met de betreffende PR te maken had, en dat
leidde eerder (SF-1075) tot een subtiele regressie waarbij een groene merge alsnog geen nieuwe
dashboard-images opleverde (zie de comment bij `repository-verification` in
`.github/workflows/verify.yml`).
