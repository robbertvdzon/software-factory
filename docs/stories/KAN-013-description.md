# KAN-013 - Runtime Plumbing En Docker Logs

Story:
De lokale Docker-runner moet de runtime-afspraken uit `specs/specs.md` volgen: AI-level/model/effort worden expliciet aan agent-containers doorgegeven, Linux kan `host.docker.internal` bereiken, resource-limieten zijn configureerbaar, en Docker stdout/stderr kan als agent-events worden vastgelegd.

Subtaken:
[x]: AI-route voor level/model/effort bepalen en opslaan bij agent-runs
[x]: `SF_AI_LEVEL`, `SF_AI_MODEL` en `SF_AI_EFFORT` doorgeven aan containers
[x]: Linux host-gateway en optionele Docker resource-limieten configureren
[x]: Docker stdout/stderr als `agent_events` loggen
[x]: unit- en integratietests bijwerken

Stappenplan:
[x]: Lees specs en huidige Docker-runtime
[x]: Voeg runtime-settings en AI-route toe
[x]: Breid Docker-command en agent-run registratie uit
[x]: Voeg log-follower toe
[x]: Draai test-suite en Spring Boot startcheck

Done / rationale:
- Story aangemaakt omdat de specs-audit liet zien dat de runner nog niet alle container-env, netwerk-, resource- en loggingafspraken implementeerde.
- `AiRouting` toegevoegd zodat Jira `AI Level` nu naar expliciete `SF_AI_LEVEL`, `SF_AI_MODEL` en `SF_AI_EFFORT` container-env wordt vertaald. De dummy gebruikt model `dummy-ai-client`, maar de plumbing blijft gelijk voor een echte CLI.
- `agent_runs` registratie uitgebreid met model/effort/level, omdat de tabel die velden al volgens de specs bevat en observability anders incompleet was.
- `DockerRuntimeSettings` toegevoegd voor `SF_DOCKER_ADD_HOST_GATEWAY`, `SF_AGENT_DOCKER_MEMORY`, `SF_AGENT_DOCKER_CPUS` en `SF_DOCKER_LOG_CAPTURE_ENABLED`.
- Docker-run command uitgebreid met host-gateway, optionele resource-limieten en de AI-env-vars.
- `DockerLogFollower` toegevoegd om `docker logs -f --timestamps` stdout/stderr asynchroon naar `software_factory.agent_events` te schrijven via de bestaande redactie-laag.
- `mvn test` draait groen met 77 tests.
- Spring Boot startte succesvol op poort 8080 tegen schema `software_factory`; daarna is het proces handmatig gestopt, waardoor Maven de run met exitcode 143 rapporteerde.
