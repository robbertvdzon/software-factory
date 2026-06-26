# KAN-002 - Jira Integratie

Story:
Als orchestrator wil ik Jira-tickets met status `AI` kunnen lezen, bijwerken en
van comments voorzien, zodat Jira de zichtbare workflow-bron voor de factory is.

Subtaken:
[x]: Jira client maken voor tickets met status `AI`
[x]: Jira custom fields lezen en schrijven
[x]: Jira comments posten met agent-prefixes
[x]: Jira comment-reacties ondersteunen voor processed comments
[x]: Fallback `processed_comments` tabel gebruiken als reacties niet werken
[x]: Command-comments herkennen en idempotent markeren

Stappen:
[x]: model Jira issue, comment and custom field data
[x]: implement Jira search call for project/status filtering
[x]: map configured custom field names to Jira field ids
[x]: implement read/write for `AI Phase`, `Paused`, `Error`, budget and token fields
[x]: implement comment posting with `[ROLE]` prefixes
[x]: implement processed-comment detection via reactions
[x]: implement DB fallback for processed comments
[x]: implement command-comment parser and processed markers
[x]: add fake Jira tests for happy path and missing field failures

Done / rationale:
- De Jira-boundary staat nu achter `JiraClient`, met modellen voor issues,
  custom fields, comments, agent-rollen en field updates. De client gebruikt
  het actuele Jira Cloud endpoint `/rest/api/3/search/jql`; het oude
  `/rest/api/3/search` gaf bij verificatie een `410`.
- Custom fields worden bij startup/use dynamisch gemapt op naam. Als een
  verplicht veld ontbreekt faalt de client hard, zodat een verkeerde Jira-setup
  niet stil tot half-werkende orchestration leidt.
- Comments worden als Atlassian Document Format gepost en krijgen altijd de
  afgesproken `[ROLE]` prefix. Binnenkomende ADF-comments worden teruggebracht
  naar plain text voor command- en user-feedback parsing.
- Voor processed comments gebruikt de implementatie een Jira-side marker via
  comment properties. Die boundary is geisoleerd zodat een zichtbare emoji-
  reactie later vervangen kan worden als Jira daar een stabiele publieke API
  voor biedt. Als Jira de marker niet accepteert, valt de service terug op
  `software_factory.processed_comments`.
- De command parser herkent `@factory:command:*`, `LEVEL=N`, `BUDGET=N` en
  `CONTINUE`, en negeert agent-comments met `[REFINER]`, `[DEVELOPER]`,
  `[REVIEWER]`, `[TESTER]`, `[COST-MONITOR]` of `[ORCHESTRATOR]`.
- `mvn test` is groen met fake-Jira tests voor happy path, missende custom
  fields, command parsing en DB fallback.
- Echte Jira-verificatie op `KAN-69` is uitgevoerd: AI-issues lezen, `Target
  Repo` uitlezen, veilige field update doen, een `[ORCHESTRATOR]` comment
  posten en de processed marker op die comment teruglezen.
