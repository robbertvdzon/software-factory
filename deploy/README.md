# Software Factory deploy

Dit deployt de volledige Software Factory in namespace `software-factory`:

- `software-factory-backend`: de factory zelf (orchestrator, tracker, pipeline, audits,
  Telegram, dashboard-API en Product Factory-integratie), gebouwd uit `softwarefactory/Dockerfile`.
  Het image bevat `gh`, `kubectl` en `oc`; de pod draait als ServiceAccount `sf-preview-cleanup`
  (robberts-infrastructure) en gebruikt de in-cluster credentials, dus geen kubeconfig.
- `software-factory-frontend`: Flutter web app served by nginx; `/api/*` is proxied to the backend service.
- `database`: eigen PostgreSQL 16 (StatefulSet, PVC `software-factory-database-data`), zie `base/database.yaml`.
- PVC `software-factory-attachments`: storybijlagen, gemount op `/var/lib/software-factory/attachments`.

De Agent Runtime-worker is geen onderdeel van deze deploy; die draait apart.

## Cluster setup: namespace

No manual step anymore: since the cluster's ArgoCD instance runs cluster-scoped (2026-07-08),
the ArgoCD Application creates the `software-factory` namespace itself via `CreateNamespace=true`.
`deploy/base/namespace.yaml` stays out of `kustomization.yaml`'s resources and is kept only as
documentation. (Historical note: in the earlier "namespaced mode" setup this required a manual
`oc apply -f deploy/base/namespace.yaml` before the first sync — a step that was found missing
from every bootstrap script/playbook on 2026-07-08; see
`robberts-infrastructure/docs/architecture.md`, "Historie".)

## Secrets

De secrets van de factory komen uit één Sealed Secret (`software-factory-secrets`): de
`SF_*`-sleutels uit `deploy/secrets-cluster.env` (kopieer `deploy/secrets-cluster.env.example`,
gitignored). De projectcatalogus staat in de database en wordt via het dashboard beheerd; een
`deploy/projects-cluster.yaml` wordt alleen nog als `SF_PROJECTS_YAML` gesealed en gemount om een
lege database eenmalig te vullen.

```bash
./deploy/seal-secrets.sh
```

The generated `deploy/base/sealed-secret.yaml` is encrypted for the current cluster and can be committed.
Een wijziging in secrets is dus: bestand aanpassen, opnieuw sealen, committen, ArgoCD syncen. De
projectcatalogus wijzig je op het Settings-scherm, zonder deploy.

## Normal deploy

`deploy/base` expects images in GHCR:

- `ghcr.io/robbertvdzon/software-factory-backend:main`
- `ghcr.io/robbertvdzon/software-factory-frontend:main`

Apply with:

```bash
oc apply -k deploy/base
```

### How those images are built

Both images are built and pushed by `.github/workflows/software-factory-backend-image.yml` (the factory,
from `softwarefactory/Dockerfile`, with the commit baked in as `SF_BUILD_COMMIT`) and
`.github/workflows/software-factory-frontend-image.yml` (the frontend workflow also builds and releases
the Android APK). They start in two ways:

- Automatically, on a successful `Repository verification` (`workflow_run`) run — but only when
  that run was triggered by a **push** on branch `main` **in this repository itself**. Runs coming
  from a fork or from a pull request no longer start a build, so build credentials and the Android
  signing key stay out of reach of forked code (SF-1550).
- Manually, via `workflow_dispatch` on the image workflow itself. A manual dispatch of
  `verify.yml` does *not* start an image build; use the image workflow's own dispatch button.

For a normal push to `main` nothing changes: both images are built and pushed and the usual
manifest version bump is opened.

## HTTPS enforcement (SF-2008, 2026-08-07)

The dashboard is HTTPS-only. Two settings enforce that across the Cloudflare/OpenShift boundary:

- `deploy/base/software-factory-frontend-route.yaml` deliberately has
  `insecureEdgeTerminationPolicy: Allow`. Cloudflare terminates public HTTPS and connects to the
  OpenShift router over HTTP; `Redirect` sends the client back to the same public HTTPS URL and
  breaks the dashboard. Public HTTP-to-HTTPS enforcement belongs at Cloudflare.
- `dashboard-frontend/nginx.conf` sends `Strict-Transport-Security: max-age=31536000` on every
  response, so browsers come back over https on their own. Deliberately without
  `includeSubDomains` and without `preload`: those are hard to walk back for the whole domain.
  The header is repeated in every `location` block that has its own `add_header`, because nginx
  masks the server-level `add_header` as soon as the chosen location declares one.

External traffic runs through Cloudflare. HTTP-to-HTTPS must be configured there (for example
"Always Use HTTPS"). Do not change the Route to `Redirect` while the Tunnel origin uses HTTP; keep
the HSTS header and verify the dashboard after every routing change.

## SNO local test deploy

The SNO overlay is only for local testing when GHCR push is unavailable. It expects images loaded onto the single OpenShift node as:

- `localhost/software-factory-backend:main`
- `localhost/software-factory-frontend:main`

The overlay sets `imagePullPolicy: Never`:

```bash
oc apply -k deploy/sno-local
```
