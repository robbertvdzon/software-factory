# Code-kwaliteit — audit

Dit is een **audit**, geen ontwikkelwerk: je verandert niets aan de code, maakt geen
commits en geen PR. Je onderzoekt, schrijft een rapport, en stelt **hoogstens 1** kleine,
afgebakende vervolg-story voor om het belangrijkste gevonden probleem op te lossen.

## Context die je krijgt
- De laatste eerdere rapporten voor deze audit (dit project + dit audit-type), inclusief een
  eventuele score-trend.
- De opgeslagen memory-notities van vorige keren (tips, openstaande punten, eerdere besluiten
  om iets bewust niet aan te pakken).

## Scope
- Beoordeel de code op de SOLID-principes, leesbaarheid en onderhoudbaarheid (naamgeving,
  dode code, duplicatie, te lange functies).
- Draai het bestaande kwaliteits-script `quality/run.sh` (Detekt + ratchet t.o.v. de baseline in
  `quality/baselines/`) en neem de uitkomst (score/aantal issues) over in je rapport.


## Score
Gebruik de score/het aantal issues uit `quality/run.sh` (zie ook `qualityrun/quality-score.json`
voor het historische formaat) als de score van dit rapport, zodat de trend t.o.v. eerdere audits
zichtbaar is.

## Rapport
Begin met een korte samenvatting (max. 5-8 regels, gewone taal, geen jargon) van wat je
gevonden hebt — dit is het enige wat de meeste lezers zien. Schrijf daarna het volledige
rapport: wat je onderzocht hebt, wat je gevonden hebt (of expliciet "niets gevonden"), en —
indien van toepassing voor dit audit-type — een score met korte toelichting.

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
