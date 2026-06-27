# Nachtelijke jobs (`.factory/nightly/`)

Elke submap hier is één **nachtelijke job**: een autonome verbetertaak die de Software Factory
's nachts oppakt en als *silent* story verwerkt (zie SF-335) — zonder interactie, en bij echte
onduidelijkheid gaat de story in error i.p.v. te wachten op een mens.

## Structuur

```
.factory/nightly/<job-naam>/
  job.yaml    # metadata (titel, aan/uit, AI-instellingen)
  story.md    # de story-beschrijving die de agent uitvoert
```

## job.yaml

| veld        | verplicht | uitleg |
|-------------|-----------|--------|
| `title`     | ja        | titel van de aangemaakte story |
| `enabled`   | ja        | `false` = job overslaan zonder hem te verwijderen |
| `silent`    | ja        | altijd `true` voor nachtelijke jobs (autonoom; vragen → error) |
| `aiSupplier`| nee       | bv. `claude`; anders de default van de factory |
| `aiModel`   | nee       | specifiek model |
| `priority`  | nee       | voor latere volgorde-bepaling (nu nog niet gebruikt) |

De **repo** wordt hier niet gezet: die volgt uit de repo waarin deze map staat.
Het **ritme** (nachtelijk) volgt uit deze `nightly/`-map.

## Regel voor álle nachtelijke jobs

Functioneel niets veranderen. Zolang alle tests slagen mag de job autonoom afgerond worden;
faalt iets, of is er een echte inhoudelijke vraag, dan gaat de story in error.
