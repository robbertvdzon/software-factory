# Consistentie: afwijkende patronen gladstrijken

Zoek inconsistente patronen in de code en breng ze in lijn met de norm in de codebase.

## Scope
- Afwijkende naamgeving, structuur, error-handling, logging of API-conventies t.o.v. de rest.
- Plekken waar hetzelfde probleem op meerdere manieren is opgelost: trek ze gelijk.

## Randvoorwaarden
- Puur consistentiewerk: het **functionele gedrag blijft exact hetzelfde**.
- Alle bestaande tests moeten blijven slagen.
- De integratietests zijn je vangnet: pas ze **niet** aan. Moet je een integratietest wijzigen
  om je werk groen te krijgen, dan verander je gedrag → ga in error.
- Twijfel je of een wijziging gedrag verandert? Doe 'm dan niet, of ga in error.
