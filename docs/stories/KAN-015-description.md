# KAN-015 - Workspace Cleanup Na Agent Run

Story:
De Docker-runner maakt per agent-run een host-workspace aan onder `~/.cache/software-factory/workspaces`. Volgens `specs/specs.md` moet die workspace na container-einde worden opgeruimd, met een configuratie-optie om workspaces bij falende runs te bewaren voor debug.

Subtaken:
[x]: workspace-pad registreren bij `agent_runs`
[x]: cleanup-instellingen uit `SF_*` env-vars lezen
[x]: filesystem cleanup veilig beperken tot de software-factory workspace-root
[x]: completion-flow ruimt workspaces op basis van outcome op
[x]: tests en startcheck bijwerken

Stappenplan:
[x]: Lees specs en huidige workspace/runtime flow
[x]: Voeg DB-migratie en repositoryvelden toe
[x]: Voeg workspace cleaner en settings toe
[x]: Sluit cleanup aan op agent completion
[x]: Draai tests en Spring Boot startcheck

Done / rationale:
- Story aangemaakt omdat de specs-audit liet zien dat workspaces wel werden aangemaakt, maar na agent-runs niet werden opgeruimd.
- `V4__agent_run_workspace_path.sql` toegevoegd zodat de workspace bij de agent-run geregistreerd wordt.
- `AgentDispatchResult`, `AgentRunRepository` en `CompletedAgentRun` uitgebreid met `workspacePath`, zodat completion weet welke host-directory bij de run hoort.
- `AgentWorkspaceCleanupSettings` toegevoegd voor `SF_AGENT_WORKSPACE_CLEANUP_ENABLED` en `SF_AGENT_WORKSPACE_PRESERVE_ON_FAILURE`.
- `FileSystemAgentWorkspaceCleaner` verwijdert alleen paden onder de software-factory workspace-root om onbedoelde deletes te voorkomen.
- `AgentRunCompletionService` ruimt de workspace na completion op en geeft mee of de run gefaald is.
- `mvn test` draait groen met 81 tests.
- Spring Boot startte succesvol op poort 8080 en Flyway migreerde schema `software_factory` naar versie 4; daarna is het proces handmatig gestopt, waardoor Maven de run met exitcode 143 rapporteerde.
