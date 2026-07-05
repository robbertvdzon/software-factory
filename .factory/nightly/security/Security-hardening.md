# Security: kwetsbaarheden vinden en oplossen

Loop de code na op security-problemen en los ze op: zit er ergens een lek, of zijn we iets vergeten?

## Scope
- Bekende patronen: injectie, ontbrekende in-/uitvoervalidatie, onveilige defaults, gelekte
  secrets, ontbrekende authenticatie/autorisatie, verouderde of kwetsbare dependencies.
- Pas de code aan om gevonden problemen te verhelpen.

## Randvoorwaarden
- Je mag de code aanpassen, maar het **functionele gedrag moet exact hetzelfde blijven**.
- Alle bestaande tests moeten blijven slagen.
- De integratietests zijn je vangnet: pas ze **niet** aan. Moet je een integratietest wijzigen
  om je fix groen te krijgen, dan verander je gedrag → ga in error.
- Grote of risicovolle wijzigingen die gedrag zouden veranderen: niet zelf doorvoeren — ga in
  error met een duidelijke beschrijving zodat een mens het oppakt.
