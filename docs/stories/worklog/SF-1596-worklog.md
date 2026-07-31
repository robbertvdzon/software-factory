# SF-1596 - Worklog

Story-context bij eerste pickup:
Fullscreen screenshot-viewer met PageView, positie-indicator en desktop-navigatie

Pas dashboard-frontend/lib/screens/screenshots_screen.dart aan: maak _ScreenshotViewer stateful en laat 'm de volledige screenshotlijst + startindex ontvangen in plaats van losse title/imageUrl, met een horizontale, niet-circulaire PageView.builder. Per pagina: InteractiveViewer rond Image.network met de bestaande url /api/v1/stories/<key>/screenshots/<id>/image en state.api.authHeaders(), plus een loadingBuilder (laadindicator) en errorBuilder met dezelfde broken-image-fallback als het grid-kaartje (regel 62). Zorg dat zoomen per pagina werkt zonder het horizontale swipen te blokkeren in niet-ingezoomde staat (bijv. pannen pas bij zoom > 1); zoomstand hoeft niet bewaard te blijven bij paginawissel. AppBar toont de name van het zichtbare screenshot, en bij meer dan een screenshot ook een positie-indicator '<huidige> / <totaal>' die meeloopt bij swipe, knop en toets. Bij meer dan een screenshot: overlay pijlknoppen links/rechts binnen het bestaande app-thema en bediening met de pijltjestoetsen, met focus die bij openen wordt gezet. Aan de eerste pagina doet vorige niets, aan de laatste doet volgende niets (geen wrap, geen foutmelding). Bij precies een screenshot: geen pijlknoppen en geen teller (ook niet '1 / 1'). Pas de aanroep in de grid-itemBuilder (regels 45-53) aan zodat de al opgehaalde screenshots-lijst en de index worden meegegeven; laat de gridweergave en de publieke signature van ScreenshotsScreen ongewijzigd zodat het pad vanuit story_detail_screen.dart:347 blijft werken. Geen backend-wijziging. Voeg een widget-test toe in dashboard-frontend/test/screens/ volgens het patroon van de bestaande *_screen_test.dart-bestanden, die minimaal acceptatiecriteria 1 (openen op aangetikte index en heen/terug bladeren), 3 (naam + positie-indicator lopen mee) en 6 (een screenshot: geen pijlen, geen teller) afdekt. Rond af met een schone flutter analyze en groene flutter test, en met een zelfreview van de wijziging.

In eigen woorden: de fullscreen-viewer toonde één los plaatje. Nu krijgt hij de hele
screenshotlijst van de story mee plus de aangetikte startindex, zodat je met swipe,
pijlknoppen en pijltjestoetsen door de reeks bladert, met naam en `<huidige> / <totaal>`
in de appbar. Bij één screenshot verandert de weergave niet.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- `dashboard-frontend/lib/screens/screenshots_screen.dart`:
  - `_ScreenshotViewer` is stateful en ontvangt `storyKey` + de al opgehaalde
    `screenshots`-lijst + `initialIndex` in plaats van losse `title`/`imageUrl`; de
    grid-`itemBuilder` geeft die door. De publieke `ScreenshotsScreen`-signature en de
    gridweergave zijn ongewijzigd, dus het pad vanuit `story_detail_screen.dart:347` blijft werken.
  - Horizontale, niet-circulaire `PageView.builder`; `onPageChanged` houdt de index bij zodat
    naam en teller meelopen bij swipe, knop en toets.
  - AppBar: naam van het zichtbare screenshot; alleen bij meer dan één screenshot ook de
    positie-indicator (dus nooit `1 / 1`).
  - Overlay-pijlknoppen (`IconButton.filledTonal`, app-thema) en `Focus(autofocus: true)` met
    `onKeyEvent` voor links/rechts. Aan de randen is de knop `onPressed: null` en doet `_goTo`
    niets: geen wrap, geen foutmelding.
  - Nieuwe `_ZoomableScreenshot`: `InteractiveViewer` met eigen `TransformationController` per
    pagina en `panEnabled` pas bij schaal > 1, anders slikt de viewer de horizontale sleep op en
    kun je niet meer swipen. Zoomstand wordt bewust niet bewaard bij paginawissel.
  - Per pagina `loadingBuilder` (CircularProgressIndicator) en `errorBuilder` met hetzelfde
    `Icons.broken_image_outlined` als het grid-kaartje.
- Nieuwe widget-test `dashboard-frontend/test/screens/screenshots_screen_test.dart` (patroon van de
  bestaande `*_screen_test.dart`: `MockClient` + `http.runWithClient`) dekt criterium 1 (openen op
  aangetikte index + heen/terug swipen), 3 (naam + teller lopen mee), 4/5 (knop + toets, randen doen
  niets) en 6/8 (één screenshot: geen pijlen/teller, broken-image-fallback).
- Specs bijgewerkt: `docs/factory/ux/screens/screenshots.md` — bij "Actions" een regel over het
  doorbladeren in de viewer (swipe/knoppen/toetsen, geen wrap, indicator alleen bij meerdere).
  `functional-spec.md`/`technical-spec.md` raakten niet aan deze wijziging (geen nieuwe
  endpoints, config of architectuurbesluiten).

Bewijs (2026-07-31, deze branch):
- `flutter analyze` → "No issues found!" (0 issues).
- `flutter test` → 105 tests, All tests passed (incl. de 3 nieuwe).
- `mvn verify` vanaf repo-root → BUILD SUCCESS, exitcode 0, 0 failures / 0 errors
  (softwarefactory-e2e's met Testcontainers meegedraaid, totaal ~6,5 min).
- `dashboard-frontend/pubspec.lock` werd door `flutter pub get` gewijzigd en is bewust
  teruggedraaid; die dependency-bumps horen niet bij deze story.

Review (SF-1597, 2026-07-31):
- Volledige story-diff t.o.v. `main` beoordeeld (screenshots_screen.dart, nieuwe widget-test,
  ux/screens/screenshots.md, dit worklog). Geen implementatiewijzigingen door de reviewer.
- Acceptatiecriteria 1-9 teruggevonden in de code; publieke `ScreenshotsScreen`-signature en
  gridweergave ongewijzigd, dus het pad vanuit `story_detail_screen.dart` blijft intact.
- Gerichte hercontrole: `flutter analyze` op beide gewijzigde bestanden → No issues found;
  `flutter test test/screens/screenshots_screen_test.dart` → 3/3 groen.
- Geen blockers. Kleine observaties: criterium 7 (zoom/pan-gedrag) is niet automatisch getest en
  de pijl-overlay ligt over de afbeeldingsranden; beide acceptabel binnen scope.

Test (SF-1598, 2026-07-31):
- Omgeving: Flutter aanwezig op `/opt/flutter` (aarch64); geen browser/preview-URL beschikbaar in
  de tester-sandbox, dus geen klikbare E2E/screenshots mogelijk. Verificatie via de widget-suite,
  een release-webbuild en gerichte code-doorloop per acceptatiecriterium.
- `flutter analyze` (dashboard-frontend) → "No issues found!" (0 issues).
- `flutter test` (volledige frontend-suite) → 105/105 groen, "All tests passed!", incl. de drie
  nieuwe tests in `test/screens/screenshots_screen_test.dart`. Geen flakes waargenomen.
- `flutter build web --release` → "✓ Built build/web" (compileert dus ook richting het echte
  webtarget, niet alleen onder de testbinding). Build-output daarna verwijderd.
- Criteria-doorloop: 1/2 (startindex + dezelfde `screenshots`-lijst uit de API-respons, dus
  identieke volgorde als het grid), 3 (titel en `n / totaal` volgen `onPageChanged`, ook bij
  knop/toets omdat `animateToPage` diezelfde callback triggert), 4 (`_goTo` weigert indices buiten
  de lijst en de randknoppen krijgen `onPressed: null` — geen wrap, geen foutmelding), 5 (pijlen +
  `LogicalKeyboardKey.arrowLeft/Right` via een `Focus` met `autofocus`, key-events bubbelen ook
  vanaf een aangetikte knop naar die node), 6 (`_hasMultiple` verbergt zowel indicator als pijlen),
  7 (`InteractiveViewer` met `panEnabled: _zoomed`, dus horizontaal slepen blijft pagineren zolang
  er niet ingezoomd is; zoomstand is per pagina-state en wordt niet bewaard), 8 (`loadingBuilder`
  met `CircularProgressIndicator` en `errorBuilder` met `Icons.broken_image_outlined`, hetzelfde
  icoon als het grid), 9 (`ScreenshotsScreen`-constructor en grid ongewijzigd; call-site
  `story_detail_screen.dart:347` onaangeraakt).
- Werkboom na de run schoon: de door `flutter pub get` gewijzigde `pubspec.lock` is teruggedraaid.
- Geen bevindingen; alleen deze worklog-regels zijn door de tester toegevoegd.

Development, tweede ronde (SF-1597, 2026-07-31) — na afwijzing door de harness:
- Aanleiding: de harness-verificatie na de tester-run wees af met
  `Verification-command repository-documentation-audit afgewezen: status=execution-error,
  exitCode=n.v.t.`. Dat is **niet** de documentatie-audit zelf: `tools/audit-documentation`
  geeft in deze werkboom gewoon `documentation-audit/v1: PASS` (exit 0).
- Oorzaak achterhaald: `TesterVerificationRunner.verify` (agentworker) vergelijkt de
  `CheckoutIdentity` (HEAD-sha + worktree-tree, inclusief niet-genegeerde untracked files) vóór
  en ná de verificatie; verschilt die, dan wordt het **laatste** evidence-item overschreven met
  `status=execution-error`. `repository-documentation-audit` is het laatste commando in
  `.factory/verification.yaml`, vandaar de misleidende naam in de diagnose. Wat de tree tijdens
  die run veranderde was het verificatiecommando `dashboard-flutter-pub-get` zelf: `flutter pub
  get` herschreef `dashboard-frontend/pubspec.lock` (transitieve patch-bumps van `characters`,
  `matcher`, `material_color_utilities`, `meta`, `test_api`) omdat de gecommitte lock nog de
  oudere resolutie bevatte.
- Besluit: de door `flutter pub get` geproduceerde `pubspec.lock` **blijft** staan (hij is
  inmiddels in commit `f36f703` meegekomen). Terugdraaien zou de instabiliteit juist opnieuw
  introduceren: elke volgende verificatie zou de lock weer herschrijven en dus weer op
  `execution-error` uitkomen. De eerdere worklog-regel "lock bewust teruggedraaid" is daarmee
  achterhaald. Alleen transitieve dev/test-afhankelijkheden bewegen mee; `pubspec.yaml`
  is niet aangeraakt en analyze/test zijn groen.
- Geverifieerd dat de tree nu stabiel is: `git write-tree` over een tijdelijke index (dezelfde
  berekening als `CheckoutIdentityResolver`) geeft vóór en ná `flutter pub get`, `flutter
  analyze` en `flutter test` exact dezelfde sha `0be7b02c…`.
- Extra regressietest toegevoegd (openstaande reviewer-suggestie bij criterium 7):
  `pannen staat pas aan na inzoomen, zodat swipen mogelijk blijft` in
  `test/screens/screenshots_screen_test.dart` legt vast dat `InteractiveViewer.panEnabled` in
  niet-ingezoomde staat `false` is en pas na een knijpgebaar `true` wordt. Het gebaar knijpt
  bewust **verticaal**: horizontaal wint de PageView-drag de gesture-arena en zoomt er niets.
- Geen wijziging aan de productiecode van deze story nodig; de implementatie uit ronde 1 stond
  al volledig en groen op de branch.

Bewijs tweede ronde (2026-07-31, deze branch):
- `flutter analyze` (dashboard-frontend) → "No issues found!" (0 issues).
- `flutter test` (volledige frontend-suite) → 106/106 groen (105 + de nieuwe zoomtest).
- `tools/audit-documentation` → `documentation-audit/v1: PASS` (exit 0).
- `mvn -B clean verify` vanaf repo-root → BUILD SUCCESS, exitcode 0, 0 failures / 0 errors.
