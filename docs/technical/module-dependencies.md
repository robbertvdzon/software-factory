# Spring Modulith dependency-matrix

Deze pagina wordt deterministisch gegenereerd uit de `allowedDependencies` in de
`package-info.java`-bestanden. Wijzig de metadata en draai `tools/generate-module-dependencies`;
`tools/generate-module-dependencies --check` bewaakt documentatiedrift.

| Module | Verantwoordelijkheid | Toegestane publieke dependencies | Motivatie |
|---|---|---|---|
| `audit` | Planning en uitvoering van read-only audit-runs | `config`, `config :: time`, `core`, `core :: contracts`, `git` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `config` | Configuratie, secrets en composition-root wiring | `core`, `core :: contracts` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `core` | Domeintypes en applicatiepoorten | — | Pure leafmodule zonder uitgaande moduledependency. |
| `dashboard` | Dashboard use-cases en publieke read/write-poorten | `audit`, `audit :: models`, `audit :: repositories`, `audit :: services`, `audit :: types`, `config`, `config :: time`, `core`, `core :: contracts`, `git`, `github`, `knowledge`, `knowledge :: models`, `maintenance`, `maintenance :: repositories`, `maintenance :: types`, `orchestrator`, `pipeline`, `pipeline :: models`, `preview`, `runtime`, `runtime :: models`, `runtime :: v2`, `support`, `telegram`, `telegram :: models`, `tracker`, `tracker :: errors` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `docs` | Factory-documentatie laden en installeren | `core` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `git` | Lokale Git-operaties | `support` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `github` | GitHub-integratie | `config`, `core`, `git`, `support` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `knowledge` | Persistente agentkennis | `config`, `core`, `git` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `merge` | Pull-request mergebeleid | `config`, `github` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `orchestrator` | Procescoördinatie en handmatige commando's | `config`, `core`, `core :: contracts`, `github`, `merge`, `preview`, `support`, `telegram`, `tracker`, `tracker :: errors` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `pipeline` | Story- en subtaskfaseovergangen | `config`, `core`, `core :: contracts`, `github`, `merge`, `preview`, `runtime`, `support`, `tracker` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `preview` | Previewomgevingen | `config`, `git`, `support` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `runtime` | Agentprocessen, workspaces en completion | `config`, `core`, `core :: contracts`, `docs`, `git`, `github`, `knowledge`, `knowledge :: models`, `maintenance`, `maintenance :: repositories`, `maintenance :: types`, `support`, `tracker`, `verification` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `support` | Gedeelde technische primitives | — | Pure leafmodule zonder uitgaande moduledependency. |
| `telegram` | Telegram-assistent en notificatieadapter | `config`, `core`, `core :: contracts`, `knowledge`, `knowledge :: models`, `runtime :: v2`, `support`, `tracker` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `tracker` | Issue-, comment- en attachmentpoorten | `config`, `core`, `core :: contracts` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `verification` | Checkout- en verificatieconfiguratie | `git` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |
| `web` | HTTP-transportadapter | `config`, `core`, `core :: contracts`, `dashboard`, `dashboard :: models`, `knowledge`, `knowledge :: models`, `runtime`, `runtime :: errors`, `runtime :: models`, `runtime :: types`, `tracker`, `tracker :: errors` | Gebruikt uitsluitend de genoemde root-API's/named interfaces voor zijn use-cases. |

## Gegenereerd dependencydiagram

```mermaid
flowchart LR
    audit["audit"]
    config["config"]
    core["core"]
    dashboard["dashboard"]
    docs["docs"]
    git["git"]
    github["github"]
    knowledge["knowledge"]
    merge["merge"]
    orchestrator["orchestrator"]
    pipeline["pipeline"]
    preview["preview"]
    runtime["runtime"]
    support["support"]
    telegram["telegram"]
    tracker["tracker"]
    verification["verification"]
    web["web"]
    audit --> config
    audit --> core
    audit --> git
    config --> core
    dashboard --> audit
    dashboard --> config
    dashboard --> core
    dashboard --> git
    dashboard --> github
    dashboard --> knowledge
    dashboard --> maintenance
    dashboard --> orchestrator
    dashboard --> pipeline
    dashboard --> preview
    dashboard --> runtime
    dashboard --> support
    dashboard --> telegram
    dashboard --> tracker
    docs --> core
    git --> support
    github --> config
    github --> core
    github --> git
    github --> support
    knowledge --> config
    knowledge --> core
    knowledge --> git
    merge --> config
    merge --> github
    orchestrator --> config
    orchestrator --> core
    orchestrator --> github
    orchestrator --> merge
    orchestrator --> preview
    orchestrator --> support
    orchestrator --> telegram
    orchestrator --> tracker
    pipeline --> config
    pipeline --> core
    pipeline --> github
    pipeline --> merge
    pipeline --> preview
    pipeline --> runtime
    pipeline --> support
    pipeline --> tracker
    preview --> config
    preview --> git
    preview --> support
    runtime --> config
    runtime --> core
    runtime --> docs
    runtime --> git
    runtime --> github
    runtime --> knowledge
    runtime --> maintenance
    runtime --> support
    runtime --> tracker
    runtime --> verification
    telegram --> config
    telegram --> core
    telegram --> knowledge
    telegram --> runtime
    telegram --> support
    telegram --> tracker
    tracker --> config
    tracker --> core
    verification --> git
    web --> config
    web --> core
    web --> dashboard
    web --> knowledge
    web --> runtime
    web --> tracker
```

`web` en `bridge` zijn onafhankelijke transportadapters. `bridge` bereikt Telegramstatus via
de dashboard-applicationport; geen transportmodule importeert een andere transportmodule.
