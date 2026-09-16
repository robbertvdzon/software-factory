# SF-3297 - Worklog

## Story in eigen woorden

`tools/verify-repository` stopte al op stap 1 (`repository-contract-tests`) met
`FAIL: dataregels staan niet alfabetisch op pad`. Die melding was onjuist:
`architecture/composition-root-boundaries.txt` is correct byte-gesorteerd.

`tools/test-check-composition-roots` vergelijkt de *opgeslagen* ordening van de dataregels met een
*verse* `sort`, en die `sort` was niet op locale gepind. In de C-locale geldt `'/'` (0x2F) < `'h'`
(0x68), dus `.../git/services/ProcessRunner.kt` komt vóór `.../github/clients/GitHubCliClient.kt` —
precies zoals in het bestand. glibc's `en_US.UTF-8`-collatie negeert interpunctie op het primaire
niveau en draait dat paar om. Omdat `verify-repository` met `set -e` op de eerste rode stap afbreekt,
kwam daardoor geen enkele andere stap meer aan bod.

Oplossing: één regel `export LC_ALL=C` per script, direct ná `set -euo pipefail`. Het register zelf
verandert niet; de controle wordt naar het bestand toe gepind, niet andersom.

## Subtaak SF-3306 (development)

### Checklist

[x]: story, beide scripts en het register gelezen
[x]: faalgedrag vóór de fix gereproduceerd met de echte registerpaden
[x]: `export LC_ALL=C` + toelichting in `tools/test-check-composition-roots`
[x]: `export LC_ALL=C` + toelichting in `tools/check-composition-roots` (preventief)
[x]: gecontroleerd dat de toelichtingen geen inhoudscontrole van de contracttest triggeren
[x]: alle acceptatiecommando's uit de story gedraaid, in beide locale-richtingen
[x]: de vier contracttests van stap 1 achter elkaar gedraaid (exit 0)

### Rood-bewijs vóór de aanpassing

In deze omgeving (`LANG=en_US.UTF-8`, `locale -a` toont `en_US.utf8`):

```
$ bash tools/test-check-composition-roots
FAIL: dataregels staan niet alfabetisch op pad          # exit 1
```

### Wat ik gedaan heb en waarom

1. `tools/test-check-composition-roots`: `export LC_ALL=C` direct ná `set -euo pipefail`, vóór elk
   sorterend of vergelijkend commando, met een toelichting in de stijl van de bestaande
   commentaarregels. `export` (en niet een aanroep-lokale `LC_ALL=C sort`) is bewust gekozen: zo
   draaien álle `sort`/`comm`/`diff`-stappen in dezelfde ordening, inclusief het kindproces
   `tools/check-composition-roots` dat de contracttest zelf aanroept.
2. `tools/check-composition-roots`: dezelfde regel direct ná `set -euo pipefail`, met een
   eenregelige toelichting. Dit script was al groen onder elke locale (het sorteert beide kanten
   vers, dus de collatie valt weg); dit is puur preventief en houdt beide scripts in dezelfde
   ordening voor het geval het script los wordt aangeroepen.

Valkuil die ik expliciet heb nagelopen: de contracttest inspecteert ook de *inhoud* van de bewaakte
scripts met `grep -v '^[[:space:]]*#' … | grep -qE '(^|[^-[:alnum:]])rg[[:space:]]'`. Die filter
haalt hele commentaarregels weg, en de toegevoegde regels bevatten sowieso geen losstaand `rg`. De
contracttest is ná het bewerken opnieuw gedraaid en is groen.

Het register is niet aangeraakt: geen enkele dataregel verandert.

### Verificatie ná de aanpassing

| Commando | Resultaat |
|---|---|
| `bash -n tools/test-check-composition-roots` | groen |
| `bash -n tools/check-composition-roots` | groen |
| `LANG=en_US.UTF-8 bash tools/test-check-composition-roots` (zonder eigen `LC_ALL`) | `composition-root contract: PASS`, exit 0 |
| `LC_ALL=en_US.UTF-8 bash tools/test-check-composition-roots` | `composition-root contract: PASS`, exit 0 |
| `LC_ALL=C bash tools/test-check-composition-roots` | `composition-root contract: PASS`, exit 0 |
| `LANG=en_US.UTF-8 ./tools/check-composition-roots` | `composition-root-boundaries/v1: PASS (19 exact paths)`, exit 0 |
| `LC_ALL=C ./tools/check-composition-roots` | `composition-root-boundaries/v1: PASS (19 exact paths)`, exit 0 |
| de vier contracttests van stap 1 achter elkaar | alle vier groen, exit 0 |

De laatste regel is het bewijs voor stap 1 `repository-contract-tests` van `tools/verify-repository`:

```
verify-repository contract v1 is valid
documentation audit contract: PASS
branch-protection audit contract is valid
composition-root contract: PASS
exit=0
```

`tools/verify-repository` faalt daarna alsnog op stap 3 (`./quality/run.sh`); dat staat in het
auditrapport van 2026-09-16, valt buiten scope van deze story en is ongewijzigd door deze diff.

### Buiten scope gelaten

- `architecture/composition-root-boundaries.txt` — ongewijzigd, is al byte-gesorteerd.
- `tools/verify-repository` stap 3 (`./quality/run.sh`) en stap 6 (`backend-mini-reactor-smoke`) —
  elk een eigen menselijke afweging.
- `.factory/verification.yaml` en GitHub Actions — welke gate leidend is, is een menselijke keuze.
- `docs/` — geen enkel document beschrijft de sorteer- of locale-aanname van deze scripts, dus er is
  geen documentatiedrift om mee te nemen.
