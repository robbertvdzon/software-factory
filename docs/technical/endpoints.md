# Endpoints

Deze lijst beschrijft de functionele endpointgroepen; de controllers en OpenAPI/DTO's zijn de bron
voor exacte payloads.

## Hoofdapp (`softwarefactory`)

- `/api/tracker/*`: story-, comment-, attachment-, antwoord- en commandoperaties;
- `/api/v1/public/changelog/*`: publieke changelogprojectie;
- `/agent-knowledge` en `/agent-knowledge/update`: knowledgequery/upsert;
- `/api/restart` en procesacties: huidige lokale procesbesturing;
- `/completion/*`: beheer/retry van durable completion;
- `/agent-run/complete`: legacy compatibility-ingang voor externe completion; nieuwe dispatch en
  normale completion gebruiken uitsluitend Runtime v2 en niet een resultbestand.

Machine-endpoints vereisen `SF_FACTORY_API_TOKEN` waar de controller dat voorschrijft.

## Dashboard-backend

`/api/v1/*` levert stories, acties, agents, projecten, builds, audits, maintenance en settings aan
de Flutterfrontend. Google OIDC/remember-cookie beschermt gebruikersroutes. Een 401 moet door de
frontend naar de loginflow worden vertaald.

`/bridge` is de tijdelijke geauthenticeerde WebSocketverbinding met de lokaal draaiende hoofdapp.
De backend weigert ontbrekende/foute hello-token, ontbrekende hello en dataverkeer vóór hello.

## Product Factory (`/api/integrations/v1`)

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
- `502`: onbruikbaar factory-/bridgeantwoord;
- `503`: factory in de huidige bridgetopologie offline;
- `500`: echte backendfout.

## Agent Runtime v2 (uitgaand)

De hoofdapp gebruikt uitgaand de Runtime-endpoints voor:

- execution options en repositoryaliassen;
- job create/get/cancel;
- event- en resultaatophaling;
- artifactdownload;
- reserve/upload/finalize van inputobjecten.

Requests bevatten een duurzame idempotentiesleutel. Repositoryjobs sturen een alias en bestaande
branch, nooit een lokaal pad of Gitcredential.
