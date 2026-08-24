# Story — minimale Product Factory v2-integratie-API

Status: klaar voor implementatie.

## Doel

Product Factory v2 moet een complete story, inclusief binaire attachments, naar Software Factory
kunnen sturen. Daarna moet Product Factory kunnen opvragen of die story nog open, opgeleverd of
geannuleerd is.

Deze story bouwt alleen de kleinste bruikbare machine-API boven op de bestaande
`/api/integrations/v1`-implementatie en Software Factory-bridge. Er komt geen nieuwe UI.

## Benodigde API

Alle routes staan onder `/api/integrations/v2` en gebruiken het bestaande
`Authorization: Bearer <SF_PRODUCT_FACTORY_TOKEN>`.

| Methode en pad | Gebruik |
|---|---|
| `GET /status` | Controleren of Software Factory verbonden is |
| `POST /stories` | Een story met eventuele attachments idempotent aanmaken en queueën |
| `GET /stories/{storyKey}` | De publieke status van een bekende story lezen |
| `GET /stories?productId=...&status=OPEN` | Open Software Factory-stories van een product lezen |
| `GET /stories?idempotencyKey=...` | Na een timeout terugvinden of de story al is aangemaakt |

De twee varianten van `GET /stories` retourneren dezelfde kleine lijstvorm. Minimaal één van
`productId` en `idempotencyKey` is verplicht. Andere algemene zoekfilters horen niet bij deze
story.

## Story aanmaken

Product Factory stuurt één verzoek:

```http
POST /api/integrations/v2/stories
Authorization: Bearer <token>
Idempotency-Key: product-factory:hkh:story:550e8400-e29b-41d4-a716-446655440000:v3
Content-Type: application/json
```

```json
{
  "productId": "hkh",
  "sourceStoryId": "550e8400-e29b-41d4-a716-446655440000",
  "sourceStoryVersion": 3,
  "type": "PRODUCT_STORY",
  "targetRepositoryUrl": "https://github.com/example/hkh.git",
  "title": "Toon lege afsprakenlijst",
  "description": "## Gedrag\n...de volledige zelfstandige story in Markdown...",
  "attachments": [
    {
      "id": "appointments-empty-mobile",
      "fileName": "appointments-empty-mobile.png",
      "mediaType": "image/png",
      "sizeBytes": 183042,
      "sha256": "ab41...64-hex-tekens...",
      "contentBase64": "iVBORw0KGgoAAA..."
    }
  ]
}
```

### Minimale regels

- `productId`, `sourceStoryId`, `sourceStoryVersion`, `targetRepositoryUrl`, `title` en
  `description` zijn verplicht.
- `type` is `PRODUCT_STORY` of `BUGFIX`.
- `description` bevat de volledige story: gedrag, gebruikerswaarde, acceptatiecriteria,
  afhankelijkheden, technische grenzen en UX. Hiervoor komen in deze eerste versie geen aparte
  API-velden.
- Tekstassets worden door Product Factory in `description` opgenomen. Alleen binaire bestanden
  staan in `attachments`.
- Attachments ondersteunen in deze story alleen `image/png`, `image/jpeg`, `image/webp` en
  `application/pdf`.
- Een attachment heeft een veilige bestandsnaam, maximaal 5 MiB gedecodeerde inhoud en een geldige
  SHA-256. Er zijn maximaal 10 attachments en maximaal 25 MiB gedecodeerde attachments per story.
- Software Factory controleert Base64, werkelijke grootte en SHA-256 voordat het bestand wordt
  opgeslagen.

### Verwerking

De implementatie hergebruikt zo veel mogelijk de bestaande v1-route en bridge:

1. valideer token, verzoek en attachments;
2. zoek via de idempotentiemarker of de story al bestaat;
3. maak de story zo nodig met `start=false`;
4. stuur ieder attachment apart over de bridge en sla het op als story-attachment;
5. queue de story pas nadat alle attachments zijn opgeslagen;
6. retourneer de storykey.

Gebruik voor Product Factory-bestanden een herkenbare naam, bijvoorbeeld
`product-factory-input__<attachmentId>__<fileName>`. De nieuwe bridge-operatie voor het opslaan van
een attachment is idempotent op storykey en deze naam: dezelfde bytes zijn een succesvolle
herhaling; andere bytes onder dezelfde naam geven een conflict. Hierdoor kan een retry na een
gedeeltelijke upload de ontbrekende attachments alsnog opslaan zonder duplicaten.

De storyomschrijving bevat naast de aangeleverde Markdown alleen kleine machineleesbare markers
voor `productId`, `sourceStoryId`, `sourceStoryVersion` en `Idempotency-Key`. Een aparte
ontvangsttabel of generieke ontvangst-state-machine hoort niet bij deze story.

### Response

Eerste aanmaak geeft HTTP 201; een idempotente herhaling geeft HTTP 200:

```json
{
  "storyKey": "SF-2314",
  "created": true,
  "status": "OPEN"
}
```

Een herhaling met dezelfde idempotentiesleutel maakt nooit een tweede story. Als een eerdere poging
na storyaanmaak of tussen attachments stopte, hervat de herhaling de attachmentopslag en wordt de
story pas daarna gequeued.

## Queries

`GET /stories/{storyKey}` retourneert:

```json
{
  "storyKey": "SF-2314",
  "productId": "hkh",
  "sourceStoryId": "550e8400-e29b-41d4-a716-446655440000",
  "sourceStoryVersion": 3,
  "status": "OPEN",
  "deliveredCommitSha": null,
  "cancelReason": null,
  "updatedAt": "2026-08-24T14:30:00Z"
}
```

De lijstquery retourneert dezelfde objecten onder `items`:

```json
{
  "items": [
    {
      "storyKey": "SF-2314",
      "productId": "hkh",
      "sourceStoryId": "550e8400-e29b-41d4-a716-446655440000",
      "sourceStoryVersion": 3,
      "status": "OPEN",
      "deliveredCommitSha": null,
      "cancelReason": null,
      "updatedAt": "2026-08-24T14:30:00Z"
    }
  ]
}
```

Alle interne Software Factory-fasen worden vertaald naar:

| Status | Betekenis |
|---|---|
| `OPEN` | De story bestaat en is nog niet afgerond of geannuleerd |
| `DONE` | De story is opgeleverd; `deliveredCommitSha` bevat verplicht de volledige mergecommit |
| `CANCELLED` | De story is bewust verwijderd of geannuleerd; waar mogelijk is `cancelReason` gevuld |

Een interne technische fout blijft extern `OPEN`. Product Factory gebruikt geen interne subtaken,
agentfasen of vraagstatussen.

## Fouten

Fouten hebben één kleine vorm:

```json
{
  "code": "ATTACHMENT_HASH_MISMATCH",
  "message": "Attachment appointments-empty-mobile heeft niet de opgegeven SHA-256.",
  "retryable": false
}
```

Minimaal ondersteund:

| HTTP | Betekenis |
|---|---|
| 400 | Ongeldig verzoek, Base64, hash, URL of bestandsnaam |
| 401 | Ongeldig of ontbrekend integratietoken |
| 404 | Story niet gevonden |
| 409 | Conflicterende idempotente attachmentherhaling |
| 413 | Attachment of totaal te groot |
| 415 | Niet ondersteund MIME-type |
| 502 | Ongeldig antwoord van de bridge |
| 503 | Software Factory niet verbonden |

Bij een timeout of retrybare fout verstuurt Product Factory exact hetzelfde verzoek opnieuw met
dezelfde `Idempotency-Key`.

## Acceptatiecriteria

1. Een geldig verzoek zonder attachments maakt precies één story en queuet haar.
2. Een geldig verzoek met meerdere attachments slaat alle ruwe bestanden bij de story op voordat
   de story wordt gequeued.
3. Een ongeldige Base64, grootte, hash, bestandsnaam of MIME-type wordt geweigerd en de story wordt
   niet gequeued.
4. Dezelfde idempotentiesleutel maakt bij herhaling geen tweede story en geen dubbele attachments.
5. Een retry na een onderbroken attachmentupload vult de ontbrekende attachments aan en queuet pas
   daarna de story.
6. Product Factory kan een story terugvinden via storykey en via idempotentiesleutel.
7. Product Factory kan alle `OPEN` stories voor een product opvragen.
8. De statusquery retourneert uitsluitend `OPEN`, `DONE` of `CANCELLED`; bij `DONE` staat de
   volledige `deliveredCommitSha` in de response.
9. Bestaande `/api/integrations/v1`-routes blijven ongewijzigd werken.
10. Controller-, bridge- en integratietests dekken bovenstaande scenario's.

## Buiten scope

Deze story bouwt nadrukkelijk niet:

- een Software Factory- of Product Factory-UI;
- een apart OpenAPI-codegeneratieproject;
- een generieke ontvangstworkflow of nieuwe ontvangsttabellen;
- RFC 8785-pakketcanonicalisatie of een aparte `contentHash`;
- textassetopslag naast de storyomschrijving;
- callbacks, webhooks, vragen of answer-endpoints;
- attachmentweergave in het dashboard;
- automatische materialisatie naar een nieuw `input/product-factory`-pad in agentworkspaces;
- wijziging of verwijdering van v1.

Het beschikbaar maken van story-attachments in agentworkspaces kan later als aparte, kleine story
worden toegevoegd. Deze API-story zorgt er al voor dat Product Factory v2 stories betrouwbaar kan
aanmaken, terugvinden, volgen en van attachments kan voorzien.

## Gerelateerde documentatie

- [Huidige HTTP-endpoints](technical/endpoints.md)
- [Huidige technische architectuur](technical/overview.md)
- [Huidige externe systemen](technical/external-systems.md)
