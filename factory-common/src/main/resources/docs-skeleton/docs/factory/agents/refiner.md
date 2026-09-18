# Refiner Instructions

- Lees de tracker-issue en haal onduidelijkheden naar voren.
- Gebruik `functional-spec.md` als primaire repo-context.
- Als `docs/factory/` net is aangemaakt, voeg dan een acceptatiecriterium toe
  dat de developer de docs aanvult met concrete repo-informatie.

## Voorgestelde samenvatting (voor de aanvrager)

Lever, vóór het description-voorstel, een korte, niet-technische samenvatting
voor de aanvrager, afgebakend met deze twee markers:

```
<!-- proposed-summary:start -->
...
<!-- proposed-summary:end -->
```

Max. 10 zinnen, gewone taal, geen jargon, geen bestands- of klassenamen. Ga in
op: wat was het probleem, waarom moet dit anders, wat gaat deze story precies
veranderen, en wat is de impact (welke onderdelen worden geraakt, bv.
frontend/backend/database/infra). Dit blok wordt na goedkeuring apart
opgeslagen en bovenaan de story getoond.

## Voorgestelde story-description

Lever daarnaast in je comment het definitieve, zelfstandig leesbare
story-voorstel (scope, acceptatiecriteria, aannames) afgebakend met deze twee
markers:

```
<!-- proposed-description:start -->
## Scope
...
## Acceptance criteria
...
## Aannames
...
<!-- proposed-description:end -->
```

Regels:

- Alles tússen de markers wordt — ná menselijke goedkeuring — de nieuwe
  story-description. Zet hier dus alleen de afgesproken spec, geschreven als
  een nette description (geen "ik heb X gelezen"-preambule, geen meta-opmerkingen).
- Meta-commentaar (welke bestanden/docs je las, je vertrouwen, etc.) en de
  JSON-control-regels (`{"phase":...}`, `{"agent_tips_update":...}`) horen
  buíten het blok, vóór of na de markers.
- Gebruik de markers exact zoals hierboven, elk op een eigen regel.

## Testaanpak

Beschrijf per acceptatiecriterium de methode, omgeving, fixtures/mocks/toegang en het verwachte
bewijs. Gebruik de testmogelijkheden uit de opdracht; markeer onbekende mogelijkheden als
te controleren aannames. Neem haalbare ontbrekende testvoorzieningen binnen deze story op
als developerwerk. Leg vast welk alternatief bewijs volstaat en wat alleen na deployment kan
worden gecontroleerd, door wie en met welk verwacht resultaat. Een controle die pas na merge
mogelijk is mag geen verplichte poort vóór merge worden. Stem de bewijslast af op de beperkte
impact van deze toepassingen. Neem deze Testaanpak in het definitieve storyvoorstel op.
