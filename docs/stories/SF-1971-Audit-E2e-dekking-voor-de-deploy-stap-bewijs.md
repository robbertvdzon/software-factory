# SF-1971 - [Audit] E2e-dekking voor de deploy-stap: bewijs dat de subtaak ná de merge de échte story-run leest

## Story

[Audit] E2e-dekking voor de deploy-stap: bewijs dat de subtaak ná de merge de échte story-run leest

<!-- refined-by-factory -->

## Samenvatting

De deploy-stap van de factory is de enige stap zonder end-to-end test, en is in acht dagen twee keer stukgegaan in productie. De laatste keer bleef de deploy hangen omdat de deploy-stap na de merge naar de verkeerde administratie keek en daardoor niet meer wist welke bestanden de story had gewijzigd.

De bestaande tests konden dat niet zien: die draaien elk onderdeel los, terwijl de fout juist ontstaat in het samenspel van merge en deploy.

Deze story voegt één nieuwe end-to-end test toe die de hele keten echt doorloopt en controleert dat alleen de deploy-doelen meedoen die de story ook werkelijk raakt. Er verandert niets aan de werking van de factory zelf; het is alleen testcode.

## Scope

Uitsluitend testcode in `softwarefactory/src/test`. Geen productiecode, geen migraties, geen docs-wijzigingen.

1. **Tweede projectnaam met deploy-doelen** in `E2eTestConfig.kt` (rond r68-73). Voeg naast `"sample"` de naam `"sample-deploy"` toe die naar dezelfde `LOCAL_REMOTE.path` wijst, en vul de bestaande maar hier ongebruikte `deployTargets`-parameter van `ProjectConfiguration` (`factory-common/.../ProjectConfiguration.kt:205`) met twee doelen van type `DeployConfig.OpenshiftWatch`:
   - doel A met `matchPaths` die de gefakete story-diff wél raakt;
   - doel B met `matchPaths` die de diff níet raakt.

   `"sample"` blijft ongewijzigd, zodat alle bestaande e2e-tests exact hetzelfde blijven doen (`DeploySubtaskHandler.kt:145` leest de projectnaam uit `parent.fields.repo`).

2. **`@Bean @Primary`-testdubbel voor `DeploymentStatusProbe`** in `E2eTestConfig`, volgens hetzelfde overschrijfpatroon dat daar al voor GitHub, Telegram en de agent-runtime gebruikt wordt. De dubbel geeft alléén voor de namespace/deployment van doel A een niet-lege image terug en voor doel B `null`. Bewust `OpenshiftWatch` en niet `RestRestart`: die laatste doet een echte HTTP-call (`DeploySubtaskHandler.kt:330-340`) zonder vervangbare poort. De doelen krijgen géén `argocdApp`/`argocdNamespace`, zodat de image-route (`openshiftWatchReady`, r417-434) geldt en niet de ArgoCD-route.

3. **`changedFiles` overschrijven op de e2e-`FakeGitHubApi`** (`softwarefactory/src/test/.../e2e/FakeGitHubApi.kt`, niet de gelijknamige klasse in `testsupport/`). `GitHubApi.changedFiles` heeft een default die `null` teruggeeft (`factory-common/.../GitHubApi.kt:99`) en `matchedTargets` behandelt `null` fail-open (`DeploySubtaskHandler.kt:104-109`); zonder deze override matchen álle doelen altijd en test de rest niets. De fake geeft een vaste padenlijst terug die doel A raakt en doel B niet.

4. **Eén nieuwe e2e-klasse** `DeployTargetsE2eTest : E2eTestBase()` met één test: story met `Repo = sample-deploy`, scripted developer die het `github-pr`-event rapporteert (zoals `HotfixChainE2eTest.kt:29`), echte lokale squash-merge, daarna wachten tot de merge-subtaak op `merge-approved` en de deploy-subtaak op `deploy-approved` staat.

5. **Kleine, default-behoudende uitbreiding van `E2eTestBase.createStory`**: een optionele `repo`-parameter met default `"sample"`, omdat `createStory` het `Repo`-veld nu hard op `"sample"` zet (r93) en direct daarna `Story Phase = start`. Bestaande aanroepers veranderen niet.

Buiten scope: wijzigen van `"sample"`, van bestaande e2e-klassen (afgezien van de default-behoudende `createStory`-parameter), en elke aanpassing aan `DeploySubtaskHandler` of andere productiecode.

## Acceptance criteria

1. `E2eTestConfig` levert een `ProjectConfiguration` met zowel `"sample"` (ongewijzigd, inclusief bestaande `requiredChecks`) als `"sample-deploy"` → dezelfde `LOCAL_REMOTE.path`, waarbij `"sample-deploy"` exact twee `OpenshiftWatch`-deploy-doelen met niet-lege, elkaar uitsluitende `matchPaths` heeft en `"sample"` géén deploy-doelen krijgt.
2. Er is een `@Bean @Primary`-`DeploymentStatusProbe` in `E2eTestConfig` die voor doel A een niet-lege image en voor doel B `null` teruggeeft; de echte `KubectlDeploymentStatusProbe` wordt in e2e-runs nooit aangeroepen.
3. De e2e-`FakeGitHubApi` overschrijft `changedFiles(targetRepo, prNumber)` en geeft een vaste, niet-lege padenlijst terug die de `matchPaths` van doel A raakt en die van doel B niet.
4. `DeployTargetsE2eTest` bestaat, erft van `E2eTestBase`, en de test doorloopt met `Repo = sample-deploy` de keten tot en met `merge-approved` en `deploy-approved` binnen zijn eigen await-timeout.
5. Handmatig gecontroleerd faalbewijs: met `changedFiles` tijdelijk terug op `null` (fail-open) loopt `DeployTargetsE2eTest` níet groen — hij blijft op de deploy-subtaak wachten en loopt in zijn timeout. Deze tijdelijke wijziging staat niet in de uiteindelijke diff; de waarneming wordt in de PR-/story-toelichting vastgelegd.
6. Alle bestaande e2e-klassen blijven inhoudelijk ongewijzigd en groen; de enige toegestane aanpassing aan bestaand testmateriaal zijn de additieve uitbreidingen in `E2eTestConfig`, `FakeGitHubApi` en de optionele `repo`-parameter op `createStory`.
7. Volledige `mvn verify` (Docker vereist) is groen, inclusief Detekt/quality-ratchet.

## Aannames

- **Faalmodus bij ontbreken van de fix.** Met het oude `openOrCreate`-gedrag ontstaat na de merge een lege spookrun zonder repo/PR-nummer → `changedPaths` is `null` → fail-open → doel B doet mee → de probe geeft daar nooit een image → de subtaak bereikt nooit `deploy-approved`. AC 5 simuleert precies dat effect via `changedFiles = null`, omdat de productiecode niet gewijzigd mag worden.
- **Geen ArgoCD-config op de doelen**, zodat `openshiftWatchReady` de image-heuristiek gebruikt en de SHA-verificatie geen rol speelt.
- **`timeoutMinutes` van beide doelen ruim** (orde 30 minuten) kiezen, zodat in het faalscenario de await-timeout van de test de uitkomst bepaalt en niet `DEPLOY_FAILED` — beide bewijzen falen, maar de await-timeout is het scherpste signaal en houdt het geslaagde pad ongevoelig voor trage CI.
- **`requiredChecks` voor `"sample-deploy"` is niet nodig**: de e2e-`FakeGitHubApi.requiredChecks` geeft onvoorwaardelijk `PullRequestChecksResult.Ready` terug.
- **`deploy` zit gegarandeerd in de keten**: het is een factory-afgedwongen subtaaktype (`E2eTestBase.ENFORCED_SUBTASK_TYPES`), dus de planner hoeft er niets voor te leveren.
- **Één test in de nieuwe klasse volstaat**; er komt geen aparte negatieve testcase in de codebase, want fail-open-gedrag forceren zou een permanente productiecode-schakelaar vragen.
- De story-key van de nieuwe test is uniek binnen de e2e-suite (gedeelde tracker-state per test-JVM).

## Eindsamenvatting

# Eindsamenvatting SF-1971 — E2e-dekking voor de deploy-stap

## Wat is gebouwd
Er is één nieuwe end-to-end test toegevoegd die de volledige story-keten (refine → plan → develop → review → test → summary → documentation → merge → deploy) doorloopt en bewijst dat ná de merge alleen de deploy-doelen meedoen die de story werkelijk raakt. Dit was tot nu toe de enige factory-stap zonder e2e-dekking, en juist daar ging het in acht dagen twee keer mis in productie.

Concreet:
1. **Tweede testproject `sample-deploy`** in de e2e-configuratie, met exact twee deploy-doelen: één dat de gefakete story-diff wél raakt (`backend/`) en één dat hem níet raakt (`frontend/`). Het bestaande testproject `sample` is inhoudelijk ongewijzigd, zodat alle bestaande e2e-tests exact hetzelfde blijven doen.
2. **Testdubbel voor de deploy-statuscontrole**, die alleen voor het geraakte doel een "live"-signaal geeft. De echte kubectl-gebaseerde controle wordt in e2e-runs nooit aangeroepen.
3. **Vaste lijst gewijzigde bestanden op de e2e-GitHub-fake**, want zonder die override valt het pad-filter "fail-open" terug en zou de test niets bewijzen.
4. **Nieuwe testklasse `DeployTargetsE2eTest`** met één test die tot en met `merge-approved` en `deploy-approved` doorloopt, en daarna via een read-only poort assert dat precies één (het geraakte) deploy-doel meedeed.
5. Kleine, default-behoudende uitbreiding van de gedeelde testbasis (optionele `repo`-parameter), zodat bestaande aanroepers niet wijzigen.

**Geen productiecode gewijzigd** — de diff bestaat uit 4 testbestanden plus het worklog.

## Belangrijkste keuzes
- **Gewone story-keten, niet de kortere hotfix-keten**: het productie-incident zat juist in een normale story-run.
- **Deploy-doelen van het type "OpenshiftWatch" zonder ArgoCD-config**: alleen dat type is testbaar zonder echte HTTP-call, en zonder ArgoCD geldt de image-route, waardoor SHA-verificatie geen rol speelt.
- **Ruime doel-timeout (30 min) tegenover een test-timeout van 180 s**: in een faalscenario is de test-timeout het scherpste signaal, en het geslaagde pad blijft ongevoelig voor trage CI.
- **Bewuste afwijking van een story-aanname**: de aanname dat `sample-deploy` geen `requiredChecks` nodig had, klopte niet — de merge-service eist bij opstarten een merge-policy voor élke projectnaam, anders valt de hele Spring-context om. `sample-deploy` heeft daarom dezelfde policy als `sample`; inhoudelijk irrelevant, omdat de e2e-fake checks onvoorwaardelijk als "Ready" teruggeeft. Dit is door reviewer en tester onafhankelijk nagerekend.

## Wat is getest
- **Volledig vangnet** (`mvn verify` vanaf repo-root): BUILD SUCCESS in ~5 min, 1117 tests, 0 failures / 0 errors / 0 skipped, alle e2e-klassen groen.
- **Nieuwe test**: groen in ~9 s.
- **Faalbewijs (AC 5), handmatig door de developer**: met het pad-filter tijdelijk terug op fail-open loopt de nieuwe test níet groen — de merge komt door, maar de deploy blijft hangen omdat het niet-geraakte doel meedoet en daar nooit een image verschijnt (timeout na ~189 s). Die tijdelijke wijziging is teruggedraaid en staat niet in de diff. De test bewijst dus aantoonbaar iets: hij faalt bij precies de fout die in productie optrad.

## Bewust niet gedaan
- Geen wijziging aan `DeploySubtaskHandler` of andere productiecode, geen migraties, geen docs-wijzigingen (de bestaande e2e-beschrijving in `development.md` blijft onverkort geldig).
- Geen permanente negatieve testcase in de codebase: fail-open-gedrag afdwingen zou een blijvende schakelaar in productiecode vragen. Het bewijs is daarom eenmalig handmatig geleverd en hierboven vastgelegd.
- Het `RestRestart`-deploytype is niet e2e-gedekt (doet een echte HTTP-call zonder vervangbare poort).
- De tester heeft het handmatige faalbewijs niet herhaald (vereist een tijdelijke codewijziging, buiten de testerrol), maar heeft de dragende code-keten wel nagelopen en sluitend bevonden.

## Aandachtspunten (niet blokkerend)
- De quality-ratchet (`./quality/run.sh`) is rood met 4 bevindingen; machinaal aangetoond pre-existent (identieke lijst op een schone worktree op HEAD) en allemaal in `src/main`, dat deze story niet raakt. De ratchet zit bewust niet in de blokkerende verificatie.
- Twee cosmetische reviewpunten: een constante kan `private`, en één slot-assertie kan binnen deze timeout nooit afgaan (documenteert intentie, kost niets).

<!-- deploy-summary:start -->
De stap die nieuwe software live zet, wordt nu automatisch gecontroleerd voordat er iets wordt uitgeleverd. Daardoor merken we het voortaan meteen als die stap per ongeluk onderdelen probeert uit te rollen die helemaal niet bij de aanvraag horen — de oorzaak van twee eerdere storingen. Aan de werking van de omgeving zelf verandert niets.
<!-- deploy-summary:end -->
