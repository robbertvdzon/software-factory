# Plan 01 — Merge- en testinvariant

**Status:** NIET GESTART<br>
**Werkpakketten:** FIX-01, daarna VER-01<br>
**Model:** GPT-5.6 Sol<br>
**Effort:** High<br>
**Waarom dit niveau:** beide werkpakketten veranderen repositorybrede veiligheidsinvarianten. Een
fout kan een mergegate omzeilen, geldig werk permanent blokkeren of een rood testerresultaat als
groen accepteren.<br>
**Bron:** docs/verbeterplan-onderhoudbaarheid-2026-07.md<br>
**Voortgang:** docs/verbetertraject-2026-07/VOORTGANG.md

> Harde regel: geen enkele falende test mag worden genegeerd, ook niet wanneer de fout al bestond,
> flaky, omgevingsgebonden of ogenschijnlijk ongerelateerd is. Rood is een blocker; nooit een
> acceptabele uitzondering.

## Doel en resultaat

Na dit plan gebruikt ieder merge-entrypoint één projectbewuste, fail-closed policy en kan een
tester alleen goedkeuren met gestructureerd, groen bewijs voor de werkelijk geteste revision.
Pending CI wacht zonder permanente Error. De GitHub-check blijft een tweede, onafhankelijke gate.

## Prerequisites

Start uitsluitend wanneer:

1. de plandocumentatie van SF-925 is gemerged;
2. VOORTGANG.md plan 01 als NIET GESTART of hervatbaar GEBLOKKEERD toont;
3. de default branch actueel en groen is;
4. de worktree schoon is, afgezien van aantoonbaar eigendom van deze planuitvoering;
5. Docker beschikbaar is voor mvn verify.

Lees volledig vóór de eerste wijziging:

- docs/verbetertraject-2026-07/UITVOERREGELS.md;
- docs/verbetertraject-2026-07/VOORTGANG.md;
- docs/verbeterplan-onderhoudbaarheid-2026-07.md;
- dit bestand.

## Kopieerbare startopdracht

    Voer plan 01 volledig autonoom uit volgens
    docs/verbetertraject-2026-07/01-merge-en-testinvariant-high.md.

    Lees eerst UITVOERREGELS.md, VOORTGANG.md en het volledige bronplan. Maak voor FIX-01 en
    VER-01 elk een afzonderlijke, niet-gestarte Factory-story, branch en PR. Voer FIX-01 volledig
    uit, merge en verifieer die story, en begin pas daarna VER-01 vanaf de nieuwe groene default
    branch. Laat developer, reviewer en tester alle gates uitvoeren. Negeer geen enkele falende
    test. Push en merge alleen na alle verplichte groene checks en werk VOORTGANG.md bij iedere
    overdracht bij. Stop alleen bij een echte externe blokkade of onomkeerbare productbeslissing.

## Verplichte volgorde

1. FIX-01 volledig uitvoeren, reviewen, testen, mergen en post-merge verifiëren.
2. Opnieuw baselinen vanaf de gemergede default branch.
3. VER-01 volledig uitvoeren, reviewen, testen, mergen en post-merge verifiëren.
4. De planbrede eindcontrole uitvoeren en overdragen naar plan 03.

Werkpakketten mogen niet worden gecombineerd in één story of PR. Plan 02 mag alleen in een strikt
gescheiden worktree parallel lopen.

## Gemeenschappelijke baseline per story

Leg commando, exitcode, datum/tijd en tellingen vast in story/worklog en VOORTGANG.md:

    git status --short
    git log -1 --oneline
    mvn verify
    ./quality/run.sh

Een rode baseline wordt eerst hersteld volgens UITVOERREGELS.md.

Voor ieder gericht Mavencommando met `-Dsurefire.failIfNoSpecifiedTests=false` geldt aanvullend:
de optie is uitsluitend bedoeld om `-am`-dependency-modules zonder match niet te laten falen. Leg
uit vers gegenereerde Surefire-/Failsafe-rapporten vast dat iedere expliciete doelmodule waarvoor
het commando bewijs claimt meer dan nul geselecteerde tests heeft uitgevoerd. Nul tests in de
doelmodule is rood, ook bij exitcode 0; een oud rapport geldt niet als bewijs.

---

## FIX-01 — Centraliseer en herstel het mergebeleid

**Voorgestelde Factory-storytitel:** Projectbewuste groene merge-gate zonder bypass of pending-error<br>
**Prioriteit / omvang:** P0 / M

### Probleem en bewijs

- MergeSubtaskHandler controleert checks, maar ManualCommandService roept mergePullRequest
  rechtstreeks aan en omzeilt de applicatiegate.
- De verplichte check Backend verification is hardcoded voor alle target-repositories, terwijl
  projects.yaml technologisch verschillende repositories ondersteunt.
- Pending, missing en rood vallen nu in hetzelfde exception-/Error-pad. Een normaal lopende check
  kan daardoor permanente handmatige interventie vereisen.
- De huidige handler-KDoc en actuele specs beschrijven de merge nog als onvoorwaardelijk.

Controleer bij start de actuele regels rond:

- softwarefactory/.../pipeline/service/MergeSubtaskHandler.kt;
- softwarefactory/.../orchestrator/services/ManualCommandService.kt;
- factory-common/.../github/GitHubApi.kt en GitHubCliClient.kt;
- ProjectRepoResolver, projects.yaml en projects.yaml.example;
- bestaande merge-unit- en e2e-tests.

### Concrete stappen

1. Voeg eerst characterizationtests toe voor automatische en handmatige merge, inclusief de
   bestaande bypass.
2. Introduceer één smalle publieke merge-use-case-interface in een Modulith-publieke package-root
   en een interne implementatie. Alleen deze implementatie mag `GitHubApi.mergePullRequest`
   aanroepen, en uitsluitend met de door de gate geverifieerde head-SHA als mergepreconditie.
3. Laat MergeSubtaskHandler en ManualCommandService dezelfde use-case gebruiken.
4. Modelleer readiness getypeerd als minimaal `Ready(verifiedHeadSha)`, `Pending` en `Blocked`:
   - `Ready(verifiedHeadSha)`: alle vereiste checks zijn groen op exact deze actuele PR-head;
   - Pending: queued/in-progress; geen merge en geen Error, later opnieuw beoordelen;
   - Blocked: missing, skipped, cancelled, failed of ongeldig/API-onbetrouwbaar bewijs; fail-closed
     met bruikbare diagnose.
5. Zorg dat Pending niet in MERGING blijft hangen: beoordeel vóór de onomkeerbare faseovergang of
   zet gecontroleerd terug zonder Error en zonder wake-loop.
6. Maak required checks projectbewust. Gebruik een expliciete, gevalideerde projectconfig tenzij
   de actuele code al betrouwbaar branch-protectionbeleid kan lezen. Iedere mergebare repo moet een
   niet-lege policy hebben.
7. Verwijder dubbele checkliterals. Houd de huidige checknaam bruikbaar tot VER-02 later een
   stabiele aggregator invoert.
8. Valideer dat checks bij de actuele PR-head horen; bewijs van een oude SHA is nooit geldig.
9. Maak de laatste headcontrole en merge atomair aan GitHub-zijde: geef `verifiedHeadSha` als
   verwachte SHA mee aan de mergecall. Als de head tussen beoordeling en merge van A naar B
   verandert, moet GitHub de merge weigeren en moet de use-case opnieuw naar `Pending`/herbeoordeling
   gaan, zonder merge en zonder permanente `Error`.
10. Voeg een deterministische racetest toe voor head A groen → head B gepusht vlak vóór merge. Voer
    die test via zowel het automatische als het handmatige entrypoint uit en bewijs dat geen van
    beide B op basis van bewijs voor A kan mergen.
11. Werk projects.yaml.example, functional-spec, technical-spec, runbook en relevante KDoc bij.
12. Zoek na implementatie repositorybreed naar rechtstreekse mergePullRequest-aanroepen.

### Acceptatiecriteria

- Geen productiecode buiten de centrale mergeservice roept mergePullRequest aan.
- Automatische en handmatige merge gebruiken exact dezelfde policy.
- Ready merget eenmaal; Pending wacht zonder Error; alle Blocked-varianten mergen niet.
- Missing, skipped, cancelled, failed en API-/parsefouten zijn fail-closed.
- Twee testprojecten met verschillende checknamen werken onafhankelijk.
- Een groen resultaat voor een oude SHA wordt afgewezen.
- De geverifieerde head-SHA is een atomische mergepreconditie; een headwijziging tussen check en
  merge levert geen merge op en wordt veilig opnieuw beoordeeld.
- De head-racetest dekt zowel automatische als handmatige merge en bewijst dat beide dezelfde
  centrale SHA-preconditie gebruiken.
- Startup/configvalidatie geeft een duidelijke fout bij ontbrekende policy.
- Unit- en e2e-tests dekken beide entrypoints en alle readinessvarianten.
- Docs en code beschrijven dezelfde policy; geen bypass is toegevoegd.

### Gerichte verificatie

    rg -n 'mergePullRequest\(' softwarefactory/src/main factory-common/src/main
    mvn -pl factory-common,softwarefactory -am -Dsurefire.failIfNoSpecifiedTests=false test
    mvn -pl softwarefactory -am -Dtest='*Merge*Test,*ManualCommandServiceTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test
    ./quality/run.sh

Gebruik concrete testklassennamen wanneer de wildcard niet door de actuele build wordt ondersteund.

### Volledige verificatie

    mvn verify

Controleer daarna de PR-checks en voer een post-merge mvn verify uit op de gemergede SHA.

### Buiten scope

- Geen deploylogica veranderen.
- Geen branch-protectionbypass of adminbypass toevoegen.
- Geen algemene command- of pipeline-refactor; die volgt later.
- Nog geen Flutter-/image-aggregatorcheck bouwen; dat is VER-02.

### Reviewer- en tester-aandachtspunten

Reviewer controleert vooral dat werkelijk ieder mergepad centraal loopt, Pending geen Error of
busy loop veroorzaakt, de config fail-closed is en de geverifieerde SHA werkelijk als atomische
mergepreconditie wordt meegestuurd. Tester verifieert beide entrypoints onafhankelijk voor ready,
pending, missing, skipped, cancelled, failed, API-fout en de head A → B-race, steeds tegen de
uiteindelijke commit.

### Afronding FIX-01

Noteer story, branch, PR, merge-SHA, qualitydelta, gerichte tests en post-merge mvn verify in
VOORTGANG.md. Begin VER-01 pas wanneer FIX-01 daar AFGEROND is.

---

## VER-01 — Maak testergoedkeuring machine-verifieerbaar

**Voorgestelde Factory-storytitel:** Testergoedkeuring vereist groen revisiongebonden testbewijs<br>
**Prioriteit / omvang:** P1 / L<br>
**Afhankelijk van:** gemergede en groene FIX-01

### Probleem en bewijs

Agentprompts verbieden rood, maar AgentResultFile bevat geen gestructureerd bewijs. Een model kan
tested rapporteren zonder controleerbaar commando, exitcode of revision. Proza is geen gate. De
GitHub-check is een noodzakelijk laatste vangnet, maar bewijst niet dat het testerbesluit zelf
waarheidsgetrouw was.

### Concrete stappen

1. Leg eerst contract- en flowcharacterizationtests vast voor de huidige testeruitkomst.
2. Inventariseer eerst alle actieve doelrepositories uit de actuele projectcatalogus en
   `projects.yaml`. Leg per repo default branch, eigenaar, huidige verificatie en rolloutstatus vast;
   een repo ontbreekt niet stil omdat zij buiten deze checkout staat.
3. Definieer een versioned target-repoconfig, bij voorkeur `.factory/verification.yaml`, met stabiele
   command-id, argv zonder impliciete shell, working directory en timeout. Onbekende versies of
   ontbrekende verplichte config falen gesloten.
4. Rol de config uit naar **alle** actieve doelrepositories met per repo een afzonderlijke,
   traceerbare config-PR binnen de bestaande autorisatie. Ontbrekende schrijf-/mergeautorisatie is
   een expliciete externe blocker met repo, eigenaar en benodigde actie; het is geen reden om die
   repo over te slaan of fail-open te activeren.
5. Gebruik een compatibele tweefasenrollout: land eerst parser/contract en geldige config in iedere
   actieve repo terwijl bestaande callers blijven werken; activeer daarna pas de fail-closed
   enforcement. Valideer direct vóór activatie de config op de actuele base-branch-SHA. Nieuwe
   repositories mogen niet actief worden zonder vooraf gevalideerde config.
6. Laat agentworker na de tester-AI-run de verplichte commands zelf deterministisch uitvoeren.
   Het model mag geen exitcode of groenstatus aanleveren.
7. Leg per command backward-compatible bewijs vast in het gedeelde resultcontract: configversie,
   command-id, start/einde, duur, exitcode, timeout/toolingstatus, geteste HEAD/tree-identiteit en
   rapportlocatie of begrensde samenvatting.
8. Voeg uitsluitend optionele/defaulted velden toe, behoud bestaande JSON-veldnamen en bewijs
   round-trip, minimale oude payload en onbekende velden met contracttests.
9. Forceer bij ontbrekende tooling, timeout, ontbrekend bewijs, non-zero exit of revisionmismatch
   test-rejected met concrete diagnose.
10. Laat factory-completion iedere gemelde tested-uitkomst onafhankelijk valideren. Andere rollen
   blijven backward-compatible; testerbewijs is bewust fail-closed.
11. Voeg exact
    `softwarefactory/src/test/kotlin/nl/vdzon/softwarefactory/e2e/TesterVerificationEvidenceE2eTest.kt`
    toe. Deze Failsafe-test bewijst dat rood of revision-mismatched bewijs de volledige
    test-loopback naar development veroorzaakt en dat groen bewijs alleen voor dezelfde checkout
    wordt geaccepteerd.
12. Werk tester/developer/reviewer-instructies, docs-skeleton, functional-spec, technical-spec en
   runbook bij. Herhaal dat pre-existing, flaky en omgevingsfouten nooit groen zijn.
13. Leg een rolloutmatrix vast voor alle actieve doelrepositories, inclusief config-PR, base-SHA,
    validatieresultaat, activatiemoment en eventuele externe blocker. Enforcement mag pas aan als
    iedere actieve repo geldige config heeft; er is geen tijdelijke of blijvende fail-openroute.

### Acceptatiecriteria

- Geen testerresultaat bereikt tested zonder compleet groen gestructureerd bewijs.
- Bewijs hoort aantoonbaar bij de geteste revision/tree.
- Tooling ontbreekt, timeout en iedere non-zero exit leiden tot reject, nooit approve.
- Contract is backward-compatible voor oude niet-testerpayloads en additive evolutie.
- Oude/mismatched evidence en handgeschreven groen proza worden afgewezen.
- E2e bewijst reject → ketenreset → developer en een latere geldige groene doorgang.
- Iedere actieve doelrepo uit de projectcatalogus heeft een traceerbare config-PR en gevalideerde
  config op de actuele base branch voordat enforcement wordt geactiveerd.
- De rolloutmatrix is compleet; een repo zonder autorisatie staat als externe blocker geregistreerd
  en kan niet stil worden overgeslagen. Nieuwe actieve repositories zonder config worden geweigerd.
- `TesterVerificationEvidenceE2eTest` bestaat onder het exact voorgeschreven pad en draait als
  Failsafe-test via `verify`.
- De GitHub-mergegate uit FIX-01 blijft onafhankelijk actief.

### Gerichte verificatie

    mvn -pl factory-common -am -Dtest='*AgentResultFile*Test' \
      -Dsurefire.failIfNoSpecifiedTests=false test
    mvn -pl agentworker -am -Dsurefire.failIfNoSpecifiedTests=false test
    mvn -pl softwarefactory -am -Dtest='*AgentRunCompletion*Test' \
      -Dsurefire.failIfNoSpecifiedTests=false test
    mvn -pl softwarefactory -am -Dit.test=TesterVerificationEvidenceE2eTest \
      -Dsurefire.failIfNoSpecifiedTests=false verify
    ./quality/run.sh

De exact benoemde Failsafe-klasse is verplicht; een wildcard of kleinere test vervangt haar niet.

### Volledige verificatie

    mvn verify

De gerichte commando's vervangen deze rootgate niet en tests worden nergens geskipt. Controleer de
uiteindelijke PR-checks en herhaal `mvn verify` op de gemergede SHA.

### Buiten scope

- Geen CI-aggregator of branch-protectionmigratie; VER-02 doet dit.
- Geen generiek remote commandplatform bouwen.
- Geen testfailure als flaky/quarantine/pre-existing uitzonderen.
- Geen agent-imagebouw repareren; dat is FIX-03 in plan 02.

### Reviewer- en tester-aandachtspunten

Reviewer controleert contractcompatibiliteit, commandinjectie, timeouts, revisionbinding, begrensde
logs en het ontbreken van een proza-bypass. Tester manipuleert negatieve payloads en bewijst dat
missing evidence, oud bewijs, tool missing, timeout en non-zero steeds rejecten. Daarna draait de
tester de volledige suite tegen de uiteindelijke commit.

## Plan-afronding en overdracht

Plan 01 is pas AFGEROND wanneer beide stories afzonderlijk zijn gemerged, post-merge mvn verify
groen is, reviewer en tester akkoord zijn, docs actueel zijn en VOORTGANG.md al het bewijs bevat.

Overdracht naar plan 03:

- vermeld de publieke merge-use-case, readiness-types en projectconfiglocatie;
- vermeld de huidige verplichte checknamen en migratiestap voor Repository verification;
- vermeld schema/versie van verification.yaml en AgentResultFile-evidence;
- lever de volledige doelrepo-rolloutmatrix met config-PR's, gevalideerde base-SHA's,
  activatiestatus en eventuele expliciete externe blockers;
- meld iedere tijdelijke compatibilitykeuze met een concrete verwijderstory;
- bevestig expliciet dat geen falende test is genegeerd.

Plan 03 mag daarnaast pas starten wanneer ook plan 02 AFGEROND en groen is.
