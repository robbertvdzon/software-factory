# Lokale configuratie en secrets

## Laadvolgorde

`SecretsEnvLoader` voegt vier lagen samen; een latere laag wint:

1. `properties.default.env` — committed defaults;
2. `properties.env` — lokale gitignored overrides;
3. `secrets.env` — lokale gitignored secrets;
4. echte environmentvariabelen.

Gebruik `secrets.env.example` als startpunt. Commit nooit `secrets.env`, tokens, kubeconfigs of
providercredentials.

## Verplicht voor de hoofdapp

```env
SF_GITHUB_TOKEN=
SF_DATABASE_URL=postgresql://user:password@host:5432/database
SF_DATABASE_SCHEMA=software_factory_dev
SF_AGENT_RUNTIME_TOKEN=
```

De eerste drie keys worden door `FactorySecrets` bij opstart afgedwongen. Zonder
`SF_AGENT_RUNTIME_TOKEN` kan de app wel starten, maar geen AI-job of Telegram-assistentbeurt
uitvoeren.

`SF_DATABASE_SCHEMA` moet een geldige PostgreSQL-identifier zijn en mag niet `factory` zijn.

## Projecten en bestanden

```env
SF_TRACKER_PROJECTS=
SF_TRACKER_ATTACHMENTS_DIR=attachments
SF_PROJECTS_FILE=projects.yaml
SF_SECRETS_FILE=/optioneel/absoluut/pad/secrets.env
```

Een lege `SF_TRACKER_PROJECTS` laat de tracker bestaande projectkeys ontdekken. De projectcatalogus
(projectnaam, repositoryconfiguratie, Runtime-alias, base branch, Telegram, previews en
mergechecks) staat in de database en wordt via het Settings-scherm beheerd; `SF_PROJECTS_FILE`
wijst alleen naar het bestand waarmee een lege database eenmalig wordt gevuld. Het bevat geen
providercredential.

## Runtime

```env
SF_AGENT_RUNTIME_TOKEN=
SF_AGENT_RUNTIME_URL=https://agent-runtime.vdzonsoftware.nl
SF_AGENT_RUNTIME_POLL_MS=2000
SF_AGENT_RUNTIME_MAX_REPAIR_ATTEMPTS=3
SF_ASSISTANT_TIMEOUT_SECONDS=3600
```

`SF_AGENT_RUNTIME_TOKEN` is een Software Factory-tenanttoken. AI-providercredentials en de
Gitpublicatiecredentials van de Runtime-worker horen uitsluitend in Agent Runtime en staan niet in
deze repository of deployment.

De jobtimeout wordt per request doorgegeven. De Telegramtimeout hierboven is alleen de default voor
een assistentbeurt.

## GitHub en OpenShift

```env
SF_GITHUB_TOKEN=
SF_GITHUB_PACKAGES_TOKEN=
SF_KUBECONFIG=
SF_PREVIEW_CLEANUP_KUBECONFIG=
```

`SF_GITHUB_TOKEN` is nodig omdat Software Factory zelf branches, pull requests, merges en releases
beheert. Geef alleen de vereiste repositoriescopes. Gebruik voor packagecleanup een apart minimaal
token met `read:packages`/`delete:packages`.

`SF_KUBECONFIG` is alleen voor de preview-/deployment-/clusterhandelingen die de factory zelf
beheert. `SF_PREVIEW_CLEANUP_KUBECONFIG` kan die destructieve cleanup apart begrenzen en valt anders
terug op `SF_KUBECONFIG`. Geen van beide gaat naar Agent Runtime of een AI-prompt.

## Telegram

```env
SF_TELEGRAM_BOT_TOKEN=
SF_TELEGRAM_CHAT_ID=
SF_DASHBOARD_BASE_URL=
```

Bot-token en standaardchat-id moeten beide gezet zijn om Telegram te activeren. Projectspecifieke
chat-id's staan in de projectcatalogus (Settings-scherm). De conversationele assistent gebruikt Agent Runtime v2 en geen
apart Claude-/Codex-token of assistantimage.

## Machinetokens

```env
SF_FACTORY_API_TOKEN=
SF_PRODUCT_FACTORY_TOKEN=
```

`SF_PRODUCT_FACTORY_TOKEN` beschermt `/api/integrations/v1` en `/api/integrations/v2`.
`SF_FACTORY_API_TOKEN` beschermt de tracker-API (`/api/tracker/*`). Deze tokens zijn onderling niet
uitwisselbaar en geen dashboardsessie.

## Dashboardlogin

```env
SF_GOOGLE_CLIENT_ID=
SF_ALLOWED_EMAILS=user@example.com
SF_DASHBOARD_REMEMBER_SECRET=
```

Het sessietoken is dertig dagen geldig en wordt met `SF_DASHBOARD_REMEMBER_SECRET` ondertekend.
Een lege allowlist, een leeg geheim of een foutieve Google-clientconfig maakt de login
fail-closed. De frontend moet een 401/sessieverloop naar een zichtbare nieuwe loginroute
sturen; alleen een foutbanner “Log opnieuw in” is onvoldoende.

## Targetprojectsecrets veranderen niet

Een gitignored `secrets.env` dat naast een targetrepository in iemands persoonlijke gitmap staat,
wordt niet gelezen, gekopieerd, gemount, gesynchroniseerd, gewijzigd of gepusht. Er komt geen
netwerkshare voor. De eigenaar blijft dat bestand zelf beheren zoals voorheen.

## Logging en tests

`FactorySecrets.toString()` en `redactedSummary()` maskeren tokens en databasecredentials. Log nooit
de volledige resolved environment. Tests gebruiken fictieve waarden en controleren dat Runtime-
requests geen provider-, Git-, database-, Telegram- of projectsecret bevatten.
