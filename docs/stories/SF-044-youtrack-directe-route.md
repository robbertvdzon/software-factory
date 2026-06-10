# SF-044 - YouTrack via directe in-cluster route i.p.v. Cloudflare-tunnel

## Story

De orchestrator-poll vertoonde terugkerende latency-pieken: de meeste
YouTrack-calls waren snel (~40ms), maar elke ~20-40s schoot een losse
`GET /api/issues` omhoog tot 1-6 seconden. Aanleiding was bovendien dat de
YouTrack-pod op OpenShift `OutOfMemoryError`-waarschuwingen logde vanuit de
Xodus database-GC.

Doel: uitzoeken waar de traagheid vandaan komt en de factory→YouTrack-verbinding
betrouwbaar en snel maken — zonder de hele transport te herbouwen.

## Stappenplan

[x]: YouTrack-geheugen op OpenShift onderzoeken; Xodus-GC `risk of OutOfMemoryError` verklaren.
[x]: JVM-heap van YouTrack vergroten naar `-Xmx2g` (deploy in de `personal-news-feed` repo) en verifiëren dat de GC-aborts verdwijnen.
[x]: Latency van de poller meten en de pieken karakteriseren.
[x]: GC, Xodus-cleaner, CPU-throttling en heap uitsluiten als oorzaak (in-pod meting = 2ms, vlak).
[x]: Bevestigen dat keep-alive al aanstaat in de poller-client → handshakes zijn niet de oorzaak.
[x]: Aantonen dat de pieken in het Cloudflare-tunnel-pad zitten, niet in YouTrack.
[x]: Directe in-cluster OpenShift-route ontdekken en meten (LAN, geen tunnel).
[x]: `YouTrackClient` de cluster-ingress-CA laten vertrouwen zodat de directe (lab-cert) route werkt.
[x]: Module compileren (BUILD SUCCESS).
[ ]: `SF_YOUTRACK_BASE_URL` op de directe route zetten in de runtime-config.
[ ]: Factory herbouwen/herstarten en de poll-latency opnieuw meten.
[ ]: Eventueel dezelfde CA-fix toepassen op de `dashboard-backend` YouTrackClient als die ook de directe route gaat gebruiken.

## Uitwerking

### Diagnose

Metingen van dezelfde `GET /api/issues`-query vanaf verschillende plekken:

| Pad | baseline/call | pieken |
|---|---|---|
| In-cluster (`localhost:8080` in de pod) | ~2 ms | geen |
| Directe LAN-route (verse verbinding per call) | ~22 ms | geen |
| Cloudflare-tunnel + keep-alive | ~34 ms | wel (prod-log) |
| Cloudflare-tunnel, verse verbinding | ~90 ms | tot 1,5-6 s |

Daarmee zijn JVM-GC (0 full GCs, <2s totaal), de Xodus-cleaner, CPU-throttling
(cumulatief, liep niet op tijdens load) en de heap uitgesloten. De server
antwoordt constant in ~2ms; de variabiliteit en de pieken zitten volledig in het
**Cloudflare-tunnel-pad** tussen de (lokaal draaiende) factory en de cluster.
Keep-alive stond al aan, dus connection-pooling bracht niets extra's.

### Oplossing

De cluster is op het LAN bereikbaar (node `192.168.178.64`) en exposeert een
directe OpenShift-route `youtrack-youtrack.apps.sno.lab.vdzon.com` die de tunnel
volledig overslaat. De factory praat YouTrack nu via die route aan
(`SF_YOUTRACK_BASE_URL`), wat de baseline verlaagt én de tunnel-pieken elimineert.

De route gebruikt edge-TLS met het cluster-ingress-cert (`CN=*.apps.sno.lab.vdzon.com`,
ondertekend door de lab `ingress-operator`-CA). De default `HttpClient`
(`java.net.http`) vertrouwde dat niet → `PKIX path building failed`. Daarom bouwt
`YouTrackClient` de `HttpClient` nu met een SSLContext en een **gemergede
X509TrustManager**: eerst de publieke CA's (default cacerts), en als die falen het
lab-CA. Zo blijven publieke calls (Anthropic/GitHub) werken en wordt de directe
route vertrouwd. Het CA-bestand staat als classpath-resource:
`src/main/resources/certs/cluster-ingress-ca.crt`. Ontbreekt het, dan valt de code
terug op `HttpClient.newHttpClient()`.

### Aandachtspunten

- De directe route is alleen op het LAN bereikbaar; draait de factory elders, dan
  is de tunnel-URL (of een fallback) nodig.
- Het ingress-cert roteert (nu geldig tot 2028). Bij rotatie moet
  `cluster-ingress-ca.crt` opnieuw geëxporteerd worden:
  `oc get configmap default-ingress-cert -n openshift-config-managed -o jsonpath='{.data.ca-bundle\.crt}'`.
- De YouTrack-heap-fix (`-Xmx2g`) zelf leeft in de `personal-news-feed` repo
  (OpenShift-deploy), niet in deze repo.
