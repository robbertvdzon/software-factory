# Endpoints

Deze lijst beschrijft de functionele endpointgroepen; de controllers en OpenAPI/DTO's zijn de bron
voor exacte payloads.

## Hoofdapp (`softwarefactory`)

- `/api/tracker/*`: story-, comment-, attachment-, antwoord- en commandoperaties;
- `GET /api/version`: versie-informatie van de hoofdapp;
- `/api/v1/public/changelog/*`: publieke changelogprojectie;
- `/agent-knowledge` en `/agent-knowledge/update`: knowledgequery/upsert;
- `/completion/*`: beheer/retry van durable completion;
- `/agent-run/complete`: legacy compatibility-ingang voor externe completion; nieuwe dispatch en
  normale completion gebruiken uitsluitend Runtime v2 en niet een resultbestand.

Machine-endpoints vereisen `SF_FACTORY_API_TOKEN` waar de controller dat voorschrijft.

## Dashboard-API (`/api/v1`)

`/api/v1/*` levert stories, acties, agents, projecten, builds, audits, maintenance en settings aan
de Flutterfrontend, rechtstreeks vanuit de hoofdapp (`web`-module). `POST /api/v1/auth/google`
ruilt een Google-ID-token in voor een sessietoken; een interceptor eist dat token op alle overige
`/api/v1`-routes (fail-closed), alleen `/api/v1/public/**` is vrij. `GET /api/v1/events` is het
SSE-kanaal met `changed`-events. Een 401 moet door de frontend naar de loginflow worden vertaald.

## Product Factory (`/api/integrations/v1` en `/api/integrations/v2`)

- `GET /status`;
- `POST /stories`;
- `GET /stories` en `GET /stories/{storyKey}`;
- `POST /stories/{storyKey}/answers`;
- cancel/commandroutes volgens het bestaande contract.

De API gebruikt een apart bearer-token en voor create het bestaande `Idempotency-Key`-contract.
Attachments, packagehash en metadata worden duurzaam aan de story gekoppeld; ze worden voor
Runtime-jobs als expliciete inputobjecten aangeboden, niet via een lokale agentworkspace.

Gebruik statuscodes als volgt:

- `400`: invoer/contract ongeldig;
- `401`: token ontbreekt of klopt niet;
- `404`: story/target niet gevonden;
- `409`: conflict met een eerdere aanlevering (bijlage of idempotentiesleutel);
- `500`: factoryfout (v2 meldt `retryable`);
- `503`: factory niet bereikbaar, bijvoorbeeld tijdens een herstart.

## Agent Runtime v2 (uitgaand)

De hoofdapp gebruikt uitgaand de Runtime-endpoints voor:

- execution options en repositoryaliassen;
- job create/get/cancel;
- event- en resultaatophaling;
- artifactdownload;
- reserve/upload/finalize van inputobjecten.

Requests bevatten een duurzame idempotentiesleutel. Repositoryjobs sturen een alias en bestaande
branch, nooit een lokaal pad of Gitcredential.
