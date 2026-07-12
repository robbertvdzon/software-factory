# Uitvoerregels voor ieder deelplan

Deze regels zijn bindend voor iedere developer-, reviewer-, tester- en coördinerende agent die een
deelplan uit [`README.md`](README.md) uitvoert. Een deelplan mag regels aanscherpen, maar niet
afzwakken, behalve waar de expliciete gebruikersoverride hieronder voor plannen 03–09 andere
regels voorschrijft.

## Gebruikersoverride: versnelde uitvoering voor plannen 03–09

Deze override is op 12 juli 2026 expliciet door de eigenaar gekozen voor het private hobbysysteem.
Zij vervangt voor plannen 03 tot en met 09 alle strijdige regels verderop in dit document en in de
afzonderlijke deelplannen over branches, pushes, PR's, reviewer-/testerrondes, GitHub-checks en
post-mergeverificatie.

1. Gebruik **één branch en één PR per plan/fase**, niet één branch of PR per Factory-story.
2. Houd wel één Factory-story per werkpakketcode aan. Voer die handmatig uit, zet haar niet op
   `start`, en zet de bordstatus op `Done` zodra scope, lokale tests en de eigen storycommit klaar
   zijn.
3. Rond iedere story volledig lokaal af:
   - implementeer alleen de storyscope;
   - draai de relevante gerichte lokale tests en controleer dat de bedoelde tests werkelijk liepen;
   - werk geraakte documentatie en `VOORTGANG.md` bij;
   - maak één zelfstandige, herkenbare commit per story.
4. Push tussentijds niet. De lokale storycommits op de fasebranch vormen de overdraagbare historie.
5. Herhaal niet per story de volledige repositorysuite en voer geen afzonderlijke, duplicerende
   developer-, reviewer- en testerruns uit. Review en test risicogericht binnen de lokale
   storyafhandeling.
6. Voer pas nadat alle stories van de fase gecommit zijn één volledige lokale fase-eindgate uit:
   - de op dat moment canonieke volledige repositorygate;
   - `./quality/run.sh`;
   - Flutter-, Docker-, Compose-, image- of smokegates alleen wanneer de fasescope die raakt of het
     deelplan ze als fase-eindbewijs vereist.
7. Iedere lokale failure blijft blokkerend en wordt onderzocht en hersteld. De versnelde modus
   versoepelt het groene-testbeleid niet; zij voorkomt alleen dubbele volledige runs.
8. Push na de groene fase-eindgate één keer, open één PR voor de hele fase en merge die direct naar
   `main`. Branch protection is door de eigenaar uitgeschakeld.
9. Wacht niet op GitHub Actions, monitor GitHub-builds niet en maak geen evidence-only PR. Een later
   falende GitHub-build wordt door de eigenaar achteraf afgehandeld en blokkeert deze autonome
   uitvoering niet.
10. Werk na de merge alleen de lokale/duurzame faseadministratie bij als dat al in de fase-PR kan;
    voer geen nieuwe post-merge test- of documentatieronde uit.

De oudere regels hieronder blijven volledig gelden voor plannen 01–02 als historisch
uitvoeringscontract. Voor plannen 03–09 blijven zij alleen gelden voor zover ze niet botsen met
deze override, met name: Factory-storytraceerbaarheid, scopescheiding, geen genegeerde lokale
failures, geen testskips en actuele documentatie.

## 1. Werk uitsluitend via Factory-stories

1. Maak vóór iedere code-, configuratie- of actuele documentatiewijziging een Factory-story aan.
2. Gebruik één story per werkpakketcode (`FIX-01`, `ARC-03`, enzovoort). `MOD-03` krijgt één story
   per module; combineer die migraties niet.
3. Zet een story die door deze Codex-opdracht handmatig wordt uitgevoerd niet ook op `start` voor
   de interne Factory-pipeline. Voorkom twee gelijktijdige uitvoerders voor dezelfde story.
4. Noteer storykey, titel, branch, PR en status onmiddellijk in [`VOORTGANG.md`](VOORTGANG.md).
5. Gebruik het worklog van de eigen story voor tussentijdse uitvoering. Wijzig geen historische
   storydocumenten van ander werk.

De lokale, niet-gestarte storyroute is hieronder weergegeven. Zet uitsluitend de twee benodigde
variabelen in de omgeving met de lokale secretmanager, IDE-runconfiguratie of een dotenv-aware
helper. Voer `secrets.env` **niet** met `source` uit: dat behandelt het hele bestand als shellcode,
exporteert onnodig alle secrets en parseert niet iedere geldige dotenvwaarde veilig.

```bash
export SF_FACTORY_API_URL=http://localhost:8080
: "${SF_FACTORY_API_TOKEN:?laad deze key eerst via secretmanager, IDE of dotenv-aware helper}"

# Health-/authcheck: dit moet slagen voordat een story wordt aangemaakt.
tools/sf-story projects >/dev/null

tools/sf-story create \
  --project SF \
  --title "<storytitel>" \
  --description "<zelfstandige scope en acceptatiecriteria>" \
  --repo softwarefactory \
  --ai-supplier codex
```

Gebruik dashboard of Telegram wanneer dat de actieve canonieke route is. Leg de gebruikte route
vast; plaats nooit tokens of secrets in shell history, logs, stories of documentatie. Verwijder de
tijdelijk gezette token na afloop uit de shellomgeving (`unset SF_FACTORY_API_TOKEN`). Wanneer geen
veilige key-specifieke loader beschikbaar is, is dat een toolingblokkade: voer niet alsnog het hele
secretbestand als shell uit.

## 2. Eén story, één branch, één verantwoordelijkheid

- Begin vanaf de actuele groene default branch nadat prerequisites zijn gemerged.
- Gebruik een aparte branch per story en volg de in de actieve Codex-/Factory-omgeving ingestelde
  branchprefix.
- Combineer geen aangrenzende refactor “omdat het bestand toch openstaat”.
- Houd gedrag, wirecontracten en databaseschema gelijk, tenzij de story expliciet een migratie
  vraagt.
- Splits een onverwacht groot, onafhankelijk probleem af naar een nieuwe blokkerende Factory-story.
  De oorspronkelijke story blijft niet goedgekeurd zolang die blokkade bestaat.
- Push regelmatig genoeg dat actief werk en voortgang buiten het lokale gesprek terug te vinden
  zijn.

## 3. Baseline vóór iedere story

Voer vóór de eerste wijziging uit:

```bash
git status --short
git log -1 --oneline
mvn verify
```

Bij Flutterscope ook:

```bash
cd dashboard-frontend
flutter analyze
flutter test
```

Bij Kotlin-refactors tevens:

```bash
./quality/run.sh
```

Vanaf de merge van `VER-02` in plan 03 draait iedere story bovendien onvoorwaardelijk het daar
vastgelegde **canonieke volledige repositorygatecommando**. Een deelplan mag extra gerichte gates
toevoegen, maar deze centrale gate niet conditioneel maken of vervangen door alleen `mvn verify`.
Leg de exacte actuele command-id/configversie in het storybewijs vast. Wanneer de gate in de
actuele default branch ontbreekt of niet uitvoerbaar is, is de voorganger niet afgerond en wordt de
story geblokkeerd teruggestuurd naar plan 03.

Leg command, exitcode, datum/tijd en relevante tellingen vast. Een falende baseline wordt niet
genegeerd en ook niet administratief “bekend” verklaard.

## 4. Absoluut groene-testbeleid

- Iedere falende unit-, integratie-, e2e-, contract-, Flutter- of smoketest is een blocker, ook als
  de fout al vóór de story bestond of inhoudelijk ongerelateerd lijkt.
- De developer onderzoekt en herstelt de fout. Kleine veilige reparaties mogen als boyscouting in
  de huidige story; grotere reparaties krijgen eerst een aparte blokkerende story.
- Een test mag alleen inhoudelijk worden gewijzigd wanneer het bedoelde gedrag aantoonbaar wijzigt
  en de story dat vraagt. Maak een test nooit zwakker om productiecode groen te krijgen.
- Geen `@Disabled`, skip, quarantine, `|| true`, suppressie of kleinere testscope gebruiken als
  vervanging voor een groene volledige suite.
- Omgevingsproblemen worden opgelost of als echte blokkade geregistreerd. “Geen Docker” of “geen
  Flutter” is geen testgoedkeuring.
- Na een fix worden eerst de gerichte regressietests en daarna alle planbrede gates opnieuw vanaf
  een schone toestand uitgevoerd.
- Een gefilterd Mavencommando met `-am` mag
  `-Dsurefire.failIfNoSpecifiedTests=false` gebruiken zodat upstreammodules zonder die testnaam de
  reactor niet afbreken. Dat maakt een lege selectie in de **bedoelde doelmodule niet groen**:
  controleer de vers aangemaakte Surefire-/Failsafe-rapporten, eis minimaal één uitgevoerde bedoelde
  test en leg klassenaam plus telling vast. Nul geselecteerde tests, een oud rapport of een wildcard
  die de nieuw vereiste regressieklasse niet raakt, is een falende gate.

## 5. Developer, reviewer en tester

### Developer

- Reproduceert het probleem of legt een concrete baseline vast.
- Implementeert alleen de afgesproken scope en werkt geraakte actuele docs bij.
- Levert exacte testcommando's plus onverkorte succes-/faaltellingen aan.
- Draagt nooit over met een rode of niet-uitgevoerde verplichte test.

### Reviewer

- Beoordeelt de volledige diff tegen story, bronplan, specs en modulegrenzen.
- Controleert testbewijs en draait risicogerichte tests opnieuw.
- Keurt nooit goed wanneer een verplichte test faalt, ontbreekt of alleen als “pre-existing” is
  weggezet.
- Stuurt iedere bevinding terug naar de developer; na een wijziging volgt een nieuwe volledige
  reviewronde.

### Tester

- Test de uiteindelijke reviewde commit, niet een oudere SHA.
- Draait de volledige voorgeschreven suite onafhankelijk en controleert zichtbaar gedrag waar van
  toepassing.
- Negeert geen enkele falende unit test. Iedere failure gaat terug naar de developer, ongeacht de
  vermeende relatie met de story.
- Approvet uitsluitend als alle verplichte tests groen zijn en het bewijs in story/worklog staat.

## 6. CI, push, merge en deployment

1. Push de storybranch en open een PR met storykey, scope, risico's, docs en testbewijs.
2. Wacht op alle verplichte checks. `queued`/`in_progress` is wachten; missing, skipped, cancelled
   en failed zijn niet groen.
3. Merge niet via een bypass of alternatieve handmatige route.
4. Controleer na merge de actuele default-branch-SHA en relevante post-mergechecks.
5. Deploy alleen wanneer het werkpakket dat vraagt. Verifieer dan de daadwerkelijk draaiende SHA,
   health en rollbackmogelijkheid.

## 7. Voortgang en hervatten

Werk [`VOORTGANG.md`](VOORTGANG.md) minimaal bij:

- bij start van een story;
- na iedere developer/reviewer/tester-overdracht;
- bij iedere blokkade;
- na push, PR, merge en post-mergeverificatie;
- bij afronding van een plan.

Werk bij iedere statuswijziging ook de totalen bovenaan, de planregel, de werkpakketregel en waar
van toepassing de `MOD-03`-moduleregel bij. `MOD-03` telt pas als afgerond werkpakket wanneer alle
acht moduleverhalen afgerond zijn. Laat een hervattende agent de tellingen opnieuw uit de tabellen
controleren; handmatig verouderde totalen zijn geen bewijs van voortgang.

Statussen:

- `NIET GESTART`: geen actieve story of wijziging;
- `BEZIG`: story, branch en laatste bewijs zijn ingevuld;
- `GEBLOKKEERD`: exacte blokkade, eigenaar en eerstvolgende actie zijn ingevuld;
- `AFGEROND`: gemerged en alle vereiste post-mergegates zijn groen;
- `VERVALLEN`: alleen na een expliciet, gedocumenteerd besluit.

Een hervattende agent vertrouwt nooit blind op `BEZIG`. Controleer trackerstatus, branch, PR,
werkboom, laatste commit en tests; noteer daarna wat werkelijk is aangetroffen.

## 8. Klaarcriteria per story

Een story is pas klaar als alle punten waar zijn:

- scope en acceptatiecriteria zijn aantoonbaar gerealiseerd;
- gerichte regressietests én volledige gates zijn groen;
- reviewer en tester hebben de uiteindelijke commit goedgekeurd;
- actuele docs en code spreken elkaar niet tegen;
- branch en PR zijn traceerbaar vanuit story en `VOORTGANG.md`;
- de wijziging is gemerged zonder bypass;
- post-mergeverificatie op de gemergede SHA is groen;
- geen tijdelijke allowlist, TODO, skip of compatibiliteitsfacade zonder expliciete verwijderstory is
  achtergebleven.
