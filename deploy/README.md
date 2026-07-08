# Software Factory dashboard deploy

This deploys only the dashboard surface:

- `softwarefactory-dashboard-backend`: small API that relays requests to the local orchestrator over an outbound WebSocket bridge (see `docs/ontwerp-bridge-dashboard.md`) — it holds no tracker data itself.
- `softwarefactory-dashboard-frontend`: Flutter web app served by nginx; `/api/*` is proxied to the backend service.

The local orchestrator on the laptop remains responsible for polling the tracker database, starting agents and processing results.

## Cluster setup: namespace

No manual step anymore: since the cluster's ArgoCD instance runs cluster-scoped (2026-07-08),
the ArgoCD Application creates the `software-factory` namespace itself via `CreateNamespace=true`.
`deploy/base/namespace.yaml` stays out of `kustomization.yaml`'s resources and is kept only as
documentation. (Historical note: in the earlier "namespaced mode" setup this required a manual
`oc apply -f deploy/base/namespace.yaml` before the first sync — a step that was found missing
from every bootstrap script/playbook on 2026-07-08; see
`robberts-infrastructure/docs/architecture.md`, "Historie".)

## Secrets

The dashboard uses the same root `secrets.env` keys as the local app. To create the OpenShift secret:

```bash
./deploy/seal-secrets.sh
```

By default the script reads `./secrets.env`. If cluster values need to differ, copy `deploy/secrets-cluster.env.example` to `deploy/secrets-cluster.env`; that file is gitignored and takes precedence.

The generated `deploy/base/sealed-secret-dashboard.yaml` is encrypted for the current cluster and can be committed.

## Normal deploy

`deploy/base` expects images in GHCR:

- `ghcr.io/robbertvdzon/softwarefactory-dashboard-backend:main`
- `ghcr.io/robbertvdzon/softwarefactory-dashboard-frontend:main`

Apply with:

```bash
oc apply -k deploy/base
```

## SNO local test deploy

The SNO overlay is only for local testing when GHCR push is unavailable. It expects images loaded onto the single OpenShift node as:

- `localhost/softwarefactory-dashboard-backend:main`
- `localhost/softwarefactory-dashboard-frontend:main`

The overlay sets `imagePullPolicy: Never`:

```bash
oc apply -k deploy/sno-local
```
