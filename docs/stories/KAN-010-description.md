# KAN-010 - Testbaarheid En End-To-End Scenarios

Story:
Als developer wil ik fake adapters en end-to-end dummy scenario's hebben, zodat
de factory betrouwbaar verder ontwikkeld kan worden zonder echte Jira, GitHub,
Docker of AI side effects.

Subtaken:
[x]: Fake Jira adapter voor integratietests
[x]: Fake GitHub adapter of test repo harness
[x]: Fake Docker runner voor state-machine tests
[x]: End-to-end happy path met dummy agents
[x]: End-to-end loopback path
[x]: End-to-end budget pause/resume path

Stappen:
[x]: define ports/interfaces around Jira, GitHub and Docker
[x]: implement fake Jira with issues, fields and comments
[x]: implement fake GitHub with PR lifecycle behavior
[x]: implement fake Docker runner with container states
[x]: run dummy happy path from empty phase to tested-successfully
[x]: run reviewer/tester loopback scenarios
[x]: run budget pause and resume scenario
[x]: keep tests deterministic with forced dummy outcomes

Done / rationale:
- Start KAN-010: specs voor de volledige phase-machine, dummy-outcomes, PR-flow, cost-monitor en comment-triggers gelezen. De productiepoorten rond Jira, GitHub en Docker bestaan al; de implementatie voegt daarom test-fakes toe die deze poorten deterministisch simuleren en de echte orchestrator-services gebruiken.
- `FactoryE2eHarness` toegevoegd met fake Jira, fake GitHub, fake Docker-runtime, in-memory story/agent-run repositories, fake event store, fake preview-cleaner en fake credits coordinator.
- De harness gebruikt de echte `OrchestratorService`, `ManualCommandService`, `CostMonitorService` en `AgentRunCompletionService`. Daardoor test hij de state-machine en completion/budget-flow zonder echte Jira, GitHub, Docker of AI side effects.
- Happy-path scenario toegevoegd: lege `AI Phase` → refiner → developer → reviewer → tester → `tested-successfully`, inclusief PR metadata en preview context voor de tester.
- Loopback-scenario toegevoegd: reviewer-feedback en tester-bug sturen allebei deterministisch terug naar developer en hergebruiken dezelfde PR-flow.
- Budget-scenario toegevoegd: tokengebruik boven budget zet `Paused=true`, plaatst cost-monitor comments, en een user-comment `BUDGET=10000` hervat de story en dispatcht developer.
- Verificatie: `mvn test` groen met 67 tests.
