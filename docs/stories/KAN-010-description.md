# KAN-010 - Testbaarheid En End-To-End Scenarios

Story:
Als developer wil ik fake adapters en end-to-end dummy scenario's hebben, zodat
de factory betrouwbaar verder ontwikkeld kan worden zonder echte Jira, GitHub,
Docker of AI side effects.

Subtaken:
[ ]: Fake Jira adapter voor integratietests
[ ]: Fake GitHub adapter of test repo harness
[ ]: Fake Docker runner voor state-machine tests
[ ]: End-to-end happy path met dummy agents
[ ]: End-to-end loopback path
[ ]: End-to-end budget pause/resume path

Stappen:
[ ]: define ports/interfaces around Jira, GitHub and Docker
[ ]: implement fake Jira with issues, fields and comments
[ ]: implement fake GitHub with PR lifecycle behavior
[ ]: implement fake Docker runner with container states
[ ]: run dummy happy path from empty phase to tested-successfully
[ ]: run reviewer/tester loopback scenarios
[ ]: run budget pause and resume scenario
[ ]: keep tests deterministic with forced dummy outcomes

Done / rationale:
- Nog niet geimplementeerd.
