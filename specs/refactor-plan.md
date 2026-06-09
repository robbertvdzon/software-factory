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

De score wordt berekend door **extra Maven-modules in de gemanagede repo zelf**
(voorlopig puur Kotlin gericht). Conventie:

- De repo levert een afgesproken target, bv. `mvn -P quality verify` of een
  `quality`-module, die PMD / detekt / (optioneel) Sonar e.d. draait en alle
  rapporten produceert.
- De build schrijft één **`quality-score.json`** weg met een **totaalscore**
  (alle deelscores opgeteld) plus een per-tool-uitsplitsing. De factory leest
  alleen dat totaal; hoe het wordt opgebouwd is de verantwoordelijkheid van de
  repo.
- **Tests groen is een harde voorwaarde**: een meting met falende build/tests
  levert geen geldige score (telt als regressie / wordt teruggedraaid).

### Anti-gaming: beschermde paden

Een AI die een score moet maximaliseren gaat de meting manipuleren (regels
onderdrukken, drempels verlagen, de quality-module aanpassen, dode code +
bijbehorende warnings weggooien). Omdat de gate in de repo zit en niet van
buitenaf te pinnen is, geldt een **dubbele rem**:

1. **Instructie** aan de developer/analyst: de Maven-quality-stappen, de
   quality-module en de rulesets (pmd/detekt/sonar-config) mogen **niet** worden
   gewijzigd.
2. **Deterministische path-guard** (geen AI-oordeel): een geconfigureerde lijst
   **beschermde paden** (de quality-module(s), de ruleset-bestanden, de
   quality-secties van de pom's). Raakt de diff van een refactor-iteratie één van
   die paden → de iteratie wordt **automatisch afgekeurd en teruggedraaid**,
   ongeacht de score.

## 3. De niet-AI meet-run

De meting bouwt en draait code → dat moet in Docker, net als de agents. Ze
**hergebruikt de bestaande run-plumbing** (`docker run -d`, een `agent_runs`-rij,
de result-file-poller, timeout/transient-retry) met een aparte entrypoint
(`--type=quality`). Het is géén AI-run: de container draait alleen het
quality-target en schrijft het totaal + de rapport-paden naar `agent-result.json`
(degeneratief result-bestand, zonder AI-output). Zo erven we voltooiing,
timeout en recovery gratis en blijft de Docker-grens intact.

## 4. Subtask-flow (`Subtask Phase` voor `Subtask Type = refactor`)

De refactor-subtask is een interne loop die grotendeels autonoom draait met één
**menselijke goedkeuringsstap aan het eind**. De interne developer→reviewer-stap
volgt het bestaande development-patroon (loopback begrensd door
`AI Max Developer Loopbacks`).

| Status | Orchestrator |
|---|---|
| _(leeg)_ | onthoud de huidige commit als **base-commit**; start de meet-run → `measuring-baseline` |
| `measuring-baseline` | meet-run draait; completion slaat **baseline = beste score** op → `analyzing` |
| `analyzing` | quality-analyst draait (krijgt **huidige rapporten + attempt-log**); completion → `refactoring` met een voorstel, of `refactored` als er niets zinnigs meer te doen is |
| `refactoring` | developer draait (voert het voorstel uit, commit); completion → **path-guard**: beschermd pad geraakt → `regressed`, anders → `measuring` |
| `measuring` | meet-run draait; completion vergelijkt met de beste score: lager (of build/tests rood) → `regressed`; hoger → `reviewing` |
| `reviewing` | reviewer bewaakt **gedrag behouden + tests + geen gaming**; completion: goedgekeurd → accepteer (zie onder); afgekeurd-gedrag → `refactoring` (developer-fix, zelfde voorstel, loopback); afgekeurd-onherstelbaar → `regressed` |
| `regressed` | **revert naar de beste commit**, schrijf een attempt-log-regel ("voorstel X: −Δ / beschermd pad / onherstelbaar"); stopconditie? ja → `refactored`, nee → `analyzing` |
| `refactored` | menselijke **goedkeuringsstap**: toont baseline vs eind-score + de diff; wacht op `refactor-approved` / `refactor-rejected` |
| `refactor-rejected` | **revert de branch naar de base-commit** (gooi alle refactor-commits weg); terminaal — de refactor wordt afgebroken |
| `refactor-approved` | terminaal; de keten gaat verder naar de volgende subtask |

**Accepteren van een goedgekeurde iteratie** (`reviewing` → goedgekeurd): de
nieuwe commit wordt de **beste**; verhoog de beste score; iteratie++; schrijf een
attempt-log-regel ("voorstel X: +Δ, toegepast"). Stopconditie bereikt → `refactored`,
anders → `analyzing` (volgende ronde, met verse rapporten).

### Twee soorten "mislukt" — bewust gescheiden

- **Reviewer keurt af op gedrag/tests** → het *voorstel* was goed, de *uitvoering*
  niet. Terug naar de developer met de reviewer-feedback, **zelfde voorstel**,
  begrensd door `AI Max Developer Loopbacks`. De analyzer draait hier niet
  opnieuw.
- **Eerlijk uitgevoerd maar de score zakte** (of beschermd pad geraakt, of
  onherstelbaar gegamed) → het *idee* leverde niets op. Revert naar de beste
  commit en voeg een regel toe aan de **attempt-log**.

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
- Nieuwe custom fields op een refactor-subtask:
  - **`Refactor Mode`** — `count` | `time`.
  - **`Refactor Budget`** — getal: aantal iteraties (`count`) of minuten (`time`).
  - **`Refactor Baseline Score`** / **`Refactor Best Score`** — voor de UI en de
    eind-goedkeuring (oud vs nieuw).
  - **`Refactor Base Commit`** — de SHA van vóór de refactor (voor reject/revert).
- De **base-commit, beste commit en attempt-log** kunnen in de DB leven; de twee
  scores en de mode/budget zijn issue-velden zodat de UI ze toont.
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

1. **Quality-gate in de repo** — Maven `quality`-profiel/-module die
   `quality-score.json` (totaal + uitsplitsing) produceert; vaststellen welke
   tools meetellen en hoe het totaal wordt opgeteld.
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
</content>
</invoke>
