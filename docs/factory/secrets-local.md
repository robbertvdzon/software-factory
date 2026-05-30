# Secrets Local

Lokale secrets staan in `secrets.env` in de root van deze repository. Dit
bestand staat in `.gitignore` en mag niet gecommit worden.

Gebruik `secrets.env.example` als template.

Verplichte keys:

```env
SF_YOUTRACK_BASE_URL=
SF_YOUTRACK_TOKEN=
SF_YOUTRACK_PROJECTS=
SF_GITHUB_TOKEN=
SF_DATABASE_URL=
SF_DATABASE_SCHEMA=software_factory
```

Database-keuze:

- Thuis kun je `SF_DATABASE_URL` naar Neon laten wijzen.
- Op werk kun je de lokale Docker Postgres starten met `./factory local-db`
  en deze waarden gebruiken:

```env
SF_DATABASE_URL=postgresql://software_factory:software_factory@localhost:5432/software_factory
SF_DATABASE_SCHEMA=software_factory_dev
```

Voor branch/story-werk mag `SF_DATABASE_SCHEMA` ook bijvoorbeeld
`software_factory_sf_020` zijn. Gebruik nooit `factory`; dat schema hoort bij
een ander systeem.

Optionele keys, afhankelijk van tester/AI-runtime:

```env
SF_KUBECONFIG=
SF_AI_CREDENTIALS_DIR=
SF_AI_OAUTH_TOKEN=
SF_COPILOT_CREDENTIALS_DIR=
SF_COPILOT_TOKEN=
SF_SECRETS_FILE=
SF_POLL_INTERVAL_MS=15000
SF_MAX_PARALLEL_REFINER=1
SF_MAX_PARALLEL_DEVELOPER=2
SF_MAX_PARALLEL_REVIEWER=2
SF_MAX_PARALLEL_TESTER=1
SF_MAX_PARALLEL_TOTAL=4
SF_MAX_DEVELOPER_LOOPBACKS=5
SF_MAX_TRANSIENT_RETRIES=2
SF_AGENT_HARD_TIMEOUT_MINUTES=60
```

Regel: alle environment variables die door deze factory gelezen of aan
agent-containers doorgegeven worden, beginnen met `SF_`.

De applicatie leest standaard `./secrets.env`. Als een key daarin ontbreekt of
leeg is, valt de applicatie terug op de system environment variable met dezelfde
naam. Ontbreekt een verplichte key in beide bronnen, dan start de applicatie
niet.
