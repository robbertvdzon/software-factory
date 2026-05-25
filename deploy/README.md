# Software Factory dashboard deploy

This deploys only the dashboard surface:

- `softwarefactory-dashboard-backend`: small API that reads YouTrack/Neon and queues commands by posting YouTrack comments.
- `softwarefactory-dashboard-frontend`: Flutter web app served by nginx; `/api/*` is proxied to the backend service.

The local orchestrator on the laptop remains responsible for polling YouTrack, starting agents and processing results.

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
