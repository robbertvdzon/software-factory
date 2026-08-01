# SF-1562 - [Audit] Maak de opruiming van de `manualApprove`-projectvlag af in KDoc en agent-instructies

## Story

[Audit] Maak de opruiming van de `manualApprove`-projectvlag af in KDoc en agent-instructies

<!-- refined-by-factory -->

## Samenvatting

De factory kende vroeger een instelling per project die bepaalde of er vóór het mergen
handmatig moest worden goedgekeurd. Die instelling is inmiddels weg: alleen de
goedkeuringsstand van de story zelf bepaalt dat nog.

Op vier plekken staat die oude uitleg er nog, en op één plek staan Engelse namen die
niet overeenkomen met wat je in de tracker ziet. Dat is verwarrend voor iedereen die
het opzoekt, en één van die teksten wordt letterlijk aan een AI-agent meegegeven.

Deze story werkt die teksten bij zodat ze kloppen. Er verandert niets aan hoe de
factory werkt.

## Scope

Alleen tekstwijzigingen (KDoc + markdown). Geen gedragswijziging, geen migratie, geen
signatuur- of configwijziging.

1. `softwarefactory/src/main/kotlin/nl/vdzon/softwarefactory/core/contracts/WorkflowModels.kt`
   regel 58 en 61 — de KDoc op `ApprovalMode.MANUAL_GATE_ONLY` en `ApprovalMode.EVERY_STEP`
   zegt nu "de manual-approve-poort volgt de project-config". Vervang dat door de geldende
   regel: bij beide waarden wordt de poort aangemaakt, bij `AUTOMATIC` overgeslagen. Bron voor
   de formulering: `docs/factory/technical-spec.md:239-240`, `docs/factory/functional-spec.md:108-112`
   en de KDoc op `SubtaskPlanMaterializer.manualApproveSpecs` (`SubtaskPlanMaterializer.kt:154-161`).
   De KDoc op `AUTOMATIC` (regel 55) klopt al en blijft ongewijzigd.
2. `docs/factory/agents/planner.md` regel 12 — verwijder "(indien geconfigureerd)" achter
   `manual-approve`, of vervang het door een verwijzing naar de goedkeuringsstand van de story.
3. `factory-common/src/main/resources/docs-skeleton/docs/factory/agents/planner.md` regel 12 —
   identieke regel, identieke wijziging. Beide kopieën moeten mee en moeten na afloop woordelijk
   gelijk blijven.
4. `docs/technical/overview.md` regel 74 en 76 — vervang de Kotlin-enumnamen `manual-gate-only`
   en `every-step` door de tracker-waarden `alleen-manual-poort` en `elke-stap`, gelijk aan
   regel 63 in hetzelfde document. De inhoud van beide zinnen klopt verder en blijft ongewijzigd.

Buiten scope: `docs/stories/**` (historische story- en worklog-bestanden met `manualApprove`-
verwijzingen zijn een archief van wat toen gold en blijven ongemoeid), en het opnieuw uitrollen
van de skeleton naar target-repo's waar al een oude `planner.md` staat.

## Acceptance criteria

1. De KDoc op `ApprovalMode.MANUAL_GATE_ONLY` en `ApprovalMode.EVERY_STEP` beschrijft dat de
   `manual-approve`-poort bij deze waarden wordt aangemaakt, en bevat geen verwijzing meer naar
   project-config.
2. Repo-breed levert een zoektocht naar `manualApprove` / `manualApproveFor` / "project-config"
   in combinatie met de manual-approve-poort geen treffers meer op in `**/src/main/**`,
   `docs/factory/**` en `docs/technical/**` (treffers in `docs/stories/**` zijn toegestaan).
3. `docs/factory/agents/planner.md:12` bevat geen "(indien geconfigureerd)" meer; de opsomming van
   vaste afsluiters (`documentation`, `manual-approve`, `merge`, `deploy`) blijft verder intact,
   inclusief de instructie dat de planner die niet zelf declareert.
4. `factory-common/src/main/resources/docs-skeleton/docs/factory/agents/planner.md` is woordelijk
   identiek aan `docs/factory/agents/planner.md` (te controleren met een `diff` van beide bestanden,
   die leeg is).
5. `docs/technical/overview.md` bevat nergens meer de strings `manual-gate-only` of `every-step`;
   regel 74 en 76 gebruiken `alleen-manual-poort` en `elke-stap` en de rest van beide zinnen is
   ongewijzigd.
6. De build blijft groen (`mvn verify` over de reactor); er zijn geen test- of gedragswijzigingen
   nodig, dus bestaande tests draaien ongewijzigd door.

## Aannames

- "Puur tekst" betekent dat er geen enkele Kotlin-declaratie, enumwaarde, `trackerValue` of
  signatuur verandert — alleen commentaar- en markdownregels.
- Voor punt (2)/(3) is de voorkeursvorm het simpelweg schrappen van "(indien geconfigureerd)";
  een korte verwijzing naar de goedkeuringsstand van de story is ook acceptabel, mits beide
  kopieën exact dezelfde formulering krijgen.
- De fail-safe in `manualApproveSpecs` (poort wél toevoegen als de parent-story niet gelezen kan
  worden) is een implementatiedetail van de materializer en hoeft niet in de `ApprovalMode`-KDoc
  herhaald te worden; die KDoc beschrijft de betekenis van de enumwaarden.
- Target-repo's waar de skeleton al is uitgerold houden hun oude `planner.md`, omdat
  `DocsSkeletonInstaller` met `overwrite=false` werkt. Dat is bekend en wordt hier niet opgelost;
  de fix geldt voor nieuwe uitrollen en voor deze repo zelf.
- Er bestaat geen geautomatiseerde drift-check tussen `docs/factory/agents/planner.md` en de
  skeleton-kopie; gelijkhouden is dus handwerk en wordt via criterium 4 geborgd.

## Eindsamenvatting

## Eindsamenvatting SF-1562 — Opruiming `manualApprove`-projectvlag in KDoc en agent-instructies

### Wat is gebouwd
Een pure tekstcorrectie op vier plekken waar nog stond dat de `manual-approve`-poort van een (inmiddels verwijderde) projectinstelling afhing. Vanaf nu klopt de documentatie met het werkelijke gedrag: alleen de goedkeuringsstand van de story bepaalt de poort.

1. **`WorkflowModels.kt`** — KDoc op `ApprovalMode.MANUAL_GATE_ONLY` en `ApprovalMode.EVERY_STEP`: "de manual-approve-poort volgt de project-config" → "de manual-approve-poort wordt aangemaakt". KDoc op `AUTOMATIC` bleef ongewijzigd (die klopte al).
2. **`docs/factory/agents/planner.md`** — "(indien geconfigureerd)" achter `manual-approve` geschrapt; de opsomming `documentation`/`manual-approve`/`merge`/`deploy` en de instructie dat de planner die niet zelf declareert zijn intact gebleven.
3. **`factory-common/.../docs-skeleton/.../planner.md`** — woordelijk dezelfde wijziging; beide kopieën zijn nu byte-identiek.
4. **`docs/technical/overview.md`** (regel 74/76) — Kotlin-enumnamen `manual-gate-only`/`every-step` vervangen door de tracker-waarden `alleen-manual-poort`/`elke-stap`, gelijk aan regel 63 in hetzelfde document.

Netto: 4 bestanden, 8 gewijzigde regels. Geen enkele Kotlin-declaratie, enumwaarde, `trackerValue` of signatuur aangeraakt — **nul gedragswijziging**.

### Gemaakte keuzes
- **Schrappen boven herformuleren**: in `planner.md` is "(indien geconfigureerd)" simpelweg weggehaald in plaats van vervangen door een verwijzing naar de goedkeuringsstand. Kortst mogelijke tekst die klopt, en makkelijk identiek te houden in beide kopieën.
- **Fail-safe niet herhaald in de KDoc**: dat de materializer de poort tóch toevoegt als de parent-story onleesbaar is, is een implementatiedetail van `SubtaskPlanMaterializer`; de `ApprovalMode`-KDoc beschrijft de betekenis van de enumwaarde.
- **`functional-spec.md` en `technical-spec.md` niet aangepast**: die beschreven het huidige gedrag al correct en dienden juist als bron voor de nieuwe formulering.
- **Geen nieuwe tests**: de wijziging bestaat volledig uit commentaar en markdown en heeft geen runtime-uiting die te testen valt.

### Wat is getest
- **Volledige build**: `mvn -B clean verify` over de reactor → BUILD SUCCESS, 938 tests over 5 modules, 0 failures / 0 errors / 0 skipped, geen flakes.
- **`tools/audit-documentation`** → `documentation-audit/v1: PASS`.
- **Alle 6 acceptatiecriteria machinaal nagelopen** door zowel reviewer als tester: `diff` van de twee `planner.md`-kopieën is leeg; geen treffers meer op `manual-gate-only`/`every-step` in `overview.md`; repo-brede grep bevestigt dat geen tekst in `**/src/main/**`, `docs/factory/**` of `docs/technical/**` de poort nog aan project-config koppelt (resterende `manualApprove*`-hits zijn functienamen, `project-config`-hits gaan over `projects.yaml` in het algemeen).
- **Extra gedragsbewijs**: de tester heeft `docs-skeleton/docs/factory/agents/planner.md` uit de gebouwde `factory-common`-jar gepakt en woordelijk identiek bevonden aan het repo-bestand — een nieuwe uitrol geeft de planner-agent dus de gecorrigeerde instructie mee.

### Bewust niet gedaan
- **`docs/stories/**` ongemoeid gelaten**: historische story- en worklogbestanden met `manualApprove`-verwijzingen zijn een archief van wat toen gold.
- **Skeleton niet opnieuw uitgerold** naar target-repo's waar al een `planner.md` staat: `DocsSkeletonInstaller` werkt met `overwrite=false`, dus die repo's houden hun oude tekst. Bekend en hier niet opgelost — de fix geldt voor nieuwe uitrollen en voor deze repo zelf. *(Aandachtspunt voor de PO: wil je de bestaande target-repo's alsnog bijwerken, dan is dat een aparte story.)*
- **Geen drift-check toegevoegd** tussen `docs/factory/agents/planner.md` en de skeleton-kopie. Gelijkhouden blijft handwerk, nu alleen geborgd via het acceptatiecriterium. *(Tweede aandachtspunt: een geautomatiseerde `diff`-gate zou herhaling van dit soort drift voorkomen.)*
