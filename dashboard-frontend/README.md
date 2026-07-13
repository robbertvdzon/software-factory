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
`Repository verification` op `main`.

## Frontendstructuur en contracten

De zes overviewfeatures hebben ieder een eigen bestand onder `lib/screens`; het oude
`overview_screens.dart` bevat alleen exports voor stabiele navigatie-imports. Featuremodellen leven
onder `lib/features`. Projects gebruikt `ProjectSummary`/`ProjectsPageData`: verplichte velden
hebben een strikt type (waaronder het echte booleanveld `hasDeployConfig`), optionele waarden hebben
expliciete defaults en onbekende additieve velden worden genegeerd.
