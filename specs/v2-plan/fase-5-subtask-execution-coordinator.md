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

## Eén uniforme machine met "AI-stappen" + goedkeuringen

Een subtask is een **ketting van AI-stappen**. Elke AI-stap (rol R, naam N) volgt
hetzelfde patroon, identiek aan de story-refiner/-planner:

```
N-ing → (N-ed-with-questions ⇄ N-questions-answered) → N-ed → [goedkeuring] N-approved | N-rejected
```

- De overgang uit `N-ing` zet de **completion-handler** (als de agent klaar is).
- `N-approved`/`N-rejected` zet de **mens** — of, later, **auto-approve** via een
  per-stap setting (zie onderaan). De **reviewer/tester mag z'n `*-rejected` ook
  zélf zetten** (findings → direct terug naar de developer).
- **Voorwaarde:** de subtask heeft de tag `ai-development` (poll-filter).

### development (developer-stap → reviewer-stap)

| Subtask Phase | Wat de orchestrator doet | Wie zet de volgende status |
|---|---|---|
| _(net getagd)_ | start developer-agent → `developing` | O |
| `developing` | niets; developer draait. Bij klaar → `developed-with-questions` of `developed` | completion |
| `developed-with-questions` | niets; wacht op antwoord | mens → `development-questions-answered` |
| `development-questions-answered` | start developer-agent (met antwoorden) → `developing` | O |
| `developed` | niets; wacht op goedkeuring | mens → `development-approved`/`development-rejected` |
| `development-rejected` | start developer-agent (met feedback) → `developing` | O |
| `development-approved` | start reviewer-agent → `reviewing` | O |
| `reviewing` | niets; reviewer draait. Bij klaar → `reviewed-with-questions`, `reviewed`, of (bij findings) direct `review-rejected` | completion |
| `reviewed-with-questions` | niets; wacht op antwoord | mens → `review-questions-answered` |
| `review-questions-answered` | start reviewer-agent (met antwoorden) → `reviewing` | O |
| `reviewed` | niets; wacht op goedkeuring | mens → `review-approved`/`review-rejected` |
| `review-rejected` | start developer-agent (verwerk findings/comments) → `developing` | O (gezet door reviewer of mens) |
| `review-approved` | **subtask klaar** → fase 4 tagt de volgende | mens |

### review — story-breed (reviewer-stap; **simpele** fix-developer)

| Subtask Phase | Wat de orchestrator doet | Wie |
|---|---|---|
| _(net getagd)_ | start reviewer-agent → `reviewing` | O |
| `reviewing` | reviewer draait. Bij klaar → `reviewed-with-questions`, `reviewed`, of `review-rejected` | completion |
| `reviewed-with-questions` ⇄ `review-questions-answered` | vragen-loop reviewer | mens / O |
| `reviewed` | wacht op goedkeuring | mens → `review-approved`/`review-rejected` |
| `review-rejected` | start developer-agent (fix) → `developing` | reviewer of mens |
| `developing` | developer draait. Bij klaar → `developed-with-questions` of `developed` | completion |
| `developed-with-questions` ⇄ `development-questions-answered` | vragen-loop developer | mens / O |
| `developed` | **geen aparte goedkeuring** → start reviewer (re-review) → `reviewing` | O |
| `review-approved` | **subtask klaar** | mens |

### test — story-breed (tester-stap; **simpele** fix-developer)

| Subtask Phase | Wat de orchestrator doet | Wie |
|---|---|---|
| _(net getagd)_ | start tester-agent → `testing` | O |
| `testing` | tester draait. Bij klaar → `tested-with-questions`, `tested`, of `test-rejected` | completion |
| `tested-with-questions` ⇄ `test-questions-answered` | vragen-loop tester | mens / O |
| `tested` | wacht op goedkeuring | mens → `test-approved`/`test-rejected` |
| `test-rejected` | start developer-agent (fix) → `developing` | tester of mens |
| `developing` | developer draait. Bij klaar → `developed-with-questions` of `developed` | completion |
| `developed-with-questions` ⇄ `development-questions-answered` | vragen-loop developer | mens / O |
| `developed` | **geen aparte goedkeuring** → start tester (re-test) → `testing` | O |
| `test-approved` | **subtask klaar** | mens |

### manual (geen agent)

| Subtask Phase | Wat de orchestrator doet | Wie |
|---|---|---|
| _(net getagd)_ | zet `awaiting-human` | O |
| `awaiting-human` | niets; geen dispatch. Mens doet het werk en zet `manual-action-done` | mens → `manual-action-done` |
| `manual-action-done` | **subtask klaar** → fase 4 tagt de volgende | mens |

### summary (summarizer-stap met goedkeuring)

| Subtask Phase | Wat de orchestrator doet | Wie |
|---|---|---|
| _(net getagd)_ | start summarizer-agent → `summarizing` | O |
| `summarizing` | summarizer draait. Bij klaar → `summary-with-questions` of `summarized` | completion |
| `summary-with-questions` ⇄ `summary-questions-answered` | vragen-loop summarizer | mens / O |
| `summarized` | wacht op goedkeuring | mens → `summary-approved`/`summary-rejected` |
| `summary-rejected` | start summarizer-agent (met feedback) → `summarizing` | O |
| `summary-approved` | **subtask klaar** | mens |

## Kernregels (over alle typen)

- **`*-rejected` is geen eindfase**: het start een developer-fix op de **gedeelde
  branch** en loopt daarna terug naar de primaire rol, tot `*-approved`. Begrensd
  door de **cap** (`AI Max Developer Loopbacks`, nu per subtask); bij
  overschrijding → error/handmatige triage.
- **AI- of mens-geïnitieerd:** reviewer/tester mag z'n `*-rejected` zelf zetten
  (findings → direct naar developer); de mens kan hetzelfde vanuit `reviewed`/`tested`.
- **Subtask klaar** = de laatste stap is `*-approved` (development/review-subtask:
  `review-approved`; test: `test-approved`; summary: `summary-approved`; manual:
  `manual-action-done`). Dan tagt fase 4 (de keten) de volgende subtask.
- **De developer in een review/test/development-subtask** leest naast de eigen
  beschrijving ook de findings/commentaren (zie fase 6: parent-context meegeven).

## Auto-approve (toekomst, per-stap setting)

Bewust staan er nu **veel handmatige goedkeuringen** in (maximale controle). Een
geplande **settings-optie per stap** (bv. `development-approve`, `review-approve`,
`test-approve`, `summary-approve` en de story-gates) laat je kiezen welke
automatisch worden goedgekeurd. Alles op auto-approve → de hele story draait
volledig autonoom. Het model is daar nu al op voorbereid: een goedkeuringsstap is
óf mens-, óf auto-beslist.

## Findings-loopback (beslissing 6, herzien)

De loopback maakt **geen nieuwe subtask** aan; alles gebeurt binnen de subtask via
`*-rejected → developing → ...`. De loopback is nu **goedkeuring-gestuurd** (AI of
mens zet `*-rejected`), niet een automatische verborgen lus. Dit vervangt de oude
story-niveau loopback-fasen (`REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER` /
`TESTED_WITH_FEEDBACK_FOR_DEVELOPER`); die worden in fase 7 opgeruimd.

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

- Dev-subtask happy path: `developing → developed → [development-approved] →
  reviewing → reviewed → [review-approved]` = klaar.
- Vragen-loop: `developed-with-questions` → mens antwoordt
  (`development-questions-answered`) → `developing`.
- Goedkeuring afkeuren: `developed → development-rejected → developing`.
- Reviewer zet zelf findings: `reviewing → review-rejected → developing` (zonder
  mens).
- Story-brede review-subtask: `review-rejected → developing → developed →
  reviewing` (simpele fix-developer, geen aparte goedkeuring) → `review-approved`.
- Test-subtask idem met tester.
- Cap stopt een blijvende `*-rejected → developing`-loop met een nette error.
- Manual-subtask: `awaiting-human` → mens zet `manual-action-done`; geen agent.
- Summary-subtask: `summarizing → summarized → [summary-approved]`.
- (Toekomst) auto-approve per stap: een goedgekeurde stap wordt zonder mens gezet.

## Klaar wanneer

Elk subtask-type draait z'n ketting van AI-stappen op de gedeelde branch, elke stap
heeft een goedkeuringsstap (mens of, later, auto-approve), en afgekeurde stappen
lopen terug naar de developer binnen de cap.
