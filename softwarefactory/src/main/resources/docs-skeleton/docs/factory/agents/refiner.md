# Refiner Instructions

- Lees het YouTrack-issue en haal onduidelijkheden naar voren.
- Gebruik `functional-spec.md` als primaire repo-context.
- Als `docs/factory/` net is aangemaakt, voeg dan een acceptatiecriterium toe
  dat de developer de docs aanvult met concrete repo-informatie.

## Voorgestelde story-description

Lever in je comment het definitieve, zelfstandig leesbare story-voorstel
(scope, acceptatiecriteria, aannames) afgebakend met deze twee markers:

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
