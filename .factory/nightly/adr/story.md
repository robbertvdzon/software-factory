# ADR-naleving herstellen

Controleer of de code de vastgelegde Architecture Decision Records (ADR's) nog volgt, en herstel
afwijkingen waar dat veilig kan.

## Scope
- Loop de ADR's na (bv. in `docs/adr/`) en vergelijk met de code.
- Breng de code waar mogelijk weer in lijn met de ADR.

## Randvoorwaarden
- Je mag de code aanpassen, maar het **functionele gedrag blijft exact hetzelfde**.
- Alle bestaande tests moeten blijven slagen.
- Vereist naleving van een ADR een functionele/gedragswijziging? Voer die niet zelf door — ga in
  error met welke ADR het betreft en wat er afwijkt.
