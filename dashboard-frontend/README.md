# Software Factory Dashboard

Flutter-frontend voor de Software Factory. De UI praat met `dashboard-backend` op poort 9080;
de backend is een dunne bridge naar de factory-WebSocket (`/bridge`) en leest zelf geen tracker-DB
of GitHub.

## Lokaal ontwikkelen

Vereist: Flutter 3.35.x en een bereikbare factory/bridge. Gebruik vanuit deze map:

```bash
flutter pub get
flutter analyze
flutter test
flutter run --dart-define=API_BASE_URL=http://localhost:9080
```

De Google-login gebruikt de geconfigureerde client-id; secrets horen niet in deze repository.
Voor de volledige lokale keten vanaf de repositoryroot:

```bash
./factory local-services
./factory start
docker/smoke-local-quickstart.sh
```

Stop de Compose-services met `./factory local-services-stop`. Voor een production-build gebruikt
de image-build de Dockerfile in deze map; CI publiceert images pas na een groene
`Repository verification` op `main`, en alleen wanneer die run door een push in deze repository
zelf is gestart (niet vanuit een fork of pull request). Zie `deploy/README.md` voor de volledige
startvoorwaarden van de image-workflows.

## Nginx-config en HSTS

`nginx.conf` in deze map zit in het productie-image: nginx serveert de gebouwde web-app en proxyt
`/api/*` en `/bridge` naar de backend. Het dashboard is https-only (SF-2008) en stuurt op elke
respons `Strict-Transport-Security: max-age=31536000` mee — bewust zonder `includeSubDomains` en
zonder `preload`, want die zijn voor het hele domein lastig terug te draaien.

Let op de nginx-headersemantiek bij elke wijziging aan dit bestand: `add_header` erft alleen van
het omsluitende blok en wordt volledig gemaskeerd zodra het gekozen `location`-blok zelf een
`add_header` heeft. Een request op `/` wordt via `try_files` (interne redirect) uiteindelijk
beantwoord vanuit `location = /index.html`. Daarom staat de header op server-niveau **én**
herhaald in elk location-blok dat al een eigen `add_header` heeft; locaties zonder eigen
`add_header` erven de server-variant. Voeg je een location-blok met bijvoorbeeld een eigen
`Cache-Control` toe, herhaal daar dan ook de HSTS-regel.

`test/nginx_conf_test.dart` bewaakt precies die val en draait mee in `flutter test` (het leest
`nginx.conf` relatief aan deze map, dus draai `flutter test` vanuit `dashboard-frontend/`). Die
test leest de configtekst; dat de header ook echt op alle responses uitkomt is aangetoond tegen
een draaiende nginx — zie `docs/stories/worklog/SF-2008-worklog.md`. De https-afdwinging aan de
routekant staat in `deploy/README.md` §HTTPS enforcement.

## Frontendstructuur en contracten

Elke overviewfeature heeft een eigen bestand onder `lib/screens`; het oude
`overview_screens.dart` bevat alleen exports voor stabiele navigatie-imports (sinds SF-1676
zonder het verwijderde `dashboard_overview_screen.dart`). Featuremodellen leven
onder `lib/features`. Projects gebruikt `ProjectSummary`/`ProjectsPageData`: verplichte velden
hebben een strikt type (waaronder het echte booleanveld `hasDeployConfig`), optionele waarden hebben
expliciete defaults en onbekende additieve velden worden genegeerd.

## URL's en deep links (web)

De app is geen router-app: op web staat sinds SF-2087 wél `usePathUrlStrategy()` aan, zodat het
adres zonder `#` in de adresbalk staat. Dat gebeurt via een conditionele import
(`lib/url_strategy_web.dart` met `flutter_web_plugins` uit de Flutter-SDK, en
`lib/url_strategy_stub.dart` als no-op voor Android), zodat de Android-build niet tegen
web-only code aanloopt.

Er is precies één deep link: `/changelog/<url-geëncodeerde projectnaam>`. `main()` leest het
opgevraagde pad éénmalig via `parseDeepLink` uit `lib/deep_link.dart` — een bewust UI-vrije,
Flutter-loze module, zodat de afleiding met gewone unit-tests te dekken is
(`test/deep_link_test.dart`). De bestemming wordt doorgegeven aan `RootScreen` en daar
vastgehouden zolang de app draait, zodat ze ook een verse Google-login overleeft. Elk ander pad
levert `null` op en dus het normale app-shell-gedrag.

Twee valkuilen bij wijzigingen hier:

- `MaterialApp` gebruikt `onGenerateInitialRoutes` in plaats van `home:`. De standaardgenerator
  kent alleen `/`, valt daarop terug en meldt die naam aan de engine — met
  `usePathUrlStrategy()` zet de adresbalk zichzelf dan terug op `/` en is het adres niet meer
  bookmarkbaar. Daarom bouwt de app zelf precies één route met de gevraagde naam; dat houdt het
  adres staan én zorgt dat er geen terug-knop verschijnt (de deep-link-pagina is de root van de
  navigator). `home:` en `onGenerateInitialRoutes` samen verbiedt `WidgetsApp`; de root-pagina
  komt daarom ook uit `onGenerateRoute`. `test/widget_test.dart` en `test/initial_route_test.dart`
  bewaken dit.
- Projectnamen kunnen spaties en tekens als `&` bevatten. Ze worden geëncodeerd in het pad
  (`changelogPathFor`) én in het API-pad `GET /api/v1/changelog/{name}`; `parseDeepLink`
  decodeert weer en laat een ongeldige escape-reeks ruw staan in plaats van te crashen.

Het datapad zelf is ongewijzigd: de changelog komt van de bestaande geauthenticeerde
bridge-route, dus deep links zijn herbruikbaar maar niet publiek. De SPA-fallback in `nginx.conf`
(`try_files $uri $uri/ /index.html`) serveert het changelog-pad al; er was geen
webserver-wijziging nodig.
