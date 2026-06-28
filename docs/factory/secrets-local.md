# Secrets Local

Lokale secrets staan in `secrets.env` in de root van deze repository. Dit
bestand staat in `.gitignore` en mag niet gecommit worden.

Gebruik `secrets.env.example` als template.

Verplichte keys:

```env
SF_YOUTRACK_BASE_URL=
SF_YOUTRACK_TOKEN=
SF_GITHUB_TOKEN=
SF_DATABASE_URL=
SF_DATABASE_SCHEMA=software_factory
```

De verplichte keys staan in `FactorySecrets.REQUIRED_KEYS`; ontbreekt er één
(in `secrets.env` én in de system environment), dan start de applicatie niet.

Optioneel: beperk tot specifieke YouTrack-projecten. Leeg laten betekent dat de
factory zelf alle niet-gearchiveerde projecten ontdekt.

```env
SF_YOUTRACK_PROJECTS=
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

Optioneel: publieke YouTrack-URL voor links in de UI (valt terug op
`SF_YOUTRACK_BASE_URL` wanneer leeg):

```env
SF_YOUTRACK_PUBLIC_URL=
```

Dashboard-login en links voor meldingen (de gebruikersnaam valt terug op
`admin`; `SF_DASHBOARD_REMEMBER_SECRET` ondertekent de remember-me-cookie en valt
terug op het wachtwoord wanneer leeg):

```env
SF_DASHBOARD_USERNAME=admin
SF_DASHBOARD_PASSWORD=admin
SF_DASHBOARD_BASE_URL=
SF_DASHBOARD_REMEMBER_SECRET=
SF_DASHBOARD_REMEMBER_DAYS=30
SF_DASHBOARD_COOKIE_SECURE=false
```

Optioneel: token dat de `POST /api/restart`-endpoint beschermt (leeg => endpoint
geeft 404/uit):

```env
SF_FACTORY_API_TOKEN=
```

Telegram-meldingen (beide leeg => uitgeschakeld):

```env
SF_TELEGRAM_BOT_TOKEN=
SF_TELEGRAM_CHAT_ID=
```

Optionele keys, afhankelijk van tester/AI-runtime:

```env
SF_KUBECONFIG=
SF_AI_CREDENTIALS_DIR=
SF_AI_OAUTH_TOKEN=
SF_CODEX_CREDENTIALS_DIR=
SF_COPILOT_CREDENTIALS_DIR=
SF_COPILOT_TOKEN=
SF_SECRETS_FILE=
SF_PROJECTS_FILE=projects.yaml
SF_POLL_INTERVAL_MS=1000
SF_POLL_INTERVAL_IDLE_MS=1000
SF_MAX_PARALLEL_REFINER=1
SF_MAX_PARALLEL_DEVELOPER=2
SF_MAX_PARALLEL_REVIEWER=2
SF_MAX_PARALLEL_TESTER=1
SF_MAX_PARALLEL_TOTAL=4
SF_MAX_DEVELOPER_LOOPBACKS=5
SF_MAX_TEST_CHAIN_RESETS=3
SF_MAX_TRANSIENT_RETRIES=2
SF_AGENT_HARD_TIMEOUT_MINUTES=60
SF_ACTIVE_PHASE_RECOVERY_DELAY_MS=60000
SF_COST_MONITOR_INTERVAL_MS=300000
SF_CREDITS_PAUSE_DEFAULT_MINUTES=30
```

Regel: alle environment variables die door deze factory gelezen of aan
agent-containers doorgegeven worden, beginnen met `SF_`.

De applicatie leest standaard `./secrets.env`. Als een key daarin ontbreekt of
leeg is, valt de applicatie terug op de system environment variable met dezelfde
naam. Ontbreekt een verplichte key in beide bronnen, dan start de applicatie
niet.
