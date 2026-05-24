# KAN-014 - Jira Context En Comment Markers

Story:
Agents moeten volgens `specs/specs.md` de Jira-story inclusief description en relevante comments in hun taakcontext krijgen. Refiner- en developer-runs moeten comments die zij succesvol verwerkt hebben als processed markeren, zodat latere runs alleen nieuwe feedback oppakken.

Subtaken:
[x]: Jira description uitlezen en modelleren
[x]: Jira story/comment-context in `task.md` opnemen
[x]: Relevante comments per rol selecteren met processed-marker filtering
[x]: Succesvolle refiner/developer completion markeert verwerkte comments
[x]: tests voor context en markers toevoegen

Stappenplan:
[x]: Lees specs rond §3.4, §7 en §13
[x]: Breid Jira-model en Atlassian mapping uit
[x]: Voeg comment-selectie en task-context toe
[x]: Voeg completion-marker flow toe
[x]: Draai tests en startcheck

Done / rationale:
- Story aangemaakt omdat de specs-audit liet zien dat agents nog onvoldoende Jira-context kregen en Jira user/review/test-comments niet automatisch als verwerkt werden gemarkeerd na succesvolle runs.
- Jira `description` wordt nu opgehaald via de Atlassian client en toegevoegd aan het interne `JiraIssue` model.
- `AgentCommentContext` toegevoegd om per rol relevante comments te selecteren: refiner krijgt nieuwe user-comments; developer krijgt refiner-context plus nieuwe reviewer/tester-feedback; reviewer/tester krijgen voorafgaande agent-output.
- `OrchestratorService` schrijft Jira summary, description, status, AI-level en relevante comments in de `AgentDispatchRequest`.
- `AgentWorkspaceFactory` neemt die Jira-context op in `/work/task.md`, naast PR-comment bundles en later agent-tips/docs.
- `AgentRunCompletionService` markeert na succesvolle refiner/developer-runs de verwerkte Jira-comments via de bestaande Jira-marker met database-fallback.
- `mvn test` draait groen met 79 tests.
- Spring Boot startte succesvol op poort 8080 tegen schema `software_factory`; daarna is het proces handmatig gestopt, waardoor Maven de run met exitcode 143 rapporteerde.
