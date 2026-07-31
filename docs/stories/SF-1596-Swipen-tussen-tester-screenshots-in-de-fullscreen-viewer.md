# SF-1596 - Swipen tussen tester-screenshots in de fullscreen-viewer

## Story

Swipen tussen tester-screenshots in de fullscreen-viewer

<!-- refined-by-factory -->

## Samenvatting

Een tester maakt vaak een reeks screenshots bij één story. Nu moet je na elk plaatje terug naar het overzicht en opnieuw tikken om het volgende te zien. Dat maakt vergelijken onhandig.

Met deze wijziging blader je vanuit het volledige scherm direct door naar het volgende of vorige screenshot: swipen op touch, pijltjesknoppen en pijltjestoetsen op desktop. Bovenin zie je welke naam je bekijkt en de hoeveelste van hoeveel het is. Bij één screenshot verandert er niets aan wat je ziet.

## Scope

In scope (alleen `dashboard-frontend/lib/screens/screenshots_screen.dart`):

- `_ScreenshotViewer` krijgt de volledige screenshotlijst van de story plus de aangetikte startindex mee in plaats van één losse `title`/`imageUrl`, en wordt stateful met een `PageView.builder`.
- De aanroep in de grid-`itemBuilder` (`screenshots_screen.dart:45-53`) geeft de al opgehaalde `screenshots`-lijst en `index` door.
- Per pagina: `InteractiveViewer` rond `Image.network` met `state.api.authHeaders()` en de bestaande image-url `/api/v1/stories/<key>/screenshots/<id>/image`.
- AppBar-titel toont de naam van het huidige screenshot; bij meer dan één screenshot ook een positie-indicator (`3 / 7`).
- Bij meer dan één screenshot: vorige/volgende-pijlknoppen als overlay en bediening met de pijltjestoetsen links/rechts.
- Per pagina een laadindicator (`loadingBuilder`) en de broken-image-fallback (`errorBuilder`, zelfde icoon als het grid gebruikt op `screenshots_screen.dart:62`).
- Een widget-test in `dashboard-frontend/test/screens/` volgens het bestaande patroon in die map.

Buiten scope:

- Backend-wijzigingen (endpoints, model, query) — de benodigde data komt al mee uit `GET /api/v1/stories/<key>/screenshots`.
- De gridweergave zelf (layout, kaartjes, sortering) en `ScreenshotsScreen`'s publieke constructor-signature.
- Downloaden, delen, verwijderen of roteren van screenshots; thumbnails; caching/prefetch.
- Circulair doorbladeren.

## Acceptance criteria

1. Bij een story met meerdere tester-screenshots opent een tik op kaartje N de viewer op precies dat screenshot; horizontaal swipen naar links toont N+1, terugswipen weer N.
2. De volgorde in de viewer is identiek aan de volgorde van het grid (de volgorde van de API-respons).
3. De appbar toont de naam van het zichtbare screenshot en een positie-indicator in de vorm `<huidige> / <totaal>`; beide lopen mee bij elke paginawissel, ook bij navigatie via knop of toets.
4. Op de eerste pagina doet "vorige" niets en op de laatste doet "volgende" niets; er wordt niet doorgesprongen naar de andere kant en er verschijnt geen foutmelding.
5. Bij meer dan één screenshot zijn er een vorige- en een volgende-knop zichtbaar en werken de pijltjestoetsen links/rechts voor dezelfde navigatie.
6. Bij precies één screenshot toont de viewer alleen naam en afbeelding: geen pijlknoppen en geen positie-indicator (dus ook niet `1 / 1`).
7. Inzoomen werkt per pagina; in niet-ingezoomde staat blijft horizontaal swipen tussen pagina's mogelijk. Verlaten van een pagina hoeft de zoomstand niet te bewaren.
8. Zolang een afbeelding laadt, toont die pagina een laadindicator; mislukt het laden, dan toont die pagina de broken-image-fallback zonder de rest van de viewer te breken.
9. Het openen van de screenshots-galerij vanuit het story-detailscherm (`story_detail_screen.dart:347`) en de gridweergave werken ongewijzigd.
10. `flutter analyze` is schoon en `flutter test` slaagt, inclusief een nieuwe widget-test die minimaal criterium 1, 3 en 6 afdekt.

## Aannames

- De technische richting noemt "de bestaande errorBuilder-fallback behouden"; in de huidige viewer bestaat die niet (alleen op het grid-kaartje). Criterium 8 wordt daarom als *toevoegen* gelezen, met het icoon uit `screenshots_screen.dart:62` als referentie.
- De screenshotlijst wordt één keer opgehaald door `DataScreen` en als momentopname aan de viewer meegegeven; de viewer ververst niet zelf en toont geen screenshots die tijdens het bekijken bijkomen.
- "Naam" is het veld `name` uit de API-respons, hetzelfde veld dat het grid onder het kaartje toont.
- De pijltjesknoppen zijn een overlay over de afbeelding (links/rechts gecentreerd); exacte styling is aan de developer, binnen het bestaande app-thema.
- Toetsenbordbediening werkt zodra de viewer open is, zonder dat de gebruiker eerst iets hoeft aan te klikken (focus wordt bij openen gezet).
- Geen aanpassing nodig aan `docs/factory/ux/screens/screenshots.md` behalve eventueel een korte notitie bij "Actions"; de documenter beslist daarover.

<!-- test-feedback:start -->
## Test-feedback
**Testresultaat SF-1598 (story SF-1596) — groen**

Uitgevoerd in `/work/repo` op branch `ai/SF-1596`:

| Check | Resultaat |
|---|---|
| `flutter analyze` (dashboard-frontend) | No issues found (0) |
| `flutter test` (volledige suite) | 105/105 groen, "All tests passed!" |
| `flutter build web --release` | ✓ Built build/web |

Diff is scope-conform: alleen `screenshots_screen.dart`, een nieuwe widget-test, `docs/factory/ux/screens/screenshots.md` en het worklog. Geen backend-wijzigingen.

Criteria-doorloop (code + tests): startindex en lijstvolgorde komen rechtstreeks uit de al opgehaalde `screenshots`-respons (AC1/2); titel + `n / totaal` volgen `onPageChanged`, dat ook door `animateToPage` vanaf knop/toets getriggerd wordt (AC3); `_goTo` weigert indices buiten de lijst en randknoppen krijgen `onPressed: null` — geen wrap, geen fout (AC4); pijlknoppen plus `arrowLeft/Right` via een `Focus` met `autofocus`, key-events bubbelen ook vanaf een aangetikte knop (AC5); `_hasMultiple` verbergt bij één screenshot zowel indicator als pijlen (AC6); `InteractiveViewer` met `panEnabled: _zoomed` houdt swipen mogelijk zolang er niet ingezoomd is (AC7); `loadingBuilder` + `errorBuilder` met hetzelfde broken-image-icoon als het grid (AC8); `ScreenshotsScreen`-signature en grid ongewijzigd, call-site `story_detail_screen.dart:347` intact (AC9).

Beperking, expliciet gemeld: er is **geen browser en geen preview-URL** in deze tester-sandbox (geen chrome/chromium binary, geen preview-host in factory.env), dus klikbare E2E-verificatie en screenshots in `/work/screenshots` waren niet mogelijk. De release-webbuild is als vervangende integratiecheck gedraaid. Geen flakes waargenomen.

Werkboom: alleen `docs/stories/worklog/SF-1596-worklog.md` gewijzigd; de door `flutter pub get` aangepaste `pubspec.lock` en `build/`-output zijn teruggedraaid/verwijderd. Niets gecommit.

{"agent_tips_update":[{"category":"environment","key":"tester-sandbox-no-browser-flutter-web-build-as-fallback","content":"Tester-sandbox (2026-07-31, SF-1596/1598): Flutter 3.x werkt op /opt/flutter (aarch64), maar er is GEEN browser (which google-chrome/chromium/chromium-browser leeg) en geen preview-URL in /work/factory.env — klikbare E2E/screenshots voor Flutter-frontendstories zijn dus niet mogelijk. Bruikbare vervanging bovenop `flutter test`: `flutter build web --release` vanuit dashboard-frontend (~30s, bevestigt dat de wijziging ook naar het echte webtarget compileert i.p.v. alleen onder de testbinding). Let op: `flutter pub get` wijzigt dashboard-frontend/pubspec.lock — draai `git checkout -- dashboard-frontend/pubspec.lock` en `rm -rf dashboard-frontend/build` na de run, anders laat je als tester ongewenste wijzigingen achter."}]}

{"phase":"tested"}

[FACTORY VERIFICATION] Verification-command repository-documentation-audit afgewezen: status=execution-error, exitCode=n.v.t.
<!-- test-feedback:end -->

## Eindsamenvatting

Ik heb `.task.md`, het worklog en de story-diff doorgenomen. Hieronder de eindsamenvatting.

---

# SF-1596 — Swipen tussen tester-screenshots in de fullscreen-viewer

## Wat is er gebouwd

De fullscreen screenshot-viewer in het dashboard toonde tot nu toe één los plaatje: na elk screenshot moest je terug naar het overzicht en opnieuw tikken. Nu blader je vanuit het volledige scherm direct door de hele reeks van de story:

- **Swipen** op touch, **pijlknoppen** als overlay en de **pijltjestoetsen** links/rechts op desktop.
- De **appbar toont de naam** van het zichtbare screenshot plus een **positie-indicator** (`3 / 7`) die meeloopt bij elke paginawissel — of je nu swipet, klikt of een toets gebruikt.
- **Geen doorspringen**: op de eerste pagina doet "vorige" niets, op de laatste doet "volgende" niets; geen wrap, geen foutmelding.
- **Bij precies één screenshot verandert er niets** aan wat je ziet: geen pijlen, geen teller (dus ook niet `1 / 1`).
- **Inzoomen werkt per pagina**; zolang je niet ingezoomd bent blijft horizontaal swipen gewoon pagineren.
- Per pagina een **laadindicator** en, als een plaatje niet laadt, dezelfde **broken-image-fallback** als het grid gebruikt.

De gridweergave, de route vanuit het story-detailscherm en de publieke signature van het screenshots-scherm zijn ongewijzigd gebleven.

## Belangrijkste keuzes

- **Geen backend-wijziging.** Alle benodigde data zat al in de bestaande screenshots-respons; de viewer krijgt de al opgehaalde lijst plus de aangetikte startindex mee. Daardoor is de volgorde in de viewer per definitie identiek aan die van het grid.
- **De lijst is een momentopname.** Screenshots die tijdens het bekijken bijkomen verschijnen pas na opnieuw openen — bewust, om de viewer simpel te houden.
- **Pannen staat pas aan na inzoomen.** Anders slikt de zoom-viewer de horizontale sleep op en kun je niet meer swipen. Dit is met een aparte regressietest vastgelegd.
- **Foutafhandeling bij laden is toegevoegd, niet behouden.** De oorspronkelijke technische richting sprak van "bestaande fallback behouden", maar die bestond alleen op het grid-kaartje; hij is nu ook in de viewer gemaakt, met hetzelfde icoon.
- **`pubspec.lock` is meegecommit.** Tijdens de eerste verificatieronde werd de story afgekeurd met een misleidende melding (`repository-documentation-audit: execution-error`). Uitgezocht bleek: niet de documentatie-audit faalde — die is groen — maar het verificatiecommando `flutter pub get` herschreef de lockfile, waardoor de checkout tijdens de run veranderde en de harness afkapte. De vernieuwde lock is daarom bewust bewaard; terugdraaien zou het probleem elke run opnieuw oproepen. Het gaat alleen om transitieve test-afhankelijkheden; `pubspec.yaml` is niet aangeraakt.

## Wat is getest

| Check | Resultaat |
|---|---|
| `flutter analyze` (dashboard-frontend) | Schoon, 0 issues |
| `flutter test` (volledige frontend-suite) | 106/106 groen |
| `flutter build web --release` | Succesvol gebouwd |
| `mvn -B clean verify` (repo-breed) | BUILD SUCCESS, 0 failures |
| `tools/audit-documentation` | PASS |

Nieuw zijn vier widget-tests die het openen op de aangetikte index, heen- en terugbladeren, het meelopen van naam en teller, het gedrag aan de randen, de bediening via knop en toets, de situatie met één screenshot en het zoom/pan-gedrag afdekken. Reviewer en tester hebben de story-diff beide tweemaal beoordeeld en alle tien acceptatiecriteria teruggevonden in code en tests. Geen blockers, geen flakes.

## Wat bewust niet is gedaan

- **Geen klikbare eindtest in een echte browser.** De testomgeving heeft geen browser en geen preview-URL, dus er zijn geen schermafbeeldingen van de werkende feature. De release-webbuild is als vervangende integratiecheck gedraaid. Wil je visuele bevestiging, dan moet dat op de preview- of productieomgeving na deploy gebeuren.
- Buiten scope gelaten conform afspraak: backend-aanpassingen, wijzigingen aan de gridweergave zelf, downloaden/delen/verwijderen/roteren van screenshots, thumbnails, prefetch en circulair doorbladeren.
- Twee kleine, bewust geaccepteerde observaties van de reviewer: de teller gebruikt een vaste tekstgrootte in plaats van een themastijl, en de zoomstand wordt niet gereset bij een paginawissel (dat laatste is expliciet toegestaan door de acceptatiecriteria).

## Stand van de story

De implementatie staat groen op branch `ai/SF-1596`. Openstaand in de keten: documentatie bijwerken (SF-1600), merge (SF-1601) en deploy naar productie (SF-1602).

---
