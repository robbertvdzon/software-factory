# SF-1453 - Onderbroken refining-fase wordt leeggemaakt en blokkeert de start-next-wachtrij

## Story

Onderbroken refining-fase wordt leeggemaakt en blokkeert de start-next-wachtrij

<!-- refined-by-factory -->

## Samenvatting
Als de refining-fase van een story onderbroken wordt (bijvoorbeeld door een factory-herstart), wordt de fase nu leeggemaakt in plaats van teruggezet naar een bruikbare status. Een lege fase betekent "pak dit nooit meer op", en de bijbehorende story-run blijft open staan — dat blokkeert daarna alle andere wachtende stories voor hetzelfde repo, zonder dat iemand dat merkt. Dit moet zowel structureel opgelost worden (nooit meer een lege fase na recovery) als zichtbaar gemaakt worden (een langdurig openstaande run mag niet meer uren onopgemerkt blijven).

## Scope
- In `StoryRefinementCoordinator.recoverActiveStoryPhase`: laat de retry-reset voor fase `REFINING` teruggaan naar `StoryPhase.START` in plaats van `null`, symmetrisch met de bestaande `PLANNING -> REFINED_APPROVED`-reset.
- Er zijn geen andere actieve fasen dan `REFINING` en `PLANNING` (zie `StoryPhase.activeRole`), dus met deze fix zet geen enkele recovery-route de story-fase nog op leeg.
- Voeg zichtbaarheid toe voor een langdurig openstaande story-run die de per-repo-wachtrij blokkeert (`StoryRunRepository.activeRunForRepo`). Kies zelf de eenvoudigste vorm (bijv. een WARN-logregel per poll zodra een open run ouder is dan een drempel, of een veld/badge in het dashboard) — een zichtbare melding heeft de voorkeur boven een automatische afsluiting die werk kan weggooien.
- Buiten scope: het automatisch sluiten/opruimen van oude story-runs, en wijzigingen aan het gedrag van de `planning`-recovery.

## Acceptance criteria
- Een story die in fase `refining` onderbroken wordt (geen afgeronde agent-run, geen lopende agent, recovery-delay verstreken) krijgt na recovery fase `start`, niet leeg.
- Er is een (nieuwe of aangepaste) test die dit exact vastlegt: actieve fase `refining`, geen agent-run, `activePhaseRecoveryDelay` verstreken → verwacht resultaat `start`, niet `<empty>`.
- Het bestaande gedrag van de `planning`-recovery (terugval naar `refined-approved`) blijft ongewijzigd en blijft gedekt door de bestaande tests.
- Geen enkele recovery-route in `StoryRefinementCoordinator` zet `TrackerField.STORY_PHASE` nog op een lege/`null`-waarde.
- Een story-run die de per-repo-wachtrij blokkeert doordat hij al langer dan een redelijke drempel open staat, is zichtbaar (log, dashboard of notificatie) zodat dit niet meer uren onopgemerkt kan blijven; de gekozen vorm mag geen automatische, mogelijk werk-verlietende afsluiting zijn.

## Aannames
- "Redelijke drempel" voor de zichtbaarheidsmelding mag door de developer gekozen worden (bijv. in dezelfde orde van grootte als `hardTimeout` of een aparte, nieuwe setting) — de story schrijft geen exacte waarde voor.
- De vorm van de melding (log-regel vs. dashboard-badge vs. Telegram) is een implementatiekeuze; een WARN-logregel per poll is als minimale, veilige uitvoering acceptabel zolang het probleem daarmee aantoonbaar zichtbaar wordt.

## Eindsamenvatting

I heb voldoende context. Ik stel nu de eindsamenvatting voor de PO op.

## Eindsamenvatting SF-1453 — Onderbroken refining-fase wordt leeggemaakt en blokkeert de start-next-wachtrij

**Wat is gebouwd**

1. **Kernfix (structureel):** `StoryRefinementCoordinator.recoverActiveStoryPhase` zette een onderbroken `REFINING`-fase bij recovery voortaan terug naar `start` in plaats van leeg — symmetrisch met de bestaande `PLANNING → refined-approved`-terugval. `retryReset` is nu niet-nullable, zodat geen enkele recovery-route `STORY_PHASE` nog op leeg kan zetten.
2. **Zichtbaarheid (structureel):** een langdurig openstaande story-run die de start-next-wachtrij voor hetzelfde repo blokkeert, wordt nu zichtbaar gemaakt via een WARN-logregel (storyKey, run-id, target-repo, openstaande duur) zodra hij ouder is dan een configureerbare drempel. Geen automatische sluiting — bewust gekozen om geen werk weg te gooien, conform de story.
3. Ondersteunende wijzigingen: `StoryRunRecord.startedAt` toegevoegd (bestaande DB-kolom `story_runs.started_at` werd nog niet gelezen), nieuwe setting `blockedQueueWarnThreshold` (default 4 uur, env `SF_BLOCKED_QUEUE_WARN_THRESHOLD_MINUTES`), losstaand van de bestaande `hardTimeout`.

**Keuzes**
- De drempelwaarde en vorm van de melding waren vrij te kiezen; gekozen is voor een ruime default (4u) en een WARN-logregel als eenvoudigste, veilige oplossing — zoals de story als voorkeur aangaf boven een dashboard-badge of automatische afsluiting.
- `InMemoryStoryRunRepository` kreeg een test-only overload om een "oude" open run te simuleren, zonder de productie-interface te wijzigen.

**Getest**
- Twee nieuwe unit tests: `StoryPhaseRecoveryTest` (legt exact het AC-scenario vast: refining zonder agent-run na verstreken recovery-delay → `start`, plus regressiecheck voor de ongewijzigde planning-terugval) en `QueuedStoryBlockedWarningTest` (WARN wel/niet over de drempel).
- Volledige `mvn verify` over alle modules: BUILD SUCCESS, 0 failures/errors (incl. 659 unit + 70 e2e-tests in softwarefactory). Een eerder waargenomen tijdgevoelige e2e-flake (`TesterVerificationEvidenceE2eTest`) bleek pre-existent en niet gerelateerd aan deze wijziging.
- Documentatie (`technical-spec.md`) bijgewerkt met de nieuwe env-var.

**Bewust niet gedaan**
- Geen automatisch sluiten/opruimen van oude story-runs (expliciet buiten scope).
- Geen wijziging aan het gedrag van de `planning`-recovery.
- Geen dashboard-badge of notificatie-integratie; de WARN-logregel is als minimale, veilige vorm gekozen.
