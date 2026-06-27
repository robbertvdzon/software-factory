# KAN-012 - PR Comment Iteratie Context

Story:
Als gebruiker wil ik met `@factory` PR-comments extra feedback kunnen geven,
zodat de developer dezelfde PR gericht kan aanpassen en de comment-flow
idempotent blijft.

Subtaken:
[x]: PR-comments sinds laatste done-reactie bundelen
[x]: developer dispatch in `mode=comment`
[x]: PR-comment context in `task.md`
[x]: comment done/failed reactions zetten na developer completion
[x]: tests voor GitHub en orchestrator-flow

Stappen:
[x]: specs §11.3 nalopen
[x]: GitHub client uitbreiden met claimed/done/failed reacties
[x]: orchestrator PR-comment context laten bepalen bij developer dispatch
[x]: Docker dispatch `SF_AGENT_MODE=comment` laten meegeven
[x]: completion-flow claimed comments op rocket/confused zetten
[x]: e2e en unit tests uitbreiden
[x]: volledige test-suite draaien

Done / rationale:
- Start KAN-012: audit vond dat PR-comments wel met `eyes` geclaimd werden, maar dat `mode=comment`, context-bundeling en `rocket`/`confused` status-reacties na developer completion nog ontbraken.
- `PullRequestClient` uitgebreid met claimed-comment lookup en `rocket`/`confused` reacties. GitHub CLI implementatie filtert claimed comments als `eyes` zonder `rocket` of `confused`.
- De orchestrator bouwt voor developer-dispatches vanuit PR-comment feedback een `PR Comment Task Bundle` en zet `agentMode=comment`.
- Docker dispatch geeft `SF_AGENT_MODE=comment` mee en `AgentWorkspaceFactory` schrijft de PR-comment bundel in `task.md`.
- `AgentRunCompletionService` markeert claimed PR-comments na developer completion met `rocket` bij succes of `confused` bij fout.
- Unit/e2e tests toegevoegd voor GitHub filtering, comment-mode dispatch, task context, completion reactions en een volledige PR-comment loop. Verificatie: `mvn test` groen met 75 tests.
