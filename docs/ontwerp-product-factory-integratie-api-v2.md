# Ontwerp — Product Factory-integratie-API v2

Status: ontwerp voor toekomstige implementatie; deze API bestaat nog niet op `main`.

Dit document specificeert de machine-API waarmee Product Factory v2 complete stories aan Software
Factory levert en daarna uitsluitend de leveringsstatus leest. Het document is zelfstandig genoeg
om de API, opslag, bridge, tests en Product Factory-adapter later zonder aanvullende chatcontext te
implementeren.

De bestaande `/api/integrations/v1`-API blijft de beschrijving van de huidige implementatie. V2
gebruikt geen Product Factory-workspace, stelt geen vragen terug aan Product Factory en accepteert
een volledige, onveranderlijke story met alle benodigde UX en bestanden.

## Doel

De grens heeft één richting voor inhoud:

1. Product Factory stuurt één volledig `StoryDeliveryPackage`.
2. Software Factory valideert en bewaart het pakket idempotent.
3. Software Factory maakt de story inclusief alle bestanden uitvoerbaar.
4. Product Factory vraagt periodiek de kleine publieke status op.

Software Factory hoeft nooit een epic, bug, UX-ontwerp, repositorydocument of ander gegeven bij
Product Factory terug te vragen. Product Factory schrijft niets in Software Factory en Software
Factory roept geen Product Factory-endpoint aan.

## Belangrijkste verschillen met v1

De huidige v1-aanmaakroute bevat onder meer `workspaceRunId`, `workspaceCommitSha`, `artifactPath`,
`deliveryMode`, AI-modelkeuzes en vraaginstellingen. Zij ondersteunt geen inkomende Product
Factory-attachments en retourneert bij status de interne Software Factory-storystructuur.

V2:

- verwijdert alle workspacevelden;
- ontvangt de volledige storyinhoud in het verzoek;
- ondersteunt tekstassets en binaire attachments;
- gebruikt een klein, stabiel statuscontract met alleen `OPEN`, `DONE` en `CANCELLED`;
- retourneert bij `DONE` verplicht de exacte volledige oplevercommit;
- biedt geen answer-endpoint en geen vraagroute terug naar Product Factory;
- gebruikt duurzame idempotentie in plaats van zoeken naar een marker in de storyomschrijving;
- lekt geen interne subtaken, agentfasen of workflowstatussen naar Product Factory.

## Basisregels

- De basisroute is `/api/integrations/v2`.
- Alle routes gebruiken `Authorization: Bearer <SF_PRODUCT_FACTORY_TOKEN>`.
- Product Factory gebruikt bij ieder muterend verzoek een stabiele `Idempotency-Key`.
- Een contractgeldig pakket wordt inhoudelijk altijd geaccepteerd. Software Factory wijst geen
  story af omdat zij de oplossing, omvang of prioriteit anders zou kiezen.
- Alleen schema-, limiet-, veiligheids-, authenticatie- of configuratiefouten mogen een pakket
  weigeren.
- Een tijdelijke storing leidt tot een retry met exact dezelfde idempotentiesleutel en inhoud.
- Software Factory start de story pas nadat pakket en alle bestanden duurzaam en gecontroleerd
  beschikbaar zijn.
- De v2-status is een projectie. Interne fasen blijven intern.

## Endpoints

| Methode en pad | Betekenis |
|---|---|
| `GET /api/integrations/v2/status` | verbinding, Software Factory-versie, API-versie en capabilities |
| `POST /api/integrations/v2/stories` | volledig storypakket idempotent aannemen en uitvoerbaar maken |
| `GET /api/integrations/v2/stories/{storyKey}` | actuele publieke status van één externe story |
| `GET /api/integrations/v2/products/{productId}/stories?status=OPEN` | alle nog open externe stories van één Product Factory-product |

Een apart lookup-endpoint voor een idempotentiesleutel is niet nodig. Een herhaald
`POST /stories` met dezelfde sleutel en dezelfde inhoud retourneert altijd hetzelfde resultaat,
ook wanneer de eerste succesvolle response bij de client verloren ging.

## Status en capabilities

### Request

```http
GET /api/integrations/v2/status
Authorization: Bearer <token>
```

### Response

```json
{
  "connected": true,
  "factoryVersion": "2.14.0",
  "apiVersion": "2",
  "capabilities": [
    "COMPLETE_STORY_PACKAGE",
    "BINARY_ATTACHMENTS_BASE64",
    "DELIVERED_COMMIT_SHA",
    "OPEN_STORIES_BY_PRODUCT",
    "NO_PRODUCT_FACTORY_QUESTIONS"
  ]
}
```

`connected = false` betekent dat de dashboard-backend de lokale Software Factory niet kan
bereiken. De route mag dan HTTP 200 retourneren om diagnose mogelijk te houden; muterende routes
geven in die toestand HTTP 503.

## Een story aanmaken

### Request

```http
POST /api/integrations/v2/stories
Authorization: Bearer <token>
Idempotency-Key: product-factory:hkh:story:550e8400-e29b-41d4-a716-446655440000:v3
Content-Type: application/json
```

```json
{
  "productId": "hkh",
  "storyId": "550e8400-e29b-41d4-a716-446655440000",
  "storyVersion": 3,
  "type": "PRODUCT_STORY",
  "targetRepositoryUrl": "https://github.com/example/hkh.git",
  "title": "Toon lege afsprakenlijst",
  "summary": "De afsprakenpagina laat duidelijk zien dat er nog geen afspraken zijn. De gebruiker krijgt meteen een passende vervolgstap.",
  "fullStory": "## Gedrag\n...de volledige zelfstandige story in Markdown...",
  "userBehavior": "Een gebruiker zonder afspraken ziet een herkenbare lege toestand.",
  "userValue": "De gebruiker begrijpt de situatie en weet hoe een eerste afspraak wordt gemaakt.",
  "acceptanceCriteria": [
    "Zonder afspraken verschijnt de afgesproken lege toestand.",
    "De primaire actie is met toetsenbord en schermlezer bruikbaar."
  ],
  "scenarios": [
    {
      "kind": "EMPTY",
      "description": "De gebruiker opent de afsprakenpagina zonder bestaande afspraken."
    },
    {
      "kind": "ERROR",
      "description": "De lijst kan niet worden geladen en toont de afgesproken fouttoestand."
    }
  ],
  "uxDesign": {
    "descriptionMarkdown": "## UX\n...zelfstandige UX-beschrijving...",
    "attachmentRefs": ["appointments-empty-mobile"]
  },
  "dependencies": [
    {
      "storyId": "a6c7f97b-8bd6-4af1-8a80-fd52ac544510",
      "meaning": "De afsprakenquery bestaat."
    }
  ],
  "technicalConstraints": [
    "Behoud de bestaande authenticatiegrens.",
    "Werk de actuele functionele en technische documentatie onder /doc bij."
  ],
  "sourceReferences": [
    {
      "type": "EPIC",
      "id": "7d056c35-8b5c-49a7-bcb1-f09dc3845778",
      "version": 2
    }
  ],
  "textAssets": [
    {
      "id": "empty-state-copy",
      "fileName": "empty-state-copy.md",
      "mediaType": "text/markdown",
      "content": "# Teksten\n...",
      "sizeBytes": 31,
      "sha256": "9d83..."
    }
  ],
  "attachments": [
    {
      "id": "appointments-empty-mobile",
      "fileName": "appointments-empty-mobile.png",
      "mediaType": "image/png",
      "sizeBytes": 183042,
      "sha256": "ab41...",
      "encoding": "BASE64",
      "content": "iVBORw0KGgoAAA..."
    }
  ],
  "contentHash": "sha256:2ad8..."
}
```

De verkorte hashes in dit voorbeeld zijn alleen voor leesbaarheid. Echte SHA-256-waarden bevatten
64 hexadecimale tekens.

### Verplichte velden

| Veld | Regel |
|---|---|
| `productId` | stabiele Product Factory-productidentiteit |
| `storyId` | UUID-string uit Product Factory |
| `storyVersion` | positief geheel getal; de verzonden versie verandert nooit |
| `type` | `PRODUCT_STORY` of `BUGFIX` |
| `targetRepositoryUrl` | publieke HTTPS-Git-URL van het doelproduct; Software Factory gebruikt eigen schrijfcredentials |
| `title` | korte niet-lege enkelregelige titel |
| `summary` | maximaal twee korte zinnen; geen vervanging voor `fullStory` |
| `fullStory` | volledige zelfstandige story in UTF-8 Markdown |
| `userBehavior` | concreet zichtbaar of aantoonbaar gedrag |
| `userValue` | waarom dit werk waarde heeft voor de gebruiker |
| `acceptanceCriteria` | niet-lege lijst met testbare criteria |
| `scenarios` | relevante hoofd-, lege, laad-, fout- en uitzonderingssituaties; alleen toepasselijke situaties zijn verplicht |
| `uxDesign` | `null` zonder UX-impact; anders volledige zelfstandige UX-momentopname met attachmentreferenties |
| `dependencies` | expliciete bronstory's met betekenis; mag leeg zijn |
| `technicalConstraints` | bekende harde grenzen zonder verborgen implementatiedocument; mag leeg zijn |
| `sourceReferences` | exacte epic-, bug- en andere bron-ID's met versie |
| `textAssets` | UTF-8-assets; mag leeg zijn |
| `attachments` | binaire assets; mag leeg zijn |
| `contentHash` | hash van het onveranderlijke semantische pakket |

`sequenceNumber`, lokale Product Factory-status, dispatchreservering en prioriteitsreden worden niet
meegestuurd. Zij hebben geen betekenis meer nadat precies deze story is gekozen voor uitvoering.

`projectKey`, `aiSupplier`, `aiModel`, `deliveryMode`, `questionsAllowed`, `approvalMode` en
`notificationEvents` zijn eveneens geen onderdeel van v2. Software Factory beheert haar eigen
projectmapping, modellen en autonome workflow. De repository-URL en het product-ID zijn de stabiele
integratie-identiteit; als de huidige Software Factory intern nog een projectkey nodig heeft, lost
haar adapter die zelf op.

### Hash van het pakket

`contentHash` gebruikt SHA-256 over RFC 8785-canonieke JSON van een manifest dat uit het verzoek
wordt afgeleid. Verwijder daarvoor het veld `contentHash` en alleen het veld `content` van ieder
binair attachment. Alle overige storygegevens blijven staan, inclusief de inhoud van textassets en
de ID, bestandsnaam, MIME-type, grootte, encoding en SHA-256 van ieder binair attachment. De
afzonderlijke attachmenthash bewijst de bytes. Zo berekenen beide systemen de pakket-hash op exact
dezelfde manier zonder grote Base64-waarden dubbel in het hashmanifest op te nemen.

Software Factory valideert zowel `contentHash` als alle assethashes vóór acceptatie. Een onjuiste
hash is een niet-retrybare contractfout.

### Response bij eerste acceptatie

```http
HTTP/1.1 201 Created
```

```json
{
  "storyKey": "SF-2314",
  "created": true,
  "status": "OPEN",
  "acceptedContentHash": "sha256:2ad8..."
}
```

### Response bij idempotente herhaling

```http
HTTP/1.1 200 OK
```

```json
{
  "storyKey": "SF-2314",
  "created": false,
  "status": "OPEN",
  "acceptedContentHash": "sha256:2ad8..."
}
```

Bij een herhaling retourneert `status` de actuele publieke status. Die kan dus inmiddels ook
`DONE` of `CANCELLED` zijn; de herhaling maakt nooit een tweede story.

## Idempotentie en gedeeltelijke ontvangst

Software Factory bewaart een duurzaam ontvangstrecord met minimaal:

- idempotentiesleutel;
- product-ID, story-ID en storyversie;
- geaccepteerde `contentHash`;
- externe storykey;
- verwachte en ontvangen asset-ID's met hashes;
- interne ontvangststatus;
- fout en laatste wijziging.

De idempotentiesleutel wordt niet alleen in vrije storytekst opgeslagen. Voor de combinatie van
aanroepende integratie en idempotentiesleutel bestaat een unieke databaseconstraint.

De aanmaakroute volgt deze volgorde:

1. valideer authenticatie, schema, limieten, IDs, URL, MIME-types en hashes;
2. maak of hervat het duurzame ontvangstrecord;
3. maak de interne story zonder haar te starten;
4. sla ieder textasset en attachment idempotent op;
5. controleer dat de volledige manifestset duurzaam leesbaar is;
6. maak de bestanden beschikbaar aan de Software Factory-agents;
7. markeer de ontvangst gereed en zet de story in de gewone uitvoeringsqueue.

Bij een crash tussen deze stappen blijft de story niet half uitvoerbaar. Een retry met dezelfde
sleutel en hash hervat ontbrekende stappen. Reeds aanwezige bytes met dezelfde ID en hash worden
niet gedupliceerd.

Dezelfde idempotentiesleutel met een andere `contentHash` geeft HTTP 409
`IDEMPOTENCY_CONFLICT`. Dezelfde Product Factory-story en versie via een andere sleutel geeft
eveneens HTTP 409. Product Factory maakt voor gewijzigde inhoud altijd eerst een nieuwe storyversie
en een nieuwe idempotentiesleutel; een al verzonden versie wordt nooit overschreven.

## Attachments

### Transportcontract

Een binair attachment bevat:

| Veld | Regel |
|---|---|
| `id` | stabiel en uniek binnen het pakket; alleen letters, cijfers, `.`, `_` en `-` |
| `fileName` | veilige losse bestandsnaam zonder pad, `..`, slash, backslash of besturingstekens |
| `mediaType` | genormaliseerd MIME-type uit de ondersteunde allowlist |
| `sizeBytes` | exacte grootte van de gedecodeerde bytes |
| `sha256` | SHA-256 van de gedecodeerde bytes |
| `encoding` | in de MVP uitsluitend `BASE64` |
| `content` | standaard Base64 zonder `data:`-prefix en zonder verborgen externe URL |

Software Factory decodeert streaming of begrensd, controleert de werkelijke grootte, verifieert de
hash en controleert waar mogelijk magic bytes tegen het opgegeven MIME-type. Bestandsnamen worden
niet als pad gebruikt.

### Initiële limieten

Deze limieten zijn onderdeel van het v2-contract en gelden identiek in Product Factory,
dashboard-backend, lokale Software Factory en mocks:

- maximaal 10 binaire attachments per story;
- maximaal 5 MiB gedecodeerd per binair attachment;
- maximaal 25 MiB gedecodeerd voor alle binaire attachments samen;
- maximaal 100 textassets;
- maximaal 512 KiB UTF-8 per textasset;
- maximaal 2 MiB UTF-8 voor alle textassets samen;
- maximaal 40 MiB voor de volledige HTTP-requestbody.

De initiële binaire allowlist is `image/png`, `image/jpeg`, `image/webp` en `application/pdf`.
SVG, JSON, Markdown, HTML en andere tekstformaten blijven `textAssets` en worden niet Base64
gecodeerd. Uitbreiding van allowlist of limieten vereist een contractwijziging en aangepaste
contracttests; een losse omgevingsinstelling mag de twee kanten niet ongemerkt laten verschillen.

De bestaande WebSocket-bridge heeft een kleinere berichtgrens dan het maximale totaalpakket.
Daarom stuurt de dashboard-backend niet het hele pakket als één bridgebericht door. Hij maakt de
story eerst zonder start en verstuurt daarna ieder binair attachment als afzonderlijke begrensde
bridge-operatie. Geen individueel bridgebericht mag de ingestelde bridgegrens overschrijden.

### Opslag en gebruik door agents

Inkomende Product Factory-bestanden zijn andere attachments dan screenshots die Software Factory
zelf tijdens testen maakt. Gebruik een deterministische naam of aparte bronmetadata, bijvoorbeeld
`product-factory-input__<attachmentId>__<fileName>`, zodat beide categorieën nooit worden verward.

Software Factory bewaart de bestanden duurzaam bij de story en materialiseert ze vóór iedere
relevante agenttaak read-only in:

```text
input/product-factory/attachments/<attachmentId>/<fileName>
```

Daarnaast krijgt de agent een manifest met ID, doelpad, MIME-type, grootte, hash en de plekken in
`uxDesign` of `fullStory` die naar het bestand verwijzen. Base64 komt niet in de agentprompt en de
agent hoeft geen Product Factory-URL te openen.

Een onbekende attachmentreferentie, dubbele attachment-ID of ontbrekend bestand blokkeert
acceptatie. De story start nooit met een incompleet UX-ontwerp.

## Status van één story

### Request

```http
GET /api/integrations/v2/stories/SF-2314
Authorization: Bearer <token>
```

### Response tijdens uitvoering

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

### Response na oplevering

```json
{
  "storyKey": "SF-2314",
  "productId": "hkh",
  "sourceStoryId": "550e8400-e29b-41d4-a716-446655440000",
  "sourceStoryVersion": 3,
  "status": "DONE",
  "deliveredCommitSha": "4b825dc642cb6eb9a060e54bf8d69288fbee4904",
  "cancelReason": null,
  "updatedAt": "2026-08-24T16:05:00Z"
}
```

### Publieke statussemantiek

| Status | Betekenis |
|---|---|
| `OPEN` | geaccepteerd en nog niet geannuleerd of volledig opgeleverd; alle interne wachtrij-, ontwikkel-, review-, test-, merge- en deployfasen vallen hieronder |
| `DONE` | Software Factory heeft de story volledig afgerond; `deliveredCommitSha` bevat verplicht de volledige merge- of oplevercommit in het doelrepository |
| `CANCELLED` | Software Factory heeft het werk bewust verwijderd of geannuleerd; `cancelReason` is waar beschikbaar gevuld en `deliveredCommitSha` is `null` |

Een story heeft extern nooit de status `FAILED`. Een interne technische fout laat de story `OPEN`
en blijft operationeel zichtbaar binnen Software Factory. De response mag daarvoor optioneel een
`technicalIssue` met stabiele code en leesbare tekst bevatten, maar Product Factory vertaalt dit
niet naar een mislukte story of nieuw domeinwerk.

`deliveredCommitSha` is een volledige Git-SHA van de commit waarin deze story in het doelrepository
is opgenomen. Een PR-nummer, branchnaam, korte SHA, buildnummer of alleen een gedeployde latere SHA
is niet voldoende. Product Factory gebruikt deze commit daarna als vereiste versie voor testen; het
revisionendpoint van de productomgeving toont afzonderlijk wat werkelijk draait.

## Open stories van een product

### Request

```http
GET /api/integrations/v2/products/hkh/stories?status=OPEN
Authorization: Bearer <token>
```

### Response

```json
{
  "items": [
    {
      "storyKey": "SF-2314",
      "sourceStoryId": "550e8400-e29b-41d4-a716-446655440000",
      "sourceStoryVersion": 3,
      "status": "OPEN",
      "updatedAt": "2026-08-24T14:30:00Z"
    }
  ]
}
```

De dispatcher stuurt normaal pas nieuw werk wanneer deze lijst leeg is. Software Factory weigert
een verder contractgeldig pakket echter niet inhoudelijk wanneer er door een technische of
handmatige afwijking al open werk bestaat; zij bewaart het nieuwe pakket veilig in haar eigen
volgorde. Een lijst met meer dan één item blijft daarom zichtbaar in plaats van stilzwijgend tot
één item te worden gereduceerd.

## Geen vraag- of callbackroute

V2 heeft bewust niet:

- `POST /stories/{storyKey}/answers`;
- een endpoint waarmee Software Factory een vraag bij Product Factory registreert;
- een webhook of callback van Software Factory naar Product Factory;
- een URL in de story waar ontbrekende inhoud later kan worden opgehaald.

Software Factory accepteert het complete pakket en werkt zelfstandig. Product Factory pollt de
status. Wanneer de story ondanks contractvalidatie niet uitvoerbaar blijkt, is dat een Software
Factory- of contractimplementatiefout en geen normale menselijke vraagflow.

## Foutcontract

Iedere foutresponse gebruikt dezelfde vorm:

```json
{
  "code": "ATTACHMENT_HASH_MISMATCH",
  "message": "Attachment appointments-empty-mobile heeft niet de opgegeven SHA-256.",
  "retryable": false,
  "correlationId": "c5ddc6fd-5020-4f5b-91ef-247618c86c16"
}
```

| HTTP | Voorbeelden | Retry |
|---|---|---|
| 400 | ontbrekend veld, ongeldige UUID, hash, URL, referentie of Base64 | nee; pakket of implementatie corrigeren |
| 401 | ontbrekend of verkeerd integratietoken | nee totdat configuratie is hersteld |
| 404 | onbekende storykey of product | nee voor dezelfde query |
| 409 | idempotentiesleutel of bronstoryversie bestaat met andere inhoud | nee; nooit automatisch met nieuwe sleutel maskeren |
| 413 | request, asset of totaal groter dan contractlimiet | nee; storypakket verkleinen of contract bewust wijzigen |
| 415 | niet ondersteund MIME-type of magic bytes komen niet overeen | nee |
| 429 | tijdelijke capaciteitsgrens | ja, volgens `Retry-After` |
| 502 | onbruikbaar antwoord van de lokale bridge/factory | ja met back-off en dezelfde sleutel |
| 503 | lokale Software Factory niet verbonden of tijdelijk niet beschikbaar | ja met back-off en dezelfde sleutel |

Een timeout na ontvangst is een onbekende uitkomst. Product Factory herhaalt dan exact hetzelfde
`POST`-verzoek met dezelfde idempotentiesleutel en `contentHash`.

## Authenticatie en vertrouwensgrens

- Gebruik het bestaande afzonderlijke `SF_PRODUCT_FACTORY_TOKEN` of een later gelijkwaardig
  machinecredential; gebruik geen Google-dashboardsessie of algemeen factory-token.
- Vergelijk tokenwaarden in constante tijd en weiger fail-closed wanneer de serverconfiguratie leeg
  is.
- Log nooit bearer-token, Base64-inhoud of volledige vrije storytekst.
- Log wel correlation-ID, product-ID, story-ID, storyversie, storykey, idempotentiesleutel,
  contenthash, aantallen en totale bytes.
- Repository-URL, storytekst, UX en attachments zijn onvertrouwde input. Zij mogen geen
  Software Factory-systeemregels, credentials of uitvoeringsgrenzen wijzigen.
- Accepteer geen uitvoerbare bestandsformaten in de initiële attachmentallowlist.

## Interne implementatiegrenzen

Het externe contract schrijft geen concrete interne klasse-indeling voor, maar de implementatie
moet minimaal deze verantwoordelijkheden scheiden:

1. **dashboard-backend API-adapter** — authenticatie, HTTP-schema, limieten, foutmapping en
   correlation-ID;
2. **duurzame ontvangst/idempotentie** — packagehash, bronstory, ontvangststappen en hervatten;
3. **story-importservice in Software Factory** — story zonder start maken, content vastleggen,
   repository/project oplossen en pas na complete ontvangst queueën;
4. **attachmentimport** — per bestand decode, grootte/hash/MIME-validatie, duurzame opslag en
   idempotente deduplicatie;
5. **agentinputmaterialisatie** — manifest en read-only bestanden in iedere relevante workspace;
6. **statusprojectie** — interne workflow naar `OPEN`, `DONE` of `CANCELLED` vertalen en de exacte
   oplevercommit bepalen.

De bestaande 8 MiB-WebSocketberichtgrens betekent dat binaire attachments afzonderlijk over de
bridge moeten worden verstuurd. Het externe HTTP-contract blijft één pakket; de interne adapter mag
dit gecontroleerd in hervatbare stappen uitvoeren.

## Mock en contracttests

De echte Software Factory-API, Product Factory-adapter en `MockSoftwareFactory` gebruiken hetzelfde
OpenAPI-contract. Minimaal zijn contract- en integratietests nodig voor:

- geldig pakket zonder assets;
- geldig pakket met textassets en meerdere binaire attachments;
- Base64-, grootte-, MIME- en hashfouten;
- onbekende attachmentreferentie en dubbele attachment-ID;
- eerste acceptatie gevolgd door idempotente herhaling;
- verloren response na geslaagde aanmaak;
- crash na storyaanmaak maar vóór alle attachments en veilig hervatten;
- dezelfde sleutel met andere inhoud;
- dezelfde storyversie met een andere sleutel;
- geen start voordat alle bestanden duurzaam aanwezig zijn;
- agentworkspace bevat de juiste ruwe bestanden en manifest, zonder Base64 in de prompt;
- `OPEN`, `DONE` met verplichte volledige commit en `CANCELLED` met reden;
- open-storyquery met nul, één en meerdere resultaten;
- 401, 429, 502 en 503 met correcte retrybetekenis;
- afwezigheid van een v2-answer- of callbackroute.

## Gefaseerde invoering

1. Leg dit contract vast als OpenAPI-specificatie en laat Software Factory en Product Factory
   daaruit of daartegen hun DTO-contracttests uitvoeren.
2. Voeg duurzame ontvangst en de v2-routes toe zonder v1 te wijzigen.
3. Bouw attachmentimport, agentmaterialisatie en statusprojectie.
4. Laat `MockSoftwareFactory` dezelfde OpenAPI-specificatie implementeren.
5. Laat Product Factory v2 uitsluitend `/api/integrations/v2` gebruiken en voer de volledige
   integratie- en acceptatiescenario's uit.
6. Verwijder `/api/integrations/v1` pas wanneer geen actieve client haar meer gebruikt. Het
   verwijderen van v1 is een afzonderlijke, expliciete wijziging.

## Beslissingen die dit ontwerp vastlegt

- Een nieuwe v2-route is duidelijker dan het bestaande v1-verzoek steeds verder uitbreiden.
- JSON met Base64 is voor de eerste versie voldoende, mits harde limieten en gescheiden interne
  bridge-upload worden toegepast.
- De story start pas na volledige, duurzame attachmentontvangst.
- `deliveredCommitSha` is bij `DONE` verplicht.
- Product Factory leest slechts drie domeinstatussen en geen interne pipelinefasen.
- Software Factory beheert haar eigen modellen, projectmapping en workflowinstellingen.
- Er is geen menselijke vraagroute tussen Software Factory en Product Factory.
- Polling blijft het integratiepatroon; callbacks zijn niet nodig.

## Gerelateerde actuele documentatie

- [Huidige HTTP-endpoints](technical/endpoints.md)
- [Huidige technische architectuur](technical/overview.md)
- [Huidige externe systemen](technical/external-systems.md)
