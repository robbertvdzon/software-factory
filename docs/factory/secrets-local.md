# Secrets Local

Lokale secrets staan in `secrets.env` in de root van deze repository. Dit
bestand staat in `.gitignore` en mag niet gecommit worden.

Gebruik `secrets.env.example` als template.

Verplichte keys:

```env
SF_GITHUB_TOKEN=
SF_DATABASE_URL=
SF_DATABASE_SCHEMA=software_factory
```

De verplichte keys staan in `FactorySecrets.REQUIRED_KEYS`; ontbreekt er één
(in `secrets.env` én in de system environment), dan start de applicatie niet.

Optioneel: beperk de tracker-scan tot specifieke projectkeys. Leeg laten
betekent dat de factory alle project_key's gebruikt die al in de eigen
tracker-tabellen voorkomen.

```env
SF_TRACKER_PROJECTS=
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

Dashboard-login via Google-SSO en links voor meldingen. `dashboard-backend` logt
in met een Google **ID-token** (`POST /api/v1/auth/google`) i.p.v. username/password:
`SF_GOOGLE_CLIENT_ID` (de OAuth-web-client-ID = audience) is **verplicht**, net als
`SF_DASHBOARD_REMEMBER_SECRET` (ondertekent het HMAC-sessie-token — geen fallback meer
op een wachtwoord). `SF_ALLOWED_EMAILS` is een komma-gescheiden allowlist van toegestane,
geverifieerde e-mailadressen (default `robbert@vdzon.com`); alleen deze adressen krijgen
een sessie-token. Zonder een verplichte waarde start dashboard-backend niet op.

De frontend heeft dezelfde web-client-ID nodig als build-time waarde
`SF_GOOGLE_CLIENT_ID` (doorgegeven als `--dart-define=GOOGLE_CLIENT_ID` in
`docker/docker-compose.yml`). Het aanmaken van de OAuth-client in Google Cloud Console
is een externe, handmatige stap.

```env
SF_GOOGLE_CLIENT_ID=<oauth-web-client-id>.apps.googleusercontent.com
SF_ALLOWED_EMAILS=robbert@vdzon.com
SF_DASHBOARD_REMEMBER_SECRET=<kies-een-sterk-geheim>
SF_DASHBOARD_BASE_URL=
SF_DASHBOARD_REMEMBER_DAYS=30
SF_DASHBOARD_COOKIE_SECURE=false
```

Sinds de bridge-architectuur (zie `docs/ontwerp-bridge-dashboard.md`) heeft
`dashboard-backend` geen eigen tracker-, database- of GitHub-toegang meer —
alleen bovenstaande login-keys plus de bridge-token hieronder.

Bridge tussen de factory (client) en `dashboard-backend` (server, "de hub"):
`SF_BRIDGE_URLS` (op de factory) is een komma-gescheiden lijst van uitgaande
websocket-URL's, leeg = bridge uit. `SF_BRIDGE_TOKEN` moet op **beide** kanten
gelijk zijn (factory-hello ↔ backend-check); leeg op de backend weigert elke
hello.

```env
SF_BRIDGE_URLS=ws://localhost:8081/bridge
SF_BRIDGE_TOKEN=<gedeeld-geheim>
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

De Telegram-assistent (zie `functional-spec.md`) draait `claude` in een aparte
container en is alleen actief wanneer er een Claude-token (`SF_AI_OAUTH_TOKEN`)
is gezet. De container-image is standaard `assistant:local` (`Dockerfile.assistant`,
overschrijfbaar met `SF_ASSISTANT_IMAGE`) en wordt na `SF_ASSISTANT_TIMEOUT_SECONDS`
(default 3600s) hard afgebroken.

De agent-workspaces onder `work/` worden na elke agent-run opgeruimd. Dat is uit
te zetten met `SF_AGENT_WORKSPACE_CLEANUP_ENABLED=false`, en met
`SF_AGENT_WORKSPACE_PRESERVE_ON_FAILURE=true` blijft de workspace van een
mislukte run staan voor analyse.

Als achtervang bovenop die event-gedreven opruiming draait een scheduled
achtervang-cleanup (`WorkCleanupPoller`, elk uur) die de `work/`-mappen die
langer dan `SF_WORK_CLEANUP_RETENTION_DAYS` (default 7 dagen) niet meer zijn
aangeraakt alsnog verwijdert — nuttig na crashes of gekilde processen. Uit te
zetten met `SF_WORK_CLEANUP_ENABLED=false`.

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
SF_ASSISTANT_IMAGE=assistant:local
SF_ASSISTANT_TIMEOUT_SECONDS=3600
SF_AGENT_WORKSPACE_CLEANUP_ENABLED=true
SF_AGENT_WORKSPACE_PRESERVE_ON_FAILURE=false
SF_WORK_CLEANUP_ENABLED=true
SF_WORK_CLEANUP_RETENTION_DAYS=7
SF_POLL_INTERVAL_MS=60000
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
