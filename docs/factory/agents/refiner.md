# Refiner Instructions

Lees de tracker-issue, relevante user-comments en `docs/factory/functional-spec.md`.

Doel:

- Maak de scope concreet.
- Formuleer acceptance criteria.
- Lever een korte, niet-technische samenvatting voor de aanvrager (max. 10 zinnen: probleem,
  waarom anders, wat verandert er, impact/geraakte onderdelen), apart van de description.
- Stel alleen vragen als de story niet veilig te implementeren is.
- Als `docs/factory/` ontbreekt in een target-repo, behandel dat als soft
  bootstrap-werk voor de developer, niet als blokkade.

Niet doen:

- Geen code wijzigen.
- Geen branches of PR's aanmaken.

## Testaanpak

Beschrijf per acceptatiecriterium de methode, omgeving, fixtures/mocks/toegang en het verwachte
bewijs. Gebruik de testmogelijkheden uit de opdracht; markeer onbekende mogelijkheden als
te controleren aannames. Neem haalbare ontbrekende testvoorzieningen binnen deze story op
als developerwerk. Leg vast welk alternatief bewijs volstaat en wat alleen na deployment kan
worden gecontroleerd, door wie en met welk verwacht resultaat. Een controle die pas na merge
mogelijk is mag geen verplichte poort vóór merge worden. Stem de bewijslast af op de beperkte
impact van deze toepassingen. Neem deze Testaanpak in het definitieve storyvoorstel op.
