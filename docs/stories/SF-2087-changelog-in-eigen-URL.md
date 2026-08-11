# SF-2087 - changelog in eigen URL

## Story

changelog in eigen URL

<!-- refined-by-factory -->

## Scope

De changelog van een project krijgt een eigen, bookmarkbare URL in de webversie van het dashboard, en de changelog-knop opent die URL in een nieuw browsertabblad.

In scope (uitsluitend `dashboard-frontend`):

1. **Eigen URL per project.** De changelog is bereikbaar op het pad `/changelog/<projectnaam>`, waarbij de projectnaam URL-geëncodeerd is. Dit is de enige nieuwe URL; alle overige schermen behouden hun huidige gedrag en blijven op de root-URL.
2. **Deep link bij het laden van de app.** Bij het starten van de web-app wordt het gevraagde pad gelezen. Matcht dat op `/changelog/<projectnaam>`, dan toont de app na een geldige sessie direct het changelog-scherm van dat project als zelfstandige pagina (dus niet als overlay bovenop de projectenpagina en zonder de normale app-navigatie eromheen). Elk ander pad leidt naar het bestaande gedrag (app-shell).
3. **URL-strategie.** Op web wordt de pad-gebaseerde URL-strategie aangezet (`usePathUrlStrategy` uit de Flutter-SDK, geen nieuwe dependency), zodat het adres zonder `#` in de adresbalk staat en gekopieerd/gebookmarkt kan worden.
4. **Knopgedrag.** De changelog-knop in de projectenlijst opent op web de URL uit punt 1 in een nieuw tabblad (via `url_launcher` met `_blank`) in plaats van in-app te navigeren. Op niet-web (Android-APK) blijft de huidige in-app navigatie via `Navigator.push` ongewijzigd.
5. **Datapad ongewijzigd.** Het changelog-scherm blijft de bestaande geauthenticeerde route `GET /api/v1/changelog/{name}` gebruiken. Er komen geen backend-, database- of deploy-manifest-wijzigingen.

Buiten scope:
- Een publiek (niet-ingelogd) toegankelijke changelog-pagina; de bestaande publieke factory-endpoint `/api/v1/public/changelog/{name}` is niet vanaf het browser-origin bereikbaar en blijft ongewijzigd.
- Een algemene router/deep-link-structuur voor de overige schermen.
- Wijzigingen aan inhoud, volgorde of totstandkoming van de changelog-items.

## Acceptance criteria

1. In de webversie levert een klik op de changelog-knop bij een project een nieuw browsertabblad op met het adres `/changelog/<projectnaam>` (projectnaam URL-geëncodeerd, inclusief namen met spaties of andere tekens die encoding vereisen); het oorspronkelijke tabblad blijft op de projectenpagina staan.
2. In dat nieuwe tabblad staat de changelog van precies dat project, met dezelfde inhoud en volgorde (nieuwste eerst) als de huidige changelog-weergave, inclusief de bestaande lege-staat-melding wanneer er geen items zijn.
3. Het adres uit AC 1 is opnieuw op te vragen: dat adres direct in de adresbalk plakken of als bladwijzer openen (koude laadbeurt, zonder eerst door de projectenlijst te klikken) toont dezelfde changelog-pagina.
4. Bij een koude laadbeurt op dat adres met een geldige bestaande sessie in de browser verschijnt de changelog zonder tussenkomst van het inlogscherm.
5. Bij een koude laadbeurt op dat adres zonder geldige sessie verschijnt eerst het inlogscherm; na succesvol inloggen wordt alsnog de changelog van het gevraagde project getoond (het deep link gaat niet verloren en de gebruiker belandt niet op het standaard dashboard).
6. De changelog-pagina in het eigen tabblad is een volwaardige pagina met de titel van het project en toont geen terug-knop die naar een niet-bestaande vorige pagina zou leiden.
7. Een onbekend of leeg projectdeel in de URL leidt niet tot een crash of wit scherm, maar tot de bestaande foutmelding/lege staat van het changelog-scherm.
8. Alle overige schermen en de bestaande navigatie werken ongewijzigd; de root-URL van het dashboard opent zoals voorheen de app-shell.
9. Op de Android-APK opent de changelog-knop de changelog nog steeds in de app zelf (geen browser/externe tab).
10. `flutter analyze` en `flutter test` in `dashboard-frontend` zijn groen; er is minimaal geautomatiseerde dekking voor (a) het afleiden van het changelog-scherm uit een changelog-pad en (b) het niet afleiden daarvan uit een willekeurig ander pad.
11. De quality-ratchet blijft groen (aantal bevindingen niet omhoog).

## Aannames

- De changelog blijft achter de bestaande Google-SSO-login; "bookmarkbaar" betekent hier "adres is herbruikbaar", niet "publiek toegankelijk zonder inloggen". Delen met iemand zonder dashboard-account werkt dus niet.
- Het sessietoken staat in browseropslag die tabbladen delen, waardoor een nieuw tabblad de bestaande sessie herkent zonder opnieuw inloggen.
- Er is geen webserver-wijziging nodig: de bestaande SPA-fallback serveert de app al op willekeurige paden. Wordt tijdens implementatie het tegendeel vastgesteld, dan hoort de bijbehorende configuratieaanpassing bij deze story.
- Er komt geen nieuwe dependency bij; nieuw-tabblad-gedrag gebruikt de al aanwezige `url_launcher` en de URL-strategie komt uit de Flutter-SDK.
- Het changelog-scherm wordt visueel niet herontworpen; alleen de manier van openen en het adres veranderen.
- Uitrol volgt het normale pad: de wijziging is pas live na de frontend-image-build en de bijbehorende tag-bump.
</proposed-description-note-verwijderd>

## Eindsamenvatting

Ik heb `.task.md`, de diff (`main..HEAD`) en het volledige worklog van SF-2087 gelezen. Hieronder de eindsamenvatting.

# SF-2087 — Changelog op eigen bookmarkbare URL (dashboard-frontend)

## Wat is gebouwd
- **Eigen URL per project**: de changelog is bereikbaar op `/changelog/<projectnaam>` (URL-geëncodeerd). Nieuwe, UI-vrije module `dashboard-frontend/lib/deep_link.dart` leidt het changelog-scherm af uit het browserpad en bouwt omgekeerd het pad bij een projectnaam.
- **Pad-URL-strategie op web**: `usePathUrlStrategy()` via conditionele import (`url_strategy_web.dart` / `url_strategy_stub.dart`), zodat het adres zonder `#` in de adresbalk staat. `flutter_web_plugins` komt uit de Flutter-SDK — geen nieuwe externe dependency.
- **Deep link bij koude laadbeurt**: `main.dart` leest bij het starten éénmalig het gevraagde pad en houdt de bestemming vast in `RootScreen`, dus ook ná een verse Google-login komt de gebruiker op de gevraagde changelog en niet op het standaarddashboard. De changelog rendert dan als zelfstandige root-pagina (geen app-shell, geen overlay, geen terug-knop).
- **Knopgedrag**: op web opent de changelog-knop het adres in een nieuw tabblad (bestaande `url_launcher`, `_blank`); op de Android-APK blijft de in-app `Navigator.push` ongewijzigd.
- **Encoding in het datapad**: de projectnaam wordt geëncodeerd in de bestaande geauthenticeerde route `GET /api/v1/changelog/{name}`, zodat namen met spaties/speciale tekens werken. Verder is het changelog-scherm inhoudelijk ongewijzigd.

## Belangrijkste keuzes
- **Geen router-framework**: bewust één extra route i.p.v. een algemene router/deep-link-structuur voor alle schermen — kleinste ingreep binnen scope.
- **Blocker uit review 1 opgelost**: Flutter zette bij een non-router `MaterialApp` de adresbalk na het laden terug naar `/`, waardoor kopiëren/bookmarken uit dat tabblad de app-shell opleverde. Fix: `MaterialApp.onGenerateInitialRoutes` geeft precies één route terug met de gevraagde routenaam, waardoor het adres blijft staan én er nog steeds geen terug-knop is. `home:` is vervangen door een gedeelde root-helper die ook `onGenerateRoute` gebruikt, zodat de root-URL zich onveranderd gedraagt.
- **Geen webserver-wijziging nodig**: de bestaande SPA-fallback (`try_files … /index.html`) serveert willekeurige paden al; de aanname uit de story is bevestigd.
- **Geen backend-, database- of manifestwijziging**; scope bleef `dashboard-frontend/` + `docs/`.

## Wat is getest
- `flutter analyze` — geen bevindingen; `flutter test` — **164 tests groen** (was 152 vóór de story).
- Nieuwe automatische dekking: deep-link-afleiding (wél bij changelog-pad, níet bij ander pad, plus encoding, query/fragment, leeg projectdeel, ongeldige escape), changelog-scherm (volgorde, lege staat, geëncodeerd API-pad, geen terug-knop) en de gemelde routenaam in de adresbalk (`/changelog/demo` én `/` voor de root).
- **Echte browsertest** door de tester op een gebouwde release-webapp in headless Chromium, met screenshots: koude laadbeurt met sessie (changelog direct zichtbaar, nieuwste bovenaan, projectnaam met spaties en `&`), lege staat, onbekend/leeg project (nette foutmelding, geen wit scherm), root-URL onveranderd, en zonder sessie eerst het inlogscherm. Het eindadres bleef in alle gevallen het changelog-pad.
- `tools/audit-documentation` — PASS. `repository-maven-verify` viel terecht buiten scope (geen Kotlin geraakt).

## Bewust niet gedaan
- **Geen publiek toegankelijke changelog**: het adres blijft achter de Google-SSO-login; "bookmarkbaar" = herbruikbaar adres, niet deelbaar met mensen zonder dashboard-account.
- **Geen deep links voor de overige schermen** en geen visueel herontwerp van de changelog.
- **AC 1 (nieuw tabblad) en AC 9 (Android in-app)** waren in de testcontainer niet klikbaar/uitvoerbaar; beoordeeld op code plus unit-dekking van de padopbouw.
- Twee `[info]`-opmerkingen bewust laten staan: een projectnaam met een `/` erin zou een backendwijziging buiten scope vergen, en een exotisch geëncodeerd pad (`/%63hangelog/...`) matcht ook — beide niet realistisch.

## Aandachtspunt voor de PO (buiten deze story)
`./quality/run.sh` is rood met 2 nieuwe bevindingen in `agentworker` en `dashboard-backend`. Deze branch wijzigt **0** Kotlin-bestanden; beide bestanden zijn laatst gewijzigd in commits die al op `main` staan. Dit is dus bestaande drift op `main`, niet veroorzaakt door SF-2087, en hoort daar los opgepakt te worden (baseline verversen of de twee bevindingen opruimen).

<!-- deploy-summary:start -->
De changelog van een project heeft nu een eigen webadres. Klik je in het dashboard op de changelog-knop, dan opent die in een nieuw tabblad met een adres dat je kunt kopiëren of als favoriet bewaren — open je het later opnieuw, dan zie je meteen weer de changelog van dat project. In de Android-app verandert er niets.
<!-- deploy-summary:end -->
