---
default_base_branch: main
branch_prefix: ai/
preview_url_template: ""
preview_namespace_template: ""
---

# Deployment

De Software Factory draait in namespace `software-factory` op OpenShift. De hoofdapp is daar als
`software-factory-backend` de enige backenddeployable; de Flutter-webapp en PostgreSQL draaien als
clustercomponenten ernaast. Agent Runtime v2 is een afzonderlijke externe component.

[`../../deploy/README.md`](../../deploy/README.md) is de operationele bron voor manifests, images,
database, PVC en secrets.

## Lokale ontwikkeling

Start voor lokale ontwikkeling de ondersteunende services en de hoofdapp vanuit de root van de
repository:

```bash
./factory local-services
./factory start
```

Stop de ondersteunende services na afloop met:

```bash
./factory local-services-stop
```

Vanuit de root wordt de lokale configuratie op de gebruikelijke locaties gevonden. Voor afwijkende
lokale runs kan `SF_SECRETS_FILE` naar een ander bestand wijzen. Lokale starts zijn uitsluitend voor
ontwikkeling en maken geen deel uit van de productieopstelling.
