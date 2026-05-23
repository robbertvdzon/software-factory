# KAN-002 - Jira Integratie

Story:
Als orchestrator wil ik Jira-tickets met status `AI` kunnen lezen, bijwerken en
van comments voorzien, zodat Jira de zichtbare workflow-bron voor de factory is.

Subtaken:
[ ]: Jira client maken voor tickets met status `AI`
[ ]: Jira custom fields lezen en schrijven
[ ]: Jira comments posten met agent-prefixes
[ ]: Jira comment-reacties ondersteunen voor processed comments
[ ]: Fallback `processed_comments` tabel gebruiken als reacties niet werken
[ ]: Command-comments herkennen en idempotent markeren

Stappen:
[ ]: model Jira issue, comment and custom field data
[ ]: implement Jira search call for project/status filtering
[ ]: map configured custom field names to Jira field ids
[ ]: implement read/write for `AI Phase`, `Paused`, `Error`, budget and token fields
[ ]: implement comment posting with `[ROLE]` prefixes
[ ]: implement processed-comment detection via reactions
[ ]: implement DB fallback for processed comments
[ ]: implement command-comment parser and processed markers
[ ]: add fake Jira tests for happy path and missing field failures

Done / rationale:
- Nog niet geimplementeerd.
