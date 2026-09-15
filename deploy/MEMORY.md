# Geheugenprofielen

Profielen vanaf 15 september 2026, gebaseerd op metingen van de draaiende JVM's.
`requests.memory` bepaalt plaatsing; `limits.memory` begrenst de volledige container.
`-Xmx` begrenst alleen de heap. Er blijft ruimte nodig voor metaspace, threads en native buffers.

| Omgeving | Request | Containerlimiet | Max. heap | Configuratie |
|---|---:|---:|---:|---|
| Productie (standaard) | 384 MiB | 640 MiB | 256 MiB | `deploy/base/backend-memory.yaml` |

De bestaande garbage collector blijft expliciet behouden: `-XX:+UseG1GC`. We zetten geen vaste `-Xms`.
De JVM leest `JAVA_TOOL_OPTIONS` zelf. Een expliciete `-Xmx` gaat voor de percentageberekening in bestaande images; een image-rebuild is hiervoor niet nodig.

De statische frontends/readers gebruiken 32 MiB request en 64 MiB limit, ingesteld in de overige `*-memory.yaml` patches.

De geheugenpatches staan na de bestaande patches, zodat overlays die `env` vervangen de JVM-opties niet verwijderen. CPU, databases en imageversies wijzigen niet met deze profielen.

## Valideren en terugzetten

- Bouw de gebruikte omgeving met `kustomize build <pad-van-de-omgeving>` en controleer de uiteindelijke Deployment: één `JAVA_TOOL_OPTIONS`, de juiste heap, request en limit.
- Rol eerst acceptatie/preview uit. Controleer de rollout, health endpoints, restarts/OOM, werkset en relevante zware applicatietaken voordat het profiel als bewezen geldt.
- Wijzig limiet en heap samen in de betreffende patch. Verhoog bij onvoldoende marge eerst het omgevingsprofiel; forceer geen GC om een test te laten slagen.
- Terugzetten kan door de profielcommit in Git te reverten en te pushen. Argo CD volgt `main`; een losse live-patch wordt weer overschreven. Bestaande previews volgen ook de centrale ApplicationSet-patches.

Een lagere request/limit is geen gegarandeerde daling van het werkelijke RAM-verbruik. Een geslaagde start en healthcheck zijn geen belastingtest.
