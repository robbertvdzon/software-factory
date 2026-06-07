# Fase 5 — SubtaskExecutionCoordinator (uniforme pipeline + interne loopback)

> Onderdeel van het v2-herontwerp. Lees eerst het overzicht: [README.md](./README.md).
> Deze fase staat op zichzelf en kan los gerefined en opgepakt worden.
>
> **Volgorde-koppeling:** de parent-keyed serialisatie + recovery uit fase 6
> moeten live zijn vóórdat deze fase echte subtask-agents draait (anders twee
> agents op één branch). Behandel fase 5+6 als één blok, of doe die stukken van 6
> eerst.

## Doel

Subtaken uitvoeren op de **gedeelde story-branch** met één **uniforme**
state machine, waarbij gevonden problemen **intern** worden opgelost.

## Eén uniforme machine met een "primaire rol"

Alle AI-subtaken hebben dezelfde vorm: **[primaire rol] → [interne verify+fix-loop
met een developer tot ok] → done**. Alleen de startrol/het beginveld verschilt.
De `SubtaskExecutionCoordinator` is dus één machine met een parameter (de
primaire rol uit `Subtask Type`), geen drie losse implementaties.

### development (primair: Developer; ingebouwde review)
```
DEVELOPING → DEVELOPED → REVIEWING → REVIEWED_OK → DONE
   ▲                         │
   └── REVIEW_WITH_FINDINGS ─┘   (interne fix: terug naar DEVELOPING, dan REVIEWING)
```

### review — story-breed (primair: Reviewer)
```
REVIEWING → REVIEWED_OK → DONE
   ▲            │
   └── REVIEW_WITH_FINDINGS → DEVELOPING → REVIEWING ...
```

### test — story-breed (primair: Tester)
```
TESTING → TESTED_OK → DONE
   ▲          │
   └── TESTED_WITH_FINDINGS → DEVELOPING → TESTING ...
```

### manual (geen agent)
```
AWAITING_HUMAN → DONE   (mens flipt 'm via @factory:command of veld; geen dispatch)
```

### summary (primair: Summarizer; geen fix-loop)
```
SUMMARIZING → DONE      (één SUMMARIZER-run; laatste subtask van de story)
```
`manual` en `summary` zijn de eenvoudige uitzonderingen op de uniforme
[primair → fix-loop]-vorm: `manual` heeft geen agent, `summary` heeft één
agent-run zonder verify+fix-loop.

## Kernregels

- **`*_WITH_FINDINGS` is geen eindfase**: het routeert naar een interne
  `DEVELOPING`-stap (zelfde subtask, zelfde branch) en daarna terug naar de
  primaire rol, tot `*_OK`.
- **Manual-verify checkpoint** na elke AI-stap (`DEVELOPED`, `REVIEWED_OK`,
  `TESTED_OK`): de mens kan het AI-oordeel controleren/overrulen vóór doorgaan.
  Zonder ingrijpen gaat 'ie automatisch verder.
- **Subtask user-vragen-loop**: elke subtask-agent kan vragen stellen
  (`SUBTASK_WITH_QUESTIONS` ⇄ `SUBTASK_QUESTIONS_ANSWERED`), analoog aan de
  refiner.
- **Cap** op de interne fix-loop via het bestaande `AI Max Developer Loopbacks`
  (nu per subtask). Bij overschrijding → error/handmatige triage i.p.v. eindeloos
  re-reviewen.
- De developer in een review/test-subtask leest naast de eigen beschrijving ook
  de findings/commentaren (zie fase 6: parent-context meegeven).

## Findings-loopback = intern (beslissing 6)

De loopback maakt **geen nieuwe subtask** aan; alles gebeurt binnen de subtask.
Dit vervangt de oude story-niveau loopback-fasen
(`REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER` / `TESTED_WITH_FEEDBACK_FOR_DEVELOPER`);
die worden in fase 7 opgeruimd.

## Geparkeerd (later)

**Cross-subtask re-review**: als de interne fix in een test-subtask veel
verandert, een eerdere (story-brede) review opnieuw laten draaien. Plan: de
**agent declareert** dit in `agent-result.json`; de orchestrator heropent dan die
review-subtask. Niet in deze fase.

## Betrokken bestanden

- `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/orchestrator/services/OrchestratorService.kt`
  (+ nieuwe `SubtaskExecutionCoordinator`)
- `.../orchestrator/services/AiRouting.kt` (rol per primaire/fix-stap)
- `.../runtime/RuntimeApi.kt` (findings/outcome in result)
- `.../runtime/services/AgentRunCompletionService.kt` (phase-overgangen subtask)

## Test

- Dev-subtask: `developing → developed → reviewing → reviewed-ok → done`.
- Dev-subtask met review-findings: interne fix-loop → `reviewed-ok` → `done`.
- Story-brede review met findings → interne dev-fix → re-review → ok.
- Test-subtask met findings → interne dev-fix → re-test → ok.
- Cap stopt een blijvende findings-loop met een nette error.
- Manual-subtask wacht op de mens en dispatcht geen agent.
- Summary-subtask: één SUMMARIZER-run → `done` (geen fix-loop).
- Manual-verify checkpoint: mens kan een `reviewed-ok` overrulen.

## Klaar wanneer

Elk subtask-type draait z'n uniforme pipeline op de gedeelde branch, gevonden
problemen worden intern opgelost binnen de cap, en de mens heeft verify-momenten.
