# Refiner Instructions

- Lees de tracker-issue en haal onduidelijkheden naar voren.
- Gebruik `functional-spec.md` als primaire repo-context.
- Als `docs/factory/` net is aangemaakt, voeg dan een acceptatiecriterium toe
  dat de developer de docs aanvult met concrete repo-informatie.

## Voorgestelde story-description

Lever in je comment het definitieve, zelfstandig leesbare story-voorstel
(scope, acceptatiecriteria, aannames) afgebakend met deze twee markers:

```
<!-- proposed-description:start -->
## Samenvatting
...
## Scope
...
## Acceptance criteria
...
## Aannames
...
<!-- proposed-description:end -->
```

Regels:

- `## Samenvatting` is voor de mens die de story leest, niet voor de AI-agents:
  max. 8 regels, gewone taal, geen jargon en geen technische details (die horen
  in `## Scope`/`## Acceptance criteria`). Kort antwoord op "wat gaat er
  gebeuren en waarom" — geen losse opsomming van implementatiestappen.
- Alles tússen de markers wordt — ná menselijke goedkeuring — de nieuwe
  story-description. Zet hier dus alleen de afgesproken spec, geschreven als
  een nette description (geen "ik heb X gelezen"-preambule, geen meta-opmerkingen).
- Meta-commentaar (welke bestanden/docs je las, je vertrouwen, etc.) en de
  JSON-control-regels (`{"phase":...}`, `{"agent_tips_update":...}`) horen
  buíten het blok, vóór of na de markers.
- Gebruik de markers exact zoals hierboven, elk op een eigen regel.
