# KAN-004 - Agent Runtime En Dummy Agents

Story:
Als factory wil ik agent-runs lokaal in Docker kunnen starten met een gedeelde
agent CLI en dummy AI-client, zodat de end-to-end flow werkt zonder echte AI
credits te gebruiken.

Subtaken:
[ ]: Docker runner bouwen die agent-containers start
[ ]: Workspace tempdir + `/work/task.md` payload maken
[ ]: `agent-base` Docker image bouwen
[ ]: `agent-tester` Docker image bouwen
[ ]: Kotlin agent CLI entrypoint maken
[ ]: Shared agent context bouwen
[ ]: `AiClient` interface + `DummyAiClient`
[ ]: Dummy gedrag voor refiner/developer/reviewer/tester
[ ]: `/agent-run/complete` endpoint + usage opslag
[ ]: Agent event logging + secret redaction

Stappen:
[ ]: define runner interface and Docker labels
[ ]: pass only `SF_` prefixed factory env-vars into containers
[ ]: create per-run workspace and task payload
[ ]: build agent CLI entrypoint with role dispatch
[ ]: implement dummy outcomes and forced outcome env-var
[ ]: report completion and fake token usage to orchestrator
[ ]: persist agent events with secret redaction
[ ]: add fake runner and dummy agent tests

Done / rationale:
- Nog niet geimplementeerd.
