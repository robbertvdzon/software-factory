# Software Factory — Refactor-subtask (plan)

Beschrijft een nieuwe subtask-soort waarmee de factory bestaande code kan
**refactoren om de kwaliteit te verhogen**, zonder het gedrag te veranderen. De
refactor-subtask haakt volledig in op het bestaande twee-laags model uit
[specs.md](specs.md): hij draait als een gewone subtask op de gedeelde
story-branch, erft budget/pauze/recovery, en wordt via de keten of via een
UI-knop aangemaakt.

Het kernidee: kwaliteit wordt **deterministisch gemeten door een script in de
repo zelf** (geen AI-oordeel), en een AI-loop probeert die score iteratief te
verhogen. De AI beslist *wat* te verbeteren en voert het uit; het **getal** uit
het script beslist of het beter werd; een reviewer bewaakt dat het gedrag
behouden blijft en dat de metric niet gegamed wordt.

## 1. Waar het past

- **Nieuw `Subtask Type`-waarde: `refactor`** — naast development / review / test
  / manual / summary.
- Hergebruikt de bestaande rollen **developer** en **reviewer**; introduceert één
  nieuwe AI-rol **quality-analyst** (de analyzer) en één **niet-AI run** (de
  kwaliteitsmeting).
- Draait op de gedeelde branch, sequentieel (max één getagde subtask), onder het
  story-niveau budget en `Paused`. Een lange "refactor 30 minuten" kan dus ook
  door het token-budget of de credits-pauze worden stilgezet — beide
  stopcondities gelden naast elkaar.
- **Scope v1**: de refactor-subtask wordt achteraan (of als eerstvolgende) in de
  keten gehangen. Een lopende subtask écht onderbreken om er een refactor tussen
  te schuiven is **bewust uitgesteld** (de gedeelde-branch + sequentiële
  uitvoering maken een echte mid-agent-run interrupt duur voor weinig winst). Wie
  "tussendoor" wil refactoren laat de huidige stap een commitbare toestand
  bereiken en voegt de refactor als volgende subtask in.

## 2. De kwaliteits-gate zit in de repo

De score wordt berekend door een **quality-target in de gemanagede repo zelf**
(voorlopig puur Kotlin gericht). Conventie:

- De repo levert een afgesproken target dat het quality-script draait en alle
  rapporten produceert.
- De build schrijft één **`quality-score.json`** weg met een **`score`** plus een
  per-regel/per-tool-uitsplitsing. De factory leest **alleen het `score`-getal**;
  hoe het wordt opgebouwd is de verantwoordelijkheid van de repo.
- **Tests groen is een harde voorwaarde**: een meting met falende build/tests
  levert geen geldige score (telt als regressie / wordt teruggedraaid).

### Concrete implementatie (v1)

De referentie-implementatie staat in deze repo (`softwarefactory`, zelf
Kotlin/Maven) en dient meteen als sjabloon voor wat de factory van een gemanagede
repo verwacht:

- **`quality/detekt.yml`** — de Detekt-ruleset. Bepaalt *hoe* er gemeten wordt →
  **beschermd pad**.
- **`quality`-Maven-profiel** (in de module-pom) — draait Detekt op **alleen
  `src/main/kotlin`** (tests uitgesloten) via de `detekt-maven-plugin`. Detekt
  parseert broncode, dus dit heeft **geen compile/test/DB** nodig en is snel:
  `mvn -pl <module> -P quality detekt:check`.
- **`quality/run.sh`** — wrapper die het profiel draait en de artefacten wegschrijft.
- **`quality-score.json`** (machine) — de gate leest hieruit het veld **`score`**.
- **`qualityrun/<timestamp>/`** (gitignored) — historie per run: `detekt.xml`,
  `detekt.md`, `quality-score.json`, `latest.md`. Daarnaast altijd
  `qualityrun/latest.md` + `qualityrun/quality-score.json` als snelkoppeling.
  De **config in `quality/` wordt wél gecommit**; alleen de runs zijn lokaal.

**De score-formule (v1):**

```
score = totalFindings + suppressions      // LAGER = beter
```

- **`totalFindings`** = aantal Detekt-bevindingen (alle regels **even zwaar** in
  v1 — bewust simpel; lange regels / magic numbers ruimt de agent snel op,
  complexere refactors gaandeweg). Weging per regel is een latere verfijning.
- **`suppressions`** = aantal `@Suppress` / `@SuppressWarnings` /
  `// detekt:disable` / `// ktlint-disable` in de main-source. Tellen mee zodat
  een bevinding *wegzwijgen* netto niets oplevert (−1 finding, +1 suppressie).
- **Baseline van deze repo bij invoering: `score = 291`** (291 findings,
  0 suppressies).

> **Let op — richting:** in v1 is de score een *penalty die omlaag moet*. Lees in
> de state-machine van §4 "beter/hoger" daarom als **"lager"**. Een latere
> genormaliseerde 0-100-score (hoger = beter) draait dit weer om.

> **Latere uitbreidingen** (nu bewust níét gedaan om simpel te starten):
> per-regel-weging (complexiteit zwaar, stijl licht), Detekt's
> `CognitiveComplexMethod` expliciet aanzetten, JaCoCo-coverage als *guardrail*
> ("mag niet zakken", niet als maximalisatie-target), en optioneel een klikbaar
> HTML-rapport. **PMD vervalt** voor Kotlin (Java-only); **Sonar** blijft optioneel
> (vereist een server → minder deterministisch voor de in-repo gate).

### Contract: elke refactorbare repo levert hetzelfde

Een repo is **refactorbaar** als hij exact deze twee dingen levert — een vaste
factory-conventie, identiek over alle repo's:

1. **`quality/run.sh`** — hetzelfde referentiescript, **module-agnostisch**: het
   draait het `quality`-profiel over de hele repo-reactor en aggregeert alle
   module-rapporten.
2. **`quality-score.json`** — met het afgesproken schema: `score`,
   `totalFindings`, `suppressions`, `byRule`.

Wat per repo verschilt is alleen de **wiring**: het `quality`-Maven-profiel en de
ruleset (`quality/detekt.yml`) in de eigen pom('s) (de module-layout verschilt per
repo). Script en json-schema zijn gestandaardiseerd; de meet-run roept altijd
`quality/run.sh` aan en leest `score`.

> **Implementatie-gat:** de huidige `quality/run.sh` is nog module-specifiek
> (`-pl softwarefactory`) en moet hiervoor module-agnostisch worden gemaakt.

### Anti-gaming: beschermde paden

Een AI die een score moet maximaliseren gaat de meting manipuleren (regels
onderdrukken, drempels verlagen, de quality-module aanpassen, dode code +
bijbehorende warnings weggooien). Omdat de gate in de repo zit en niet van
buitenaf te pinnen is, geldt een **dubbele rem**:

1. **Instructie** aan de developer/analyst: de Maven-quality-stappen, het
   quality-script en de ruleset (`quality/detekt.yml`) mogen **niet** worden
   gewijzigd, en bevindingen mogen niet met `@Suppress` worden onderdrukt.
2. **Deterministische path-guard** (geen AI-oordeel): een geconfigureerde lijst
   **beschermde paden** — concreet: **`quality/detekt.yml`**, **`quality/run.sh`**,
   en het **`quality`-profiel in de pom('s)**. Raakt de diff van een
   refactor-iteratie één van die paden → de iteratie wordt **automatisch afgekeurd
   en teruggedraaid**, ongeacht de score. (Suppressies zelf zitten bovendien al in
   de score, zie §2.)

## 3. De niet-AI meet-run

De meting bouwt en draait code → dat moet in Docker, net als de agents. Ze
**hergebruikt de bestaande run-plumbing** (`docker run -d`, een `agent_runs`-rij,
de result-file-poller, timeout/transient-retry) met een aparte entrypoint
(`--type=quality`). Het is géén AI-run: de container draait alleen het
**quality-target van de repo** — in onze referentie **`quality/run.sh`**, dat het
`quality`-Maven-profiel draait en **`quality-score.json`** produceert. De meet-run
leest daaruit het **`score`-getal** en schrijft dat + de rapport-paden naar
`agent-result.json` (degeneratief result-bestand, zonder AI-output). De
orchestrator persisteert dat getal vervolgens in de DB (zie §7); de
`quality-score.json`/`qualityrun/`-bestanden zelf zijn transiente meet-output. Zo
erven we voltooiing, timeout en recovery gratis en blijft de Docker-grens intact.

## 4. Subtask-flow (`Subtask Phase` voor `Subtask Type = refactor`)

De refactor-subtask is een interne loop die grotendeels autonoom draait met één
**menselijke goedkeuringsstap aan het eind**. De interne developer→reviewer-stap
volgt het bestaande development-patroon (loopback begrensd door
`AI Max Developer Loopbacks`).

| Status | Orchestrator |
|---|---|
| _(leeg)_ | leg de huidige commit (`git rev-parse HEAD`) vast als **base-commit** in de DB; start de meet-run (`quality/run.sh`) → `measuring-baseline` |
| `measuring-baseline` | meet-run draait `quality/run.sh`; completion slaat **baseline = beste score** (+ beste-commit) op in de DB → `analyzing` |
| `analyzing` | quality-analyst draait (krijgt **huidige rapporten + attempt-log**); completion → `refactoring` met een voorstel, of `refactored` als er niets zinnigs meer te doen is |
| `refactoring` | developer draait (voert het voorstel uit, commit); completion → de **orchestrator** doet een **deterministische diff-check** (`git diff --name-only` t.o.v. de beschermde paden, geen AI): raakt de diff de **meet-config** (`detekt.yml` / `run.sh` / het `quality`-profiel) → `regressed` (vals spel), anders → `measuring` |
| `measuring` | meet-run draait; completion vergelijkt met de beste score: lager (of build/tests rood) → `regressed`; hoger → `reviewing` |
| `reviewing` | reviewer (AI-oordeel) bewaakt **gedrag behouden + tests + geen semantische gaming**; completion: goedgekeurd → **accepteer** (zie onder), dan **stopconditie? ja → `refactored`, nee → `analyzing`**; afgekeurd-gedrag → `refactoring` (developer-fix, zelfde voorstel, loopback); afgekeurd-onherstelbaar → `regressed` |
| `regressed` | **revert naar de beste commit**, schrijf een attempt-log-regel ("voorstel X: −Δ / meet-config geraakt / onherstelbaar"); **stopconditie? ja → `refactored`, nee → `analyzing`** |
| `refactored` | menselijke **goedkeuringsstap**: toont baseline vs eind-score + de diff; wacht op `refactor-approved` / `refactor-rejected` |
| `refactor-rejected` | **revert de branch naar de base-commit** (gooi alle refactor-commits weg); terminaal — de refactor wordt afgebroken |
| `refactor-approved` | terminaal; de keten gaat verder naar de volgende subtask |

> **Einde van de loop (`→ refactored`)** kan op drie momenten: (a) de analyzer
> ziet niets zinnigs meer, of — ná een iteratie — wanneer de **stopconditie**
> (max iteraties / tijd) is bereikt, zowel (b) op de **accept**-route als (c) op
> de **regress**-route. De stopconditie staat dus los van de diff-check uit
> `refactoring`: die laatste is anti-gaming, niet "klaar".

**Accepteren van een goedgekeurde iteratie** (`reviewing` → goedgekeurd): de
nieuwe commit wordt de **beste**; verhoog de beste score; iteratie++; schrijf een
attempt-log-regel ("voorstel X: +Δ, toegepast"). Stopconditie bereikt → `refactored`,
anders → `analyzing` (volgende ronde, met verse rapporten).

### Twee soorten "mislukt" — bewust gescheiden

- **Reviewer keurt af op gedrag/tests** → het *voorstel* was goed, de *uitvoering*
  niet. Terug naar de developer met de reviewer-feedback, **zelfde voorstel**,
  begrensd door `AI Max Developer Loopbacks`. De analyzer draait hier niet
  opnieuw.
- **Eerlijk uitgevoerd maar de score zakte** (of de diff-check zag de meet-config
  geraakt, of de reviewer zag onherstelbare gaming) → het *idee* leverde niets op.
  Revert naar de beste commit en voeg een regel toe aan de **attempt-log**.

## 5. De attempt-log en de analyzer

Omdat elke agent-run een verse Docker-clone is, kan "wat al geprobeerd is" niet in
agent-geheugen leven. Per refactor-subtask houdt de orchestrator een **attempt-log**
bij (in de subtask-state / als YouTrack-comments, naast de score per iteratie).
Elke regel: **wat het voorstel beoogde** + de **uitkomst** (toegepast & +Δ /
eerlijk maar −Δ / afgekeurd wegens …).

Bij elke `analyzing`-ronde krijgt de quality-analyst mee:
- de **huidige rapporten** (verse meting — de al doorgevoerde verbeteringen zijn
  zichtbaar in de code), en
- de **attempt-log**, met de instructie: deze voorstellen zijn al geprobeerd en
  hielpen niet — stel iets anders voor, of een andere aanpak voor hetzelfde doel.

Zo valt de analyzer na een teruggedraaide iteratie niet terug op exact hetzelfde
voorstel, zonder dat een goed idee verloren gaat omdat de developer het slordig
uitvoerde.

## 6. Beste-commit, revert en reject-semantiek

- De loop houdt steeds de **beste score + bijbehorende commit-SHA** bij. Aan het
  eind staat de branch op de **beste** commit, niet per se de laatste.
- Een **regressie** wordt hard teruggedraaid (`git reset` naar de beste commit) —
  betrouwbaarder dan de developer vragen het ongedaan te maken.
- **Reject aan het eind betekent terugdraaien**, niet opnieuw: de branch gaat
  terug naar de **base-commit** van vóór de refactor, zodat de story-branch schoon
  blijft. Dit wijkt bewust af van andere subtaken, waar reject "opnieuw doen"
  betekent.

## 7. YouTrack-model — toevoegingen

- `Subtask Type` krijgt de waarde **`refactor`**.
- Nieuwe custom fields op een refactor-subtask — **alleen config die de gebruiker
  zet**, geen runtime-state:
  - **`Refactor Mode`** — `count` | `time`.
  - **`Refactor Budget`** — getal: aantal iteraties (`count`) of minuten (`time`).
- **Runtime-state leeft in de DB** (eigendom van de orchestrator, session-scoped):
  **base-commit, beste-commit, baseline-score, beste-score en de attempt-log**.
  Bewust niet in YouTrack-issuevelden: dat scheelt **schrijf-churn per iteratie**,
  en het is precies waar recovery + het dashboard al kijken.
- **Niet** in de (ephemeral) repo-workspace: `quality-score.json`/`qualityrun/`
  zijn transiente meet-output — de meet-run leest er `score` uit, geeft dat via
  `agent-result.json` door, en de orchestrator persisteert het in de DB.
- Hergebruikt zonder wijziging: `AI Max Developer Loopbacks`, `Paused`, `Error`,
  `AgentStartedAt`, token-budget op story-niveau.

## 8. Dashboard

- De **UI-knop "Refactor-subtask toevoegen"** op een story maakt een
  `refactor`-subtask aan (ook bij een story waarvan alle subtaken klaar zijn — hij
  komt onderaan de keten) en vraagt **mode + budget** (3× of 30 min).
- De subtask-detail toont de **score-trajectorie** (baseline → per iteratie →
  beste), de attempt-log, en bij `refactored` de **goedkeuringsstap met oud vs
  nieuw**.
- Bestaande commands (pause/resume/kill, clear-error, retry-current-step) werken
  ook hier.

## 9. Rollen

Toevoeging aan de rollen-set uit specs.md §8:
- **quality-analyst** — leest de rapporten + attempt-log, maakt een
  geprioriteerde verbeterlijst en kiest de refactoring(en) met het meeste effect
  die in **één** developer-run passen. Emit het voorstel in `agent-result.json`.
- **developer** / **reviewer** — hergebruikt, met refactor-specifieke prompts
  (developer: voer dít voorstel uit, raak de quality-config niet aan; reviewer:
  gedrag behouden + geen gaming).
- De **meet-run** is geen rol maar een `--type=quality` container die
  `quality-score.json` produceert.

## 10. Implementatievolgorde (fasen)

1. **Quality-gate in de repo** — ✅ **gedaan (v1)**: Maven `quality`-profiel +
   `quality/detekt.yml` + `quality/run.sh` die `quality-score.json`
   (`score = totalFindings + suppressions`) + `latest.md` met hotspots produceren.
   Detekt-only, main-code, baseline `score = 291`. Weging/normalisatie + coverage
   zijn latere verfijningen (zie §2).
2. **Niet-AI meet-run** — `--type=quality` entrypoint in agentworker;
   result-file draagt de score; hergebruik van de run-plumbing
   (agent_runs/poller/timeout).
3. **Subtask-type + state machine** — `refactor` als `Subtask Type`; de
   `Subtask Phase`-tabel uit §4 in de SubtaskExecutionCoordinator; config-velden
   (mode/budget) en de scores/base-commit.
4. **De loop** — analyzer-rol + attempt-log; beste-commit-tracking; revert bij
   regressie; path-guard op beschermde paden; stopcondities (iteraties/tijd, naast
   het bestaande token-budget).
5. **Eind-goedkeuring + reject=revert** — `refactored` goedkeuringsstap met oud vs
   nieuw; `refactor-rejected` draait terug naar de base-commit.
6. **UI** — knop "Refactor-subtask toevoegen" (mode/budget), score-trajectorie en
   de eind-goedkeuring in het subtask-detail.

## 11. Geparkeerd

- Mid-flight een refactor in een lopende subtask injecteren (zie §1).
- Ruleset/score van buiten de repo pinnen (nu bewust in de repo gehouden voor
  eenvoud; de path-guard dekt het misbruik af).
- Auto-approve van de eind-goedkeuringsstap (past op de geplande per-stap
  auto-approve-instelling, maar nog niet ingebouwd).
