package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerProject
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.OrchestratorPollResult
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.web.repositories.FactoryDashboardRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime

class FactoryDashboardServiceTest {

    @Test
    fun `latestAgentQuestions takes the most recent run with a non-blank summary, not just the last run`() {
        // De vraag-tester (eerder) stelde de vraag; een latere lege/half-afgeronde run (recovery-churn)
        // mag die vraag niet verbergen.
        val question = run(subtaskKey = "SF-8", startedAt = at(1), summaryText = "Mag ik de acceptatiecriteria bevestigen?")
        val laterBlank = run(subtaskKey = "SF-8", startedAt = at(2), summaryText = null)

        val result = FactoryDashboardService.latestAgentQuestions(listOf(question, laterBlank), fallbackKey = "SF-1")

        assertEquals(mapOf("SF-8" to "Mag ik de acceptatiecriteria bevestigen?"), result)
    }

    @Test
    fun `latestAgentQuestions prefers the newest non-blank summary and groups story-level runs under the story key`() {
        val older = run(subtaskKey = null, startedAt = at(1), summaryText = "oude vraag")
        val newer = run(subtaskKey = null, startedAt = at(3), summaryText = "nieuwe vraag")

        val result = FactoryDashboardService.latestAgentQuestions(listOf(older, newer), fallbackKey = "SF-1")

        assertEquals(mapOf("SF-1" to "nieuwe vraag"), result)
    }

    @Test
    fun `latestAgentQuestions drops a key when no run has a non-blank summary`() {
        val blank = run(subtaskKey = "SF-9", startedAt = at(1), summaryText = "   ")
        val nullSummary = run(subtaskKey = "SF-9", startedAt = at(2), summaryText = null)

        val result = FactoryDashboardService.latestAgentQuestions(listOf(blank, nullSummary), fallbackKey = "SF-1")

        assertEquals(emptyMap<String, String>(), result)
    }

    @Test
    fun `questionTextFrom extracts only the questions from the control JSON, not the whole report`() {
        val summary = """
            Ik heb het worklog gelezen. Hier is de eindsamenvatting voor de PO.

            ## SF-1 — Eindsamenvatting
            Een heleboel rapport-tekst die de gebruiker NIET als "de vraag" wil zien.

            {"agent_tips_update":[]}
            {"phase":"summary-with-questions","questions":["Heeft de CI een Docker-daemon beschikbaar?","Hoe moet de PR-strategie eruitzien?"]}
        """.trimIndent()

        assertEquals(
            "1. Heeft de CI een Docker-daemon beschikbaar?\n\n2. Hoe moet de PR-strategie eruitzien?",
            FactoryDashboardService.questionTextFrom(summary),
        )
    }

    @Test
    fun `questionTextFrom returns a single question without numbering`() {
        val summary = """
            Korte toelichting.
            {"phase":"refined-with-questions","questions":["Kun je de acceptatiecriteria bevestigen?"]}
        """.trimIndent()

        assertEquals("Kun je de acceptatiecriteria bevestigen?", FactoryDashboardService.questionTextFrom(summary))
    }

    @Test
    fun `questionTextFrom falls back to the full summary when there is no questions JSON`() {
        val summary = "Gewoon een samenvatting zonder vragen-control-JSON."

        assertEquals(summary, FactoryDashboardService.questionTextFrom(summary))
    }

    @Test
    fun `questionTextFrom extracts questions from pretty-printed JSON inside a fenced code block`() {
        // Zoals de reviewer het soms levert: een preamble plus een multi-line JSON in een ```json-blok.
        // De oude regel-voor-regel parser miste dit en toonde het volledige rapport.
        val summary = """
            Perfecte. Nu geef ik mijn review-output volgens het JSON-contract.

            ```json
            {
              "phase": "reviewed-with-questions",
              "questions": [
                "Klopt het dat endpoint X publiek mag zijn?"
              ]
            }
            ```
        """.trimIndent()

        assertEquals(
            "Klopt het dat endpoint X publiek mag zijn?",
            FactoryDashboardService.questionTextFrom(summary),
        )
    }

    // ── awaitsHuman + autoApprove ────────────────────────────────────────────────

    @Test
    fun `awaitsHuman returns false for REFINED when autoApprove is true`() {
        val service = createService(FakeYouTrackApi())
        val issue = storyIssue(storyPhase = "refined", autoApprove = true)
        assert(!service.awaitsHuman(issue)) { "REFINED met autoApprove=true mag niet wachten op mens" }
    }

    @Test
    fun `awaitsHuman returns true for REFINED when autoApprove is false`() {
        val service = createService(FakeYouTrackApi())
        val issue = storyIssue(storyPhase = "refined", autoApprove = false)
        assert(service.awaitsHuman(issue)) { "REFINED zonder autoApprove moet wachten op mens" }
    }

    @Test
    fun `awaitsHuman returns true for REFINED_WITH_QUESTIONS regardless of autoApprove`() {
        val service = createService(FakeYouTrackApi())
        assert(service.awaitsHuman(storyIssue(storyPhase = "refined-with-questions", autoApprove = true)))
        assert(service.awaitsHuman(storyIssue(storyPhase = "refined-with-questions", autoApprove = false)))
    }

    @Test
    fun `awaitsHuman returns false for REVIEWED subtask when autoApprove is true`() {
        val service = createService(FakeYouTrackApi())
        val issue = subtaskIssue(subtaskPhase = "reviewed", autoApprove = true)
        assert(!service.awaitsHuman(issue)) { "REVIEWED met autoApprove=true mag niet wachten" }
    }

    @Test
    fun `awaitsHuman returns true for REVIEWED subtask when autoApprove is false`() {
        val service = createService(FakeYouTrackApi())
        val issue = subtaskIssue(subtaskPhase = "reviewed", autoApprove = false)
        assert(service.awaitsHuman(issue)) { "REVIEWED zonder autoApprove moet wachten" }
    }

    @Test
    fun `awaitsHuman returns false for DEVELOPED development subtask when autoApprove is true`() {
        val service = createService(FakeYouTrackApi())
        val issue = subtaskIssue(subtaskPhase = "developed", subtaskType = "development", autoApprove = true)
        assert(!service.awaitsHuman(issue)) { "DEVELOPED dev subtask met autoApprove=true mag niet wachten" }
    }

    @Test
    fun `awaitsHuman returns true for DEVELOPED development subtask when autoApprove is false`() {
        val service = createService(FakeYouTrackApi())
        val issue = subtaskIssue(subtaskPhase = "developed", subtaskType = "development", autoApprove = false)
        assert(service.awaitsHuman(issue)) { "DEVELOPED dev subtask zonder autoApprove moet wachten" }
    }

    @Test
    fun `awaitsHuman returns true for REVIEWED_WITH_QUESTIONS regardless of autoApprove`() {
        val service = createService(FakeYouTrackApi())
        assert(service.awaitsHuman(subtaskIssue(subtaskPhase = "reviewed-with-questions", autoApprove = true)))
        assert(service.awaitsHuman(subtaskIssue(subtaskPhase = "reviewed-with-questions", autoApprove = false)))
    }

    @Test
    fun `setAutoApproveFlag enables auto-approve by updating the field to 'on'`() {
        val issueTracker = FakeYouTrackApi()
        val service = createService(issueTracker)

        service.setAutoApproveFlag("SF-129", enabled = true)

        assertEquals("SF-129", issueTracker.lastUpdatedKey)
        assertEquals("on", issueTracker.lastFieldUpdate?.values?.get(TrackerField.AUTO_APPROVE))
    }

    @Test
    fun `setAutoApproveFlag disables auto-approve by updating the field to 'off'`() {
        val issueTracker = FakeYouTrackApi()
        val service = createService(issueTracker)

        service.setAutoApproveFlag("SF-129", enabled = false)

        assertEquals("SF-129", issueTracker.lastUpdatedKey)
        assertEquals("off", issueTracker.lastFieldUpdate?.values?.get(TrackerField.AUTO_APPROVE))
    }

    @Test
    fun `createStory with autoApprove=true calls setAutoApproveFlag after creating the story`() {
        val issueTracker = FakeYouTrackApi()
        val service = createService(issueTracker)

        service.createStory(
            projectKey = "SF",
            title = "Test story",
            description = null,
            repo = null,
            aiSupplier = null,
            aiModel = null,
            start = false,
            autoApprove = true,
        )

        // Verify that the story was created
        assertEquals("SF", issueTracker.lastCreatedProjectKey)
        assertEquals("Test story", issueTracker.lastCreatedTitle)
        // Verify that auto-approve was set to "on" after creation
        assertEquals("SF-1", issueTracker.lastUpdatedKey)
        assertEquals("on", issueTracker.lastFieldUpdate?.values?.get(TrackerField.AUTO_APPROVE))
    }

    @Test
    fun `createStory without projectKey falls back to the single configured project`() {
        // SF-818 — het "Nieuwe story"-dialoog stuurt geen projectKey meer mee; de service kiest het
        // enige geconfigureerde project zodat de key-generatie (SF-###) blijft werken.
        val issueTracker = FakeYouTrackApi().apply {
            configuredProjects = listOf(TrackerProject(id = "SF", key = "SF", name = "SF"))
        }
        val service = createService(issueTracker)

        service.createStory(
            projectKey = null,
            title = "Zonder project",
            description = null,
            repo = null,
            aiSupplier = null,
            aiModel = null,
            start = false,
        )

        assertEquals("SF", issueTracker.lastCreatedProjectKey)
        assertEquals("Zonder project", issueTracker.lastCreatedTitle)
    }

    @Test
    fun `createStory with autoApprove=false does not call setAutoApproveFlag`() {
        val issueTracker = FakeYouTrackApi()
        val service = createService(issueTracker)

        service.createStory(
            projectKey = "SF",
            title = "Test story",
            description = null,
            repo = null,
            aiSupplier = null,
            aiModel = null,
            start = false,
            autoApprove = false,
        )

        // Verify that the story was created
        assertEquals("SF", issueTracker.lastCreatedProjectKey)
        assertEquals("Test story", issueTracker.lastCreatedTitle)
        // Verify that auto-approve was NOT set (lastUpdatedKey should still be null)
        assertEquals(null, issueTracker.lastUpdatedKey)
    }

    // ── storyStatusBucket ────────────────────────────────────────────────────────

    @Test
    fun `storyStatusBucket maps done variants to done`() {
        val service = createService(FakeYouTrackApi())
        assertEquals("done", service.storyStatusBucket("Done"))
        assertEquals("done", service.storyStatusBucket("fixed"))
        assertEquals("done", service.storyStatusBucket("VERIFIED"))
        assertEquals("done", service.storyStatusBucket("closed"))
        assertEquals("done", service.storyStatusBucket("resolved"))
    }

    @Test
    fun `storyStatusBucket maps in-progress variants to in-progress`() {
        val service = createService(FakeYouTrackApi())
        assertEquals("in-progress", service.storyStatusBucket("In Progress"))
        assertEquals("in-progress", service.storyStatusBucket("to verify"))
        assertEquals("in-progress", service.storyStatusBucket("developing"))
    }

    @Test
    fun `storyStatusBucket maps unknown and null to todo`() {
        val service = createService(FakeYouTrackApi())
        assertEquals("todo", service.storyStatusBucket(null))
        assertEquals("todo", service.storyStatusBucket(""))
        assertEquals("todo", service.storyStatusBucket("open"))
        assertEquals("todo", service.storyStatusBucket("backlog"))
        assertEquals("todo", service.storyStatusBucket("unknown-status"))
    }

    // ── repoMatchesProject ───────────────────────────────────────────────────────

    @Test
    fun `repoMatchesProject returns true on exact match`() {
        val service = createService(FakeYouTrackApi())
        assert(service.repoMatchesProject("https://github.com/foo/bar.git", "https://github.com/foo/bar.git"))
    }

    @Test
    fun `repoMatchesProject returns true when db contains resolved url`() {
        val service = createService(FakeYouTrackApi())
        assert(service.repoMatchesProject("https://github.com/foo/bar.git", "github.com/foo/bar"))
    }

    @Test
    fun `repoMatchesProject returns false on no match`() {
        val service = createService(FakeYouTrackApi())
        assert(!service.repoMatchesProject("https://github.com/foo/other.git", "https://github.com/foo/bar.git"))
    }

    @Test
    fun `repoMatchesProject returns false when either is blank`() {
        val service = createService(FakeYouTrackApi())
        assert(!service.repoMatchesProject("", "https://github.com/foo/bar.git"))
        assert(!service.repoMatchesProject("https://github.com/foo/bar.git", ""))
    }

    // ── parsePrdVersionJson ──────────────────────────────────────────────────────

    @Test
    fun `parsePrdVersionJson parses valid JSON`() {
        val service = createService(FakeYouTrackApi())
        val json = """{"commitHash":"abc1234def","commitDate":"2026-06-01","branch":"main"}"""
        val result = service.parsePrdVersionJson(json)
        assertEquals("abc1234", result?.commitShort)
        assertEquals("2026-06-01", result?.commitDate)
        assertEquals("main", result?.branch)
    }

    @Test
    fun `parsePrdVersionJson returns null when commitHash missing`() {
        val service = createService(FakeYouTrackApi())
        val json = """{"commitDate":"2026-06-01","branch":"main"}"""
        assertEquals(null, service.parsePrdVersionJson(json))
    }

    @Test
    fun `parsePrdVersionJson handles missing optional fields with empty strings`() {
        val service = createService(FakeYouTrackApi())
        val json = """{"commitHash":"deadbeef"}"""
        val result = service.parsePrdVersionJson(json)
        assertEquals("deadbee", result?.commitShort)
        assertEquals("", result?.commitDate)
        assertEquals("", result?.branch)
    }

    private fun createService(issueTracker: YouTrackApi): FactoryDashboardService {
        val secrets = FakeFactorySecrets()
        // Must use actual Repository class since it's final, but wrapped with StubJdbcTemplate
        // that doesn't execute DB queries
        val repository = FactoryDashboardRepository(StubJdbcTemplate(), secrets)
        val operations = FactoryOperationsService(
            issueTrackerClient = issueTracker,
            orchestratorApi = FakeOrchestratorApi(),
            repository = repository,
            previewApi = FakePreviewApi(),
        )
        return FactoryDashboardService(
            issueTrackerClient = issueTracker,
            orchestratorApi = FakeOrchestratorApi(),
            repository = repository,
            factorySecrets = secrets,
            operations = operations,
            projectRepoResolver = ProjectRepoResolver(emptyMap()),
            versionService = FactoryVersionService(),
            nightlySettingsRepository = nl.vdzon.softwarefactory.nightly.NightlySettingsRepository(StubJdbcTemplate(), secrets),
            nightlyRunRepository = nl.vdzon.softwarefactory.nightly.NightlyRunRepository(StubJdbcTemplate(), secrets),
            nightlyRunJobRepository = nl.vdzon.softwarefactory.nightly.NightlyRunJobRepository(StubJdbcTemplate(), secrets),
            // Geen defaults meer in productie-code: de echte beans expliciet meegeven.
            nightlyJobsReader = nl.vdzon.softwarefactory.nightly.NightlyJobsReader(),
            deployClient = nl.vdzon.softwarefactory.web.services.ProjectDeployClient(),
            workspaceLauncher = nl.vdzon.softwarefactory.web.services.WorkspaceDesktopLauncher(),
            gitHubReleaseClient = nl.vdzon.softwarefactory.web.services.GitHubReleaseClient(secrets),
            subtaskPlanMaterializer = nl.vdzon.softwarefactory.runtime.services.SubtaskPlanMaterializer(
                issueTracker,
                ProjectRepoResolver(emptyMap()),
            ),
        )
    }

    private fun at(seconds: Long): OffsetDateTime =
        OffsetDateTime.parse("2026-06-11T10:00:00Z").plusSeconds(seconds)

    private fun run(subtaskKey: String?, startedAt: OffsetDateTime, summaryText: String?): UiAgentRun =
        UiAgentRun(
            id = 1,
            storyRunId = 1,
            storyKey = "SF-1",
            role = "tester",
            containerName = "c",
            model = null,
            effort = null,
            level = null,
            startedAt = startedAt,
            endedAt = null,
            outcome = null,
            inputTokens = 0,
            outputTokens = 0,
            cacheReadInputTokens = 0,
            cacheCreationInputTokens = 0,
            numTurns = 0,
            durationMs = 0,
            costUsdEst = 0.0,
            summaryText = summaryText,
            workspacePath = null,
            subtaskKey = subtaskKey,
        )

    private fun storyIssue(storyPhase: String?, autoApprove: Boolean): TrackerIssue =
        TrackerIssue(
            key = "SF-1",
            summary = "Test story",
            description = null,
            status = "",
            comments = emptyList(),
            fields = TrackerIssueFields(
                targetRepo = null,
                aiPhase = null,
                aiLevel = null,
                aiTokenBudget = null,
                aiTokensUsed = null,
                agentStartedAt = null,
                paused = false,
                error = null,
                storyPhase = storyPhase,
                autoApprove = autoApprove,
            ),
        )

    private fun subtaskIssue(subtaskPhase: String?, subtaskType: String = "review", autoApprove: Boolean): TrackerIssue =
        TrackerIssue(
            key = "SF-2",
            summary = "Test subtask",
            description = null,
            status = "",
            comments = emptyList(),
            fields = TrackerIssueFields(
                targetRepo = null,
                aiPhase = null,
                aiLevel = null,
                aiTokenBudget = null,
                aiTokensUsed = null,
                agentStartedAt = null,
                paused = false,
                error = null,
                type = "Task",
                subtaskPhase = subtaskPhase,
                subtaskType = subtaskType,
                autoApprove = autoApprove,
            ),
        )

    private class StubJdbcTemplate : JdbcTemplate()

    private fun FakeFactorySecrets(): FactorySecrets =
        FactorySecrets(
            youTrackBaseUrl = "http://fake",
            youTrackToken = "fake",
            youTrackProjects = emptyList(),
            githubToken = "fake",
            factoryDatabaseUrl = "jdbc:fake",
            factoryDatabaseSchema = "fake",
            kubeconfig = "fake",
            aiCredentialsDir = "fake",
            aiOauthToken = null,
            loadedFrom = "fake",
        )

    private class FakeOrchestratorApi : OrchestratorApi {
        override fun pollOnce(projectKey: String) = OrchestratorPollResult(emptyList())
        override fun processIssue(issue: TrackerIssue) = IssueProcessResult.Skipped(issue.key, "test")
        override fun queueCommand(storyKey: String, command: FactoryCommand, reason: String?) = Unit
        override fun purgeStory(storyKey: String) = Unit
    }

    private class FakePreviewApi : PreviewApi {
        override fun render(template: String?, prNumber: Int?) = null
        override fun cleanup(namespace: String) = false
    }

    private class FakeYouTrackApi : YouTrackApi {
        var lastUpdatedKey: String? = null
        var lastFieldUpdate: TrackerFieldUpdate? = null
        var lastCreatedProjectKey: String? = null
        var lastCreatedTitle: String? = null
        var configuredProjects: List<TrackerProject> = emptyList()
        private var createdStoryCounter = 0

        override fun ensureConfiguredProjects(): List<TrackerProject> = configuredProjects
        override fun findAiIssues(projectKey: String, maxResults: Int): List<TrackerIssue> = emptyList()
        override fun findWorkIssues(maxResults: Int): List<TrackerIssue> = emptyList()
        override fun getIssue(issueKey: String): TrackerIssue = throw UnsupportedOperationException()
        override fun parentStoryKey(subtaskKey: String): String = throw UnsupportedOperationException()
        override fun subtasksOf(parentKey: String): List<TrackerIssue> = emptyList()
        override fun createStory(projectKey: String, title: String, description: String?, repo: String?, aiSupplier: String?, aiModel: String?, start: Boolean, silent: Boolean): TrackerIssue {
            lastCreatedProjectKey = projectKey
            lastCreatedTitle = title
            createdStoryCounter++
            return TrackerIssue(
                key = "SF-$createdStoryCounter",
                summary = title,
                description = description,
                status = "",
                comments = emptyList(),
                fields = TrackerIssueFields(
                    targetRepo = repo,
                    aiPhase = "",
                    aiLevel = null,
                    aiTokenBudget = 0L,
                    aiTokensUsed = 0L,
                    error = null,
                    paused = false,
                    agentStartedAt = null,
                ),
            )
        }
        override fun createSubtask(parentKey: String, spec: nl.vdzon.softwarefactory.core.SubtaskSpec, supplier: String?): TrackerIssue = throw UnsupportedOperationException()
        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            lastUpdatedKey = issueKey
            lastFieldUpdate = update
        }
        override fun transitionIssue(issueKey: String, statusName: String) = Unit
        override fun postComment(issueKey: String, message: String): TrackerComment = throw UnsupportedOperationException()
        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment = throw UnsupportedOperationException()
    }
}
