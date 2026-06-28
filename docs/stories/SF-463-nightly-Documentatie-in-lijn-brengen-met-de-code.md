# SF-463 - nightly: Documentatie in lijn brengen met de code

## Story

nightly: Documentatie in lijn brengen met de code

<!-- refined-by-factory -->

## Scope

Breng de documentatie in `docs/factory/` weer in lijn met de huidige broncode van de Software Factory. Doel is dat de gedocumenteerde functionaliteit een juiste en volledige weergave is van wat de code daadwerkelijk doet.

In scope:
- Controleer en corrigeer de inhoud van `docs/factory/` (o.a. `functional-spec.md`, `technical-spec.md`, `development.md`, `deployment.md`, `README.md`, de `agents/*.md` rolbeschrijvingen en de `ux/`-documentatie) tegen de actuele code.
- Voeg ontbrekende functionaliteit toe aan de documentatie wanneer die wél in de code zit maar niet (correct) beschreven staat.
- Verwijder of corrigeer beschrijvingen die niet meer kloppen met de code (verouderde namen, paden, commando's, flows, enums, configuratie-opties).
- `docs/stories/` mag als input gebruikt worden om te begrijpen wáárom functionaliteit bestaat, maar wordt niet gewijzigd.

Expliciet buiten scope (harde grens):
- Geen enkele wijziging aan broncode (geen `.kt`, geen testbestanden, geen build/config-bestanden die het gedrag van de applicatie bepalen). Dit is een documentatie-only story.
- Geen wijzigingen aan `docs/stories/`.
- Geen functionele/gedragsveranderingen aan de applicatie.

## Acceptance criteria

- Er zijn uitsluitend wijzigingen in documentatiebestanden (primair onder `docs/factory/`); de diff bevat geen wijzigingen aan broncode, tests of build/config. (Reviewer keurt af bij elke code-wijziging.)
- De gewijzigde documentatie beschrijft de actuele functionaliteit correct: aangetroffen discrepanties tussen docs en code zijn opgelost, hetzij door de docs te corrigeren, hetzij door ontbrekende functionaliteit toe te voegen aan de docs.
- Functionaliteit die in de code aanwezig is maar in de documentatie ontbrak, is toegevoegd aan de juiste documentatiepagina(’s).
- Verwijzingen in de documentatie (bestandspaden, modulenamen, commando's, configuratie-opties, agent-rollen/fases) komen overeen met de werkelijke code.
- De worklog (`docs/stories/worklog/SF-463-worklog.md`) beschrijft welke discrepanties zijn gevonden en hoe ze zijn opgelost; als er geen discrepanties zijn gevonden, wordt dat expliciet als uitkomst vastgelegd.
- Bestaande build/tests blijven groen (er zijn immers geen code-wijzigingen); de tester hoeft niets te testen wanneer er geen code is aangepast.

## Aannames

- "De gehele documentatie" verwijst naar de documentatie in deze repo, met de nadruk op `docs/factory/`; `docs/stories/` is read-only context en geen doelwit voor wijzigingen.
- Bij twijfel is de code de bron van waarheid: de documentatie wordt aangepast aan de code, nooit andersom.
- Het is acceptabel dat de uiteindelijke wijziging klein of zelfs leeg is (afgezien van de worklog) als documentatie en code al in sync blijken; in dat geval wordt dat als expliciete uitkomst in de worklog vastgelegd.
- De `ux/wireframes*`-HTML/CSS-bestanden zijn ontwerp-artefacten; deze worden alleen aangepast als er een duidelijke, aantoonbare discrepantie met de code is, niet voor cosmetische verschillen.
- Scope-afbakening "documentatie" omvat Markdown-documentatie; README's en specs binnen `docs/` vallen eronder, applicatie-broncode en tests vallen er nadrukkelijk buiten.

## Eindsamenvatting

Ik heb alle context: `.task.md`, het worklog en de story-diff. Hier is de eindsamenvatting.

---

## Eindsamenvatting SF-463 — Documentatie in lijn brengen met de code

**Doel:** de documentatie onder `docs/factory/` weer kloppend maken met de actuele broncode van de Software Factory. Documentatie-only story: geen code-, test- of build-wijzigingen.

### Wat is gebouwd / gewijzigd
Aangepaste bestanden (alleen documentatie):
- `docs/factory/functional-spec.md`
- `docs/factory/technical-spec.md`
- `docs/factory/secrets-local.md`
- `docs/stories/worklog/SF-463-worklog.md` (verslag)

### Gevonden discrepanties en hoe opgelost
1. **`SF_YOUTRACK_PROJECTS` was als verplicht gedocumenteerd**, maar is optioneel (`REQUIRED_KEYS` = 5 keys; leeg = factory ontdekt zelf alle niet-gearchiveerde projecten). → verplaatst naar optioneel blok in `secrets-local.md`.
2. **Verkeerde default voor `SF_POLL_INTERVAL_MS`** (`15000` → werkelijk `1000`). → gecorrigeerd in `technical-spec.md` en `secrets-local.md`.
3. **Niet-gedocumenteerde config-vars die de code wél leest** (o.a. `SF_POLL_INTERVAL_IDLE_MS`, `SF_ACTIVE_PHASE_RECOVERY_DELAY_MS`, `SF_COST_MONITOR_INTERVAL_MS`, `SF_CREDITS_PAUSE_DEFAULT_MINUTES`, dashboard-vars `SF_DASHBOARD_*`, `SF_FACTORY_API_TOKEN`, `SF_PROJECTS_FILE`). → toegevoegd aan tuning-/dashboard-blokken.
4. **Nightly-scheduler verouderd** (migratie V13): docs zeiden nog "precies één run per dag" en uniek `run_date`. Werkelijkheid: meerdere runs/dag, `kind`-kolom (`scheduled`/`manual`), `NightlyJobStatus.CANCELLED`, handmatige run ("Run nu", `POST /nightly/run-now`) en onderbreken (`POST /nightly/stop`), plus aangepaste digest-timing. → herschreven in `technical-spec.md` en `functional-spec.md`.
5. **Verkeerd pad docker-compose** (`docker/docker-compose.yml` i.p.v. repo-root). → gecorrigeerd.

### Geverifieerd correct (geen wijziging nodig)
Agent-rollen/keten-volgorde, documentatie-fases en `*-with-questions`-fases, manual-approve-poort, overige tuning-defaults, en padverwijzingen in `agents/*.md`, `development.md`, `deployment.md`, `README.md`. UX-wireframes: geen aantoonbare code-discrepantie → conform scope niet cosmetisch aangepast.

### Keuzes
- Bij twijfel is de code de bron van waarheid; docs aangepast aan de code, nooit andersom.
- Interne rollen (`PLANNER`/`ASSISTANT`/`COST_MONITOR`/`ORCHESTRATOR`) bewust níét aan de functionele opsomming toegevoegd, omdat ze geen onderdeel van het zichtbare verhaal zijn.

### Tests
Geen build/tests gedraaid — bewust en conform acceptance criteria: het is een documentatie-only story zonder code-wijziging, dus bestaande build/tests blijven groen. Reviewer en tester hebben elke gewijzigde doc-claim los tegen de code geverifieerd en goedgekeurd.

### Bewust niet gedaan
- Geen broncode, tests of build/config gewijzigd.
- `docs/stories/` niet aangepast (alleen read-only input + het worklog).
- UX-wireframes niet cosmetisch bijgewerkt.
