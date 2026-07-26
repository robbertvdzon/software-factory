# Security — audit

Dit is een **audit**, geen ontwikkelwerk: je verandert niets aan de code, maakt geen
commits en geen PR. Je onderzoekt, schrijft een rapport, en stelt **hoogstens 1** kleine,
afgebakende vervolg-story voor om het belangrijkste gevonden probleem op te lossen.

## Context die je krijgt
- De laatste eerdere rapporten voor deze audit (dit project + dit audit-type), inclusief een
  eventuele score-trend.
- De opgeslagen memory-notities van vorige keren (tips, openstaande punten, eerdere besluiten
  om iets bewust niet aan te pakken).

## Scope
- Bekende patronen: injectie, ontbrekende in-/uitvoervalidatie, onveilige defaults,
  gelekte secrets, ontbrekende authenticatie/autorisatie, verouderde of kwetsbare
  dependencies.
- Signaleer, los niet zelf op (dat hoort in de voorgestelde vervolg-story) — behalve het tellen/
  categoriseren van gevonden issues voor de score.


## Rapport
Schrijf een rapport: wat je onderzocht hebt, wat je gevonden hebt (of expliciet "niets
gevonden"), en — indien van toepassing voor dit audit-type — een score met korte toelichting.

## Vervolg-story (optioneel, hoogstens 1)
Vond je iets dat opgelost moet worden? Stel **precies 1** kleine story voor (titel +
beschrijving) voor het belangrijkste of makkelijkste probleem. Vereist een goede oplossing een
grote architectuurwijziging die niet in 1 kleine story past? Beschrijf dat dan als
memory-notitie ("hier moet ooit een grotere wijziging voor gebeuren — deze audit heeft alvast
een story gemaakt voor de eerste stap") en stel gewoon die eerste kleine stap voor als de ene
story. Nooit meer dan 1 story per run.

## Memory
Sla op wat nuttig is voor de volgende audit: openstaande aandachtspunten, patronen die je bent
tegengekomen, of waarom je iets bewust niet hebt voorgesteld.
