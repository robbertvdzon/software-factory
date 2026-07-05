# Code-kwaliteit verbeteren

Verbeter de kwaliteit van de code.

## Scope
- Pas de code toe aan de SOLID-principes; verbeter leesbaarheid en onderhoudbaarheid
  (naamgeving, dode code, duplicatie, te lange functies).
- Los warnings en deprecations uit de Maven-output op.

## Randvoorwaarden
- Puur kwaliteits-/refactorwerk: het **functionele gedrag blijft exact hetzelfde**.
- Alle bestaande tests moeten blijven slagen.
- De integratietests zijn je vangnet: pas ze **niet** aan. Moet je een integratietest wijzigen
  om je refactor groen te krijgen, dan verander je gedrag → ga in error.
- Twijfel je of een wijziging gedrag verandert? Doe 'm dan niet, of ga in error.
