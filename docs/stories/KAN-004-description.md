# KAN-004 - Agent Runtime En Dummy Agents

Story:
Als factory wil ik agent-runs lokaal in Docker kunnen starten met een gedeelde
agent CLI en dummy AI-client, zodat de end-to-end flow werkt zonder echte AI
credits te gebruiken.

Subtaken:
[x]: Docker runner bouwen die agent-containers start
[x]: Workspace tempdir + `/work/task.md` payload maken
[x]: `agent-base` Docker image bouwen
[x]: `agent-tester` Docker image bouwen
[x]: Kotlin agent CLI entrypoint maken
[x]: Shared agent context bouwen
[x]: `AiClient` interface + `DummyAiClient`
[x]: Dummy gedrag voor refiner/developer/reviewer/tester
[x]: `/agent-run/complete` endpoint + usage opslag
[x]: Agent event logging + secret redaction

Stappen:
[x]: define runner interface and Docker labels
[x]: pass only `SF_` prefixed factory env-vars into containers
[x]: create per-run workspace and task payload
[x]: build agent CLI entrypoint with role dispatch
[x]: implement dummy outcomes and forced outcome env-var
[x]: report completion and fake token usage to orchestrator
[x]: persist agent events with secret redaction
[x]: add fake runner and dummy agent tests

Done / rationale:
- `DockerAgentRuntime` start nu lokale Docker containers met labels
  `app=factory-agent`, `story-key` en `role`, een per-run workspace mount,
  een `factory.env` met alleen `SF_`-waarden, en optionele read-only mounts
  voor AI credentials en kubeconfig. Lokale mount-paden ondersteunen ook
  `~`-expansie, passend bij de voorbeelden in de specs.
- `agent-base:local` bouwt de Kotlin agent CLI in een multi-stage Dockerfile
  en bevat Java 21, Node 22, git, GitHub CLI, `jq` en ssh tooling.
- `agent-tester:local` erft de base entrypoint en voegt `psql`, Playwright en
  Chromium toe. De image zet `SF_PLAYWRIGHT_CHROMIUM_EXECUTABLE` naar een
  stabiele full-Chromium symlink, omdat de Playwright headless-shell op deze
  arm64/Noble combinatie crasht terwijl full Chromium wel betrouwbaar start.
- De agent CLI dispatcht op `SF_AGENT_TYPE`, gebruikt voorlopig de
  `DummyAiClient`, schrijft de juiste Jira phase/comment of `Error`, en meldt
  completion met fake usage via `POST /agent-run/complete`. Completion-posts
  retryen kort, zodat een snelle container niet meteen faalt als de
  orchestrator-run row nog net niet zichtbaar is.
- Completion wordt opgeslagen in `agent_runs`, usage wordt bijgeteld op de
  story-run, en events worden via `SecretRedactor` geredact voordat ze in
  `agent_events` terechtkomen. Een agent-run kan maar een keer completed
  worden, zodat duplicate completion geen tokenusage dubbeltelt.
- `specs/specs.md` is bijgewerkt naar de gebruikte `eclipse-temurin:21-jdk-noble`
  base en de Chromium executable afspraak.
- Verificatie:
  `mvn test` draaide 26 tests groen.
  `./factory build-images` bouwde `agent-base:local` en `agent-tester:local`.
  Containerchecks bevestigden Java 21, Node 22, git, gh, jq, psql, Playwright
  en een werkende Chromium launch via `SF_PLAYWRIGHT_CHROMIUM_EXECUTABLE`.
  Een live smoke met KAN-69 op de finale images dispatchte een refiner-container,
  zette Jira op `refined-finished`, schreef een afgeronde `ok` run met tokengebruik in
  `software_factory.agent_runs`, en liet geen agent-containers achter.
