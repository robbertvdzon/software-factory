# Secrets Local

Lokale secrets staan in `secrets.env` in de root van deze repository. Dit
bestand staat in `.gitignore` en mag niet gecommit worden.

Gebruik `secrets.env.example` als template.

Verplichte keys:

```env
SF_JIRA_BASE_URL=
SF_JIRA_EMAIL=
SF_JIRA_API_KEY=
SF_GITHUB_TOKEN=
SF_DATABASE_URL=
SF_DATABASE_SCHEMA=software_factory
```

Optionele keys, afhankelijk van tester/AI-runtime:

```env
SF_KUBECONFIG=
SF_AI_CREDENTIALS_DIR=
SF_AI_OAUTH_TOKEN=
SF_SECRETS_FILE=
```

Regel: alle environment variables die door deze factory gelezen of aan
agent-containers doorgegeven worden, beginnen met `SF_`.

De applicatie leest standaard `./secrets.env`. Als een key daarin ontbreekt of
leeg is, valt de applicatie terug op de system environment variable met dezelfde
naam. Ontbreekt een verplichte key in beide bronnen, dan start de applicatie
niet.
