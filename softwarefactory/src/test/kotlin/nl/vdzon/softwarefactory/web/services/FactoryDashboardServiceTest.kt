package nl.vdzon.softwarefactory.dashboard.services

import nl.vdzon.softwarefactory.dashboard.types.BuildSyncStatus
import nl.vdzon.softwarefactory.dashboard.types.DeployRolloutStage
import nl.vdzon.softwarefactory.dashboard.types.DeployTargetRuntimeStatus
import nl.vdzon.softwarefactory.dashboard.models.PrdVersionInfo
import nl.vdzon.softwarefactory.dashboard.models.UiAgentRun
import nl.vdzon.softwarefactory.dashboard.models.WorkflowRunInfo
import nl.vdzon.softwarefactory.tracker.TrackerApi
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.ApprovalMode
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.contracts.TrackerProject
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.DeploymentStatusProbe
import nl.vdzon.softwarefactory.core.contracts.FactoryCommand
import nl.vdzon.softwarefactory.core.contracts.OrchestratorPollResult
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.DeployTarget
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.pipeline.DeployRolloutStatusApi
import nl.vdzon.softwarefactory.pipeline.DeployTargetStatusApi
import nl.vdzon.softwarefactory.pipeline.models.DeployTargetLiveStatus
import nl.vdzon.softwarefactory.pipeline.models.MatchedDeployTarget
import nl.vdzon.softwarefactory.preview.PreviewApi
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.dashboard.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.dashboard.models.CreateStoryCommand
import nl.vdzon.softwarefactory.runtime.repositories.JdbcAgentEventRepository
import nl.vdzon.softwarefactory.runtime.services.AgentLogService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class DashboardQueryServiceTest {

    @Test
    fun `latestAgentQuestions takes the most recent run with a non-blank summary, not just the last run`() {
        // De vraag-tester (eerder) stelde de vraag; een latere lege/half-afgeronde run (recovery-churn)
        // mag die vraag niet verbergen.
        val question = run(subtaskKey = "SF-8", startedAt = at(1), summaryText = "Mag ik de acceptatiecriteria bevestigen?")
        val laterBlank = run(subtaskKey = "SF-8", startedAt = at(2), summaryText = null)

        val result = DashboardQueryService.latestAgentQuestions(listOf(question, laterBlank), fallbackKey = "SF-1")

        assertEquals(mapOf("SF-8" to "Mag ik de acceptatiecriteria bevestigen?"), result)
    }

    @Test
    fun `latestAgentQuestions prefers the newest non-blank summary and groups story-level runs under the story key`() {
        val older = run(subtaskKey = null, startedAt = at(1), summaryText = "oude vraag")
        val newer = run(subtaskKey = null, startedAt = at(3), summaryText = "nieuwe vraag")

        val result = DashboardQueryService.latestAgentQuestions(listOf(older, newer), fallbackKey = "SF-1")

        assertEquals(mapOf("SF-1" to "nieuwe vraag"), result)
    }

    @Test
    fun `latestAgentQuestions drops a key when no run has a non-blank summary`() {
        val blank = run(subtaskKey = "SF-9", startedAt = at(1), summaryText = "   ")
        val nullSummary = run(subtaskKey = "SF-9", startedAt = at(2), summaryText = null)

        val result = DashboardQueryService.latestAgentQuestions(listOf(blank, nullSummary), fallbackKey = "SF-1")

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
            DashboardQueryService.questionTextFrom(summary),
        )
    }

    @Test
    fun `questionTextFrom returns a single question without numbering`() {
        val summary = """
            Korte toelichting.
            {"phase":"refined-with-questions","questions":["Kun je de acceptatiecriteria bevestigen?"]}
        """.trimIndent()

        assertEquals("Kun je de acceptatiecriteria bevestigen?", DashboardQueryService.questionTextFrom(summary))
    }

    @Test
    fun `questionTextFrom falls back to the full summary when there is no questions JSON`() {
        val summary = "Gewoon een samenvatting zonder vragen-control-JSON."

        assertEquals(summary, DashboardQueryService.questionTextFrom(summary))
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
            DashboardQueryService.questionTextFrom(summary),
        )
    }

    // ── awaitsHuman + autoApprove ────────────────────────────────────────────────

    @Test
    fun `awaitsHuman returns false for REFINED when autoApprove is true`() {
        val service = createService(FakeTrackerApi())
        val issue = storyIssue(storyPhase = "refined", autoApprove = true)
        assert(!service.awaitsHuman(issue)) { "REFINED met autoApprove=true mag niet wachten op mens" }
    }

    @Test
    fun `awaitsHuman returns true for REFINED when autoApprove is false`() {
        val service = createService(FakeTrackerApi())
        val issue = storyIssue(storyPhase = "refined", autoApprove = false)
        assert(service.awaitsHuman(issue)) { "REFINED zonder autoApprove moet wachten op mens" }
    }

    @Test
    fun `awaitsHuman returns true for REFINED_WITH_QUESTIONS regardless of autoApprove`() {
        val service = createService(FakeTrackerApi())
        assert(service.awaitsHuman(storyIssue(storyPhase = "refined-with-questions", autoApprove = true)))
        assert(service.awaitsHuman(storyIssue(storyPhase = "refined-with-questions", autoApprove = false)))
    }

    @Test
    fun `awaitsHuman returns false for REVIEWED subtask when parent autoApprove is true`() {
        val tracker = FakeTrackerApi().apply { parentIssue = parentStoryIssue(autoApprove = true) }
        val service = createService(tracker)
        val issue = subtaskIssue(subtaskPhase = "reviewed")
        assert(!service.awaitsHuman(issue)) { "REVIEWED met parent autoApprove=true mag niet wachten" }
    }

    @Test
    fun `awaitsHuman returns true for REVIEWED subtask when parent autoApprove is false`() {
        val tracker = FakeTrackerApi().apply { parentIssue = parentStoryIssue(autoApprove = false) }
        val service = createService(tracker)
        val issue = subtaskIssue(subtaskPhase = "reviewed")
        assert(service.awaitsHuman(issue)) { "REVIEWED zonder parent autoApprove moet wachten" }
    }

    @Test
    fun `awaitsHuman returns false for DEVELOPED development subtask when parent autoApprove is true`() {
        val tracker = FakeTrackerApi().apply { parentIssue = parentStoryIssue(autoApprove = true) }
        val service = createService(tracker)
        val issue = subtaskIssue(subtaskPhase = "developed", subtaskType = "development")
        assert(!service.awaitsHuman(issue)) { "DEVELOPED dev subtask met parent autoApprove=true mag niet wachten" }
    }

    @Test
    fun `awaitsHuman returns true for DEVELOPED development subtask when parent autoApprove is false`() {
        val tracker = FakeTrackerApi().apply { parentIssue = parentStoryIssue(autoApprove = false) }
        val service = createService(tracker)
        val issue = subtaskIssue(subtaskPhase = "developed", subtaskType = "development")
        assert(service.awaitsHuman(issue)) { "DEVELOPED dev subtask zonder parent autoApprove moet wachten" }
    }

    @Test
    fun `awaitsHuman returns true for REVIEWED_WITH_QUESTIONS regardless of autoApprove`() {
        val trackerTrue = FakeTrackerApi().apply { parentIssue = parentStoryIssue(autoApprove = true) }
        assert(createService(trackerTrue).awaitsHuman(subtaskIssue(subtaskPhase = "reviewed-with-questions")))
        val trackerFalse = FakeTrackerApi().apply { parentIssue = parentStoryIssue(autoApprove = false) }
        assert(createService(trackerFalse).awaitsHuman(subtaskIssue(subtaskPhase = "reviewed-with-questions")))
    }

    /**
     * SF-1261 review-fix: als de parent-lookup faalt (geen parent geconfigureerd op de fake),
     * moet de subtaak fail-safe op een mens wachten, ongeacht wat het eigen (nooit-gezette) veld
     * als class-default zou zijn.
     */
    @Test
    fun `awaitsHuman returns true for REVIEWED subtask when parent lookup fails`() {
        val service = createService(FakeTrackerApi())
        val issue = subtaskIssue(subtaskPhase = "reviewed")
        assert(service.awaitsHuman(issue)) { "REVIEWED met falende parent-lookup moet fail-safe wachten" }
    }

    private fun parentStoryIssue(autoApprove: Boolean): TrackerIssue =
        TrackerIssue(
            key = "SF-1",
            summary = "Parent story",
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
                type = "User Story",
                approvalMode = if (autoApprove) ApprovalMode.AUTOMATIC.trackerValue else ApprovalMode.EVERY_STEP.trackerValue,
            ),
        )

    @Test
    fun `setApprovalMode enables auto-approve by updating the field to 'automatisch'`() {
        val issueTracker = FakeTrackerApi()
        val service = createService(issueTracker)

        service.setApprovalMode("SF-129", ApprovalMode.AUTOMATIC.trackerValue)

        assertEquals("SF-129", issueTracker.lastUpdatedKey)
        assertEquals(ApprovalMode.AUTOMATIC.trackerValue, issueTracker.lastFieldUpdate?.values?.get(TrackerField.APPROVAL_MODE))
    }

    @Test
    fun `setApprovalMode disables auto-approve by updating the field to 'elke-stap'`() {
        val issueTracker = FakeTrackerApi()
        val service = createService(issueTracker)

        service.setApprovalMode("SF-129", ApprovalMode.EVERY_STEP.trackerValue)

        assertEquals("SF-129", issueTracker.lastUpdatedKey)
        assertEquals(ApprovalMode.EVERY_STEP.trackerValue, issueTracker.lastFieldUpdate?.values?.get(TrackerField.APPROVAL_MODE))
    }

    @Test
    fun `createStory with autoApprove=false calls setApprovalMode after creating the story`() {
        val issueTracker = FakeTrackerApi()
        val service = createService(issueTracker)

        // SF-1261 — 'automatisch' is nu het default approvalMode (was voorheen 'false'/off): alleen
        // een AFWIJKENDE waarde (hier elke-stap = autoApprove=false) triggert nog een aparte
        // veld-update ná het aanmaken van de story.
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
        // Verify that approval mode was set to "elke-stap" after creation
        assertEquals("SF-1", issueTracker.lastUpdatedKey)
        assertEquals(ApprovalMode.EVERY_STEP.trackerValue, issueTracker.lastFieldUpdate?.values?.get(TrackerField.APPROVAL_MODE))
    }

    @Test
    fun `createStory without projectKey falls back to the single configured project`() {
        // SF-818 — het "Nieuwe story"-dialoog stuurt geen projectKey meer mee; de service kiest het
        // enige geconfigureerde project zodat de key-generatie (SF-###) blijft werken.
        val issueTracker = FakeTrackerApi().apply {
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
    fun `createStory fills in the resolved default AI model when left blank`() {
        // Zonder gekozen model moet het echte default-model meteen worden vastgelegd i.p.v. leeg
        // te blijven tot de eerste agent-dispatch — anders toont de storydetail-pagina nooit welk
        // model gebruikt gaat worden voor een story zonder expliciete keuze. AI-supplier "claude" is
        // hier expliciet gezet zoals het dashboard-formulier ook al standaard doet (selected optie).
        val issueTracker = FakeTrackerApi()
        val service = createService(issueTracker)

        service.createStory(
            projectKey = "SF",
            title = "Story zonder gekozen model",
            description = null,
            repo = null,
            aiSupplier = "claude",
            aiModel = null,
            start = false,
        )

        assertEquals("claude-sonnet-5", issueTracker.lastCreatedAiModel)
    }

    @Test
    fun `createStory falls back to the dummy model when AI supplier is also left blank`() {
        val issueTracker = FakeTrackerApi()
        val service = createService(issueTracker)

        service.createStory(
            projectKey = "SF",
            title = "Story zonder supplier of model",
            description = null,
            repo = null,
            aiSupplier = null,
            aiModel = null,
            start = false,
        )

        assertEquals("dummy-ai-client", issueTracker.lastCreatedAiModel)
    }

    @Test
    fun `createStory keeps an explicitly chosen AI model`() {
        val issueTracker = FakeTrackerApi()
        val service = createService(issueTracker)

        service.createStory(
            projectKey = "SF",
            title = "Story met gekozen model",
            description = null,
            repo = null,
            aiSupplier = "claude",
            aiModel = "claude-opus-4-8",
            start = false,
        )

        assertEquals("claude-opus-4-8", issueTracker.lastCreatedAiModel)
    }

    @Test
    fun `createStory with autoApprove=true does not call setApprovalMode`() {
        val issueTracker = FakeTrackerApi()
        val service = createService(issueTracker)

        // SF-1261 — 'automatisch' (autoApprove=true) is nu het default approvalMode, dus dit
        // triggert geen aparte veld-update ná het aanmaken van de story.
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
        // Verify that approval mode was NOT set (lastUpdatedKey should still be null)
        assertEquals(null, issueTracker.lastUpdatedKey)
    }

    // ── storyStatusBucket ────────────────────────────────────────────────────────

    @Test
    fun `storyStatusBucket maps done variants to done`() {
        val service = createService(FakeTrackerApi())
        assertEquals("done", service.storyStatusBucket("Done"))
        assertEquals("done", service.storyStatusBucket("fixed"))
        assertEquals("done", service.storyStatusBucket("VERIFIED"))
        assertEquals("done", service.storyStatusBucket("closed"))
        assertEquals("done", service.storyStatusBucket("resolved"))
    }

    @Test
    fun `storyStatusBucket maps in-progress variants to in-progress`() {
        val service = createService(FakeTrackerApi())
        assertEquals("in-progress", service.storyStatusBucket("In Progress"))
        assertEquals("in-progress", service.storyStatusBucket("to verify"))
        assertEquals("in-progress", service.storyStatusBucket("developing"))
    }

    @Test
    fun `storyStatusBucket maps unknown and null to todo`() {
        val service = createService(FakeTrackerApi())
        assertEquals("todo", service.storyStatusBucket(null))
        assertEquals("todo", service.storyStatusBucket(""))
        assertEquals("todo", service.storyStatusBucket("open"))
        assertEquals("todo", service.storyStatusBucket("backlog"))
        assertEquals("todo", service.storyStatusBucket("unknown-status"))
    }

    // ── repoMatchesProject ───────────────────────────────────────────────────────

    @Test
    fun `repoMatchesProject returns true on exact match`() {
        val service = createService(FakeTrackerApi())
        assert(service.repoMatchesProject("https://github.com/foo/bar.git", "https://github.com/foo/bar.git"))
    }

    @Test
    fun `repoMatchesProject returns true when db contains resolved url`() {
        val service = createService(FakeTrackerApi())
        assert(service.repoMatchesProject("https://github.com/foo/bar.git", "github.com/foo/bar"))
    }

    @Test
    fun `repoMatchesProject returns false on no match`() {
        val service = createService(FakeTrackerApi())
        assert(!service.repoMatchesProject("https://github.com/foo/other.git", "https://github.com/foo/bar.git"))
    }

    @Test
    fun `repoMatchesProject returns false when either is blank`() {
        val service = createService(FakeTrackerApi())
        assert(!service.repoMatchesProject("", "https://github.com/foo/bar.git"))
        assert(!service.repoMatchesProject("https://github.com/foo/bar.git", ""))
    }

    // ── parsePrdVersionJson ──────────────────────────────────────────────────────

    @Test
    fun `parsePrdVersionJson parses valid JSON`() {
        val service = createService(FakeTrackerApi())
        val json = """{"commitHash":"abc1234def","commitDate":"2026-06-01","branch":"main"}"""
        val result = service.parsePrdVersionJson(json)
        assertEquals("abc1234", result?.commitShort)
        assertEquals("2026-06-01", result?.commitDate)
        assertEquals("main", result?.branch)
    }

    @Test
    fun `parsePrdVersionJson returns null when commitHash missing`() {
        val service = createService(FakeTrackerApi())
        val json = """{"commitDate":"2026-06-01","branch":"main"}"""
        assertEquals(null, service.parsePrdVersionJson(json))
    }

    @Test
    fun `parsePrdVersionJson handles missing optional fields with empty strings`() {
        val service = createService(FakeTrackerApi())
        val json = """{"commitHash":"deadbeef"}"""
        val result = service.parsePrdVersionJson(json)
        assertEquals("deadbee", result?.commitShort)
        assertEquals("", result?.commitDate)
        assertEquals("", result?.branch)
    }

    // ── projectsOverview (SF-1069: regressie prd-versie/sync-status) ───────────────

    @Test
    fun `projectsOverview toont prdVersion en hasDeployConfig voor een project met een geldige rest-restart-config`() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/version") { exchange ->
            val body = """{"commitHash":"abc1234def","commitDate":"2026-07-08","branch":"main"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            val projectResolver = ProjectConfiguration(
                repos = mapOf("demo" to "https://example.invalid/demo.git"),
                deployConfigs = mapOf(
                    "demo" to nl.vdzon.softwarefactory.config.DeployConfig.RestRestart(
                        restartUrl = "http://127.0.0.1:$port/api/restart",
                        versionUrl = "http://127.0.0.1:$port/api/version",
                        tokenEnvVar = "SF_TEST_TOKEN",
                        pollIntervalSeconds = 15,
                        timeoutMinutes = 20,
                    ),
                ),
            )
            val queries = createQueries(FakeTrackerApi(), projectResolver)

            val page = queries.projectsOverview(force = true)

            val demo = page.projects.single { it.name == "demo" }
            assertTrue(demo.hasDeployConfig, "demo heeft een rest-restart-config, hasDeployConfig moet true zijn")
            assertEquals("abc1234", demo.prdVersion?.commitShort)
            assertEquals("2026-07-08", demo.prdVersion?.commitDate)
            assertEquals("main", demo.prdVersion?.branch)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `projectsOverview blijft prdVersion ophalen ook als de gedeelde ForkJoinPool commonPool verzadigd is`() {
        // Root-cause-regressietest (SF-1069): de prdVersion-/builds-/live-component-fan-out in
        // projectsOverview() gebruikte CompletableFuture.supplyAsync/thenApplyAsync ZONDER expliciete
        // executor, en viel daarmee terug op het process-brede ForkJoinPool.commonPool(). Zodra dat
        // pool door ander (trager, blocking) werk verzadigd is, verdringt dat de prdVersion-fetch
        // structureel voorbij PRD_VERSION_TIMEOUT_MS — voor alle projecten tegelijk, wat exact het
        // gerapporteerde symptoom is (overal "Geen productieversie beschikbaar"). Deze test verzadigt
        // het commonPool bewust vóór de aanroep; met de fix (eigen dedicated executor) blijft
        // prdVersion alsnog correct opgehaald.
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/version") { exchange ->
            val body = """{"commitHash":"abc1234def","commitDate":"2026-07-08","branch":"main"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val commonPool = java.util.concurrent.ForkJoinPool.commonPool()
        val saturationLatch = java.util.concurrent.CountDownLatch(1)
        val saturationTasks = (0 until commonPool.parallelism + 2).map {
            CompletableFuture.runAsync({ saturationLatch.await(10, TimeUnit.SECONDS) }, commonPool)
        }
        try {
            val port = server.address.port
            val projectResolver = ProjectConfiguration(
                repos = mapOf("demo" to "https://example.invalid/demo.git"),
                deployConfigs = mapOf(
                    "demo" to nl.vdzon.softwarefactory.config.DeployConfig.RestRestart(
                        restartUrl = "http://127.0.0.1:$port/api/restart",
                        versionUrl = "http://127.0.0.1:$port/api/version",
                        tokenEnvVar = "SF_TEST_TOKEN",
                        pollIntervalSeconds = 15,
                        timeoutMinutes = 20,
                    ),
                ),
            )
            val queries = createQueries(FakeTrackerApi(), projectResolver)

            val page = queries.projectsOverview(force = true)

            val demo = page.projects.single { it.name == "demo" }
            assertEquals(
                "abc1234",
                demo.prdVersion?.commitShort,
                "prdVersion had opgehaald moeten worden ook met een verzadigd commonPool",
            )
        } finally {
            saturationLatch.countDown()
            CompletableFuture.allOf(*saturationTasks.toTypedArray()).get(10, TimeUnit.SECONDS)
            server.stop(0)
        }
    }

    @Test
    fun `projectsOverview blijft UNAVAILABLE tonen voor een project zonder deploy-config`() {
        val projectResolver = ProjectConfiguration(repos = mapOf("zonder-deploy" to "https://example.invalid/zonder-deploy.git"))
        val queries = createQueries(FakeTrackerApi(), projectResolver)

        val page = queries.projectsOverview(force = true)

        val project = page.projects.single { it.name == "zonder-deploy" }
        assertFalse(project.hasDeployConfig)
        assertEquals(null, project.prdVersion)
        assertEquals(BuildSyncStatus.UNAVAILABLE, project.buildStatus.syncStatus)
    }

    @Test
    fun `shaPrefixMatch vergelijkt short vs full sha hoofdletter-ongevoelig`() {
        assertTrue(DashboardQueryService.shaPrefixMatch("deadbee", "deadbeefcafebabe"))
        assertTrue(DashboardQueryService.shaPrefixMatch("DEADBEE", "deadbeefcafebabe"))
        assertTrue(DashboardQueryService.shaPrefixMatch("deadbeefcafebabe", "deadbee"))
        assertFalse(DashboardQueryService.shaPrefixMatch("deadbee", "cafebabe"))
        assertFalse(DashboardQueryService.shaPrefixMatch("", "deadbee"))
        assertFalse(DashboardQueryService.shaPrefixMatch("deadbee", ""))
    }

    @Test
    fun `buildStatusFor zonder deploy-configuratie is altijd UNAVAILABLE`() {
        val status = DashboardQueryService.buildStatusFor(
            runs = listOf(mainRun(status = "completed", headSha = "deadbeef")),
            defaultBranch = "main",
            hasDeployConfig = false,
            prdVersion = PrdVersionInfo(commitShort = "deadbee", commitDate = "2026-07-08", branch = "main"),
        )
        assertEquals(BuildSyncStatus.UNAVAILABLE, status.syncStatus)
    }

    @Test
    fun `buildStatusFor met matchende sha is IN_SYNC`() {
        val status = DashboardQueryService.buildStatusFor(
            runs = listOf(mainRun(status = "completed", headSha = "deadbeefcafebabe", updatedAt = "2026-07-08T10:05:00Z")),
            defaultBranch = "main",
            hasDeployConfig = true,
            prdVersion = PrdVersionInfo(commitShort = "deadbee", commitDate = "2026-07-08", branch = "main"),
        )
        assertEquals(BuildSyncStatus.IN_SYNC, status.syncStatus)
        assertEquals("2026-07-08T10:05:00Z", status.lastMainBuildAt)
    }

    @Test
    fun `buildStatusFor met afwijkende sha is OUT_OF_SYNC`() {
        val status = DashboardQueryService.buildStatusFor(
            runs = listOf(mainRun(status = "completed", headSha = "cafebabe")),
            defaultBranch = "main",
            hasDeployConfig = true,
            prdVersion = PrdVersionInfo(commitShort = "deadbee", commitDate = "2026-07-08", branch = "main"),
        )
        assertEquals(BuildSyncStatus.OUT_OF_SYNC, status.syncStatus)
    }

    @Test
    fun `buildStatusFor zonder bekende main-build-sha is UNAVAILABLE`() {
        val status = DashboardQueryService.buildStatusFor(
            runs = emptyList(),
            defaultBranch = "main",
            hasDeployConfig = true,
            prdVersion = PrdVersionInfo(commitShort = "deadbee", commitDate = "2026-07-08", branch = "main"),
        )
        assertEquals(BuildSyncStatus.UNAVAILABLE, status.syncStatus)
        assertEquals(null, status.lastMainBuildAt)
    }

    @Test
    fun `buildStatusFor onderscheidt actieve main- en PR-builds`() {
        val status = DashboardQueryService.buildStatusFor(
            runs = listOf(
                mainRun(status = "in_progress", headSha = "aaa"),
                WorkflowRunInfo(
                    repository = "robbert/sf",
                    projectKey = "SF",
                    workflowName = "Validate PR",
                    status = "queued",
                    conclusion = null,
                    branch = "ai/SF-1",
                    event = "pull_request",
                    durationSeconds = null,
                    updatedAt = null,
                    htmlUrl = "",
                ),
            ),
            defaultBranch = "main",
            hasDeployConfig = false,
            prdVersion = null,
        )
        assertTrue(status.mainBuildActive)
        assertTrue(status.prBuildActive)
    }

    @Test
    fun `buildStatusFor zonder actieve builds meldt geen actieve build`() {
        val status = DashboardQueryService.buildStatusFor(
            runs = listOf(mainRun(status = "completed", headSha = "aaa")),
            defaultBranch = "main",
            hasDeployConfig = false,
            prdVersion = null,
        )
        assertFalse(status.mainBuildActive)
        assertFalse(status.prBuildActive)
    }

    @Test
    fun `apkSyncStatus met matchende commit-sha is IN_SYNC`() {
        assertEquals(BuildSyncStatus.IN_SYNC, DashboardQueryService.apkSyncStatus("deadbee", "deadbeefcafebabe"))
    }

    @Test
    fun `apkSyncStatus met afwijkende commit-sha is OUT_OF_SYNC`() {
        assertEquals(BuildSyncStatus.OUT_OF_SYNC, DashboardQueryService.apkSyncStatus("cafebabe", "deadbeefcafebabe"))
    }

    @Test
    fun `apkSyncStatus zonder bekende release-commit is UNAVAILABLE`() {
        assertEquals(BuildSyncStatus.UNAVAILABLE, DashboardQueryService.apkSyncStatus(null, "deadbeefcafebabe"))
        assertEquals(BuildSyncStatus.UNAVAILABLE, DashboardQueryService.apkSyncStatus("", "deadbeefcafebabe"))
    }

    @Test
    fun `apkSyncStatus zonder bekende main-build-sha is UNAVAILABLE`() {
        assertEquals(BuildSyncStatus.UNAVAILABLE, DashboardQueryService.apkSyncStatus("deadbee", null))
        assertEquals(BuildSyncStatus.UNAVAILABLE, DashboardQueryService.apkSyncStatus("deadbee", ""))
    }

    // ── deployRolloutView (Story 4: story-detail per-onderdeel build-status) ───────

    @Test
    fun `deployRolloutView returns no targets and no stage when the story has no DEPLOY subtask`() {
        val service = createQueries(FakeTrackerApi(), ProjectConfiguration(emptyMap()))
        val story = storyIssue(storyPhase = "development", autoApprove = true)

        val (targets, stage) = service.deployRolloutView("SF-1", story, subtasks = emptyList(), errors = mutableListOf())

        assertEquals(emptyList<Any>(), targets)
        assertEquals(null, stage)
    }

    @Test
    fun `deployRolloutView returns only the matched subset, PENDING and IN_PULL_REQUEST while the PR is still open`() {
        // Twee geraakte doelen komen terug van de DeployTargetStatusApi (de "juiste subset" is al
        // door matchedDeployTargetsFor bepaald, zie DeploySubtaskHandlerTest); deployRolloutView mag
        // die niet aanvullen/inperken, alleen een status + rolloutfase toevoegen.
        var seenStoryKey: String? = null
        val fakeApi = DeployTargetStatusApi { storyKey, _ ->
            seenStoryKey = storyKey
            listOf(
                MatchedDeployTarget(DeployTarget(name = "frontend", config = DeployConfig.Skip()), watched = true),
                MatchedDeployTarget(DeployTarget(name = "backend", config = DeployConfig.Skip()), watched = true),
            )
        }
        val service = createQueries(FakeTrackerApi(), ProjectConfiguration(emptyMap()), fakeApi)
        val story = storyIssue(storyPhase = "development", autoApprove = true)
        val mergeSubtask = subtaskIssue(subtaskPhase = SubtaskPhase.START.trackerValue, subtaskType = "merge", autoApprove = true)
        val deploySubtask = subtaskIssue(subtaskPhase = SubtaskPhase.START.trackerValue, subtaskType = "deploy", autoApprove = true)

        val (targets, stage) =
            service.deployRolloutView("SF-1", story, listOf(mergeSubtask, deploySubtask), mutableListOf())

        assertEquals("SF-1", seenStoryKey)
        assertEquals(listOf("frontend", "backend"), targets.map { it.name })
        assertTrue(targets.all { it.status == DeployTargetRuntimeStatus.PENDING })
        assertEquals(DeployRolloutStage.IN_PULL_REQUEST, stage)
    }

    @Test
    fun `deployRolloutView reports MERGED_AWAITING_DEPLOY and IN_PROGRESS once merged but not yet deployed`() {
        val fakeApi = DeployTargetStatusApi { _, _ ->
            listOf(
                MatchedDeployTarget(DeployTarget(name = "frontend", config = DeployConfig.Skip()), watched = true),
                MatchedDeployTarget(DeployTarget(name = "backend", config = DeployConfig.Skip()), watched = true),
            )
        }
        val service = createQueries(FakeTrackerApi(), ProjectConfiguration(emptyMap()), fakeApi)
        val story = storyIssue(storyPhase = "development", autoApprove = true)
        val mergeSubtask = subtaskIssue(subtaskPhase = SubtaskPhase.MERGE_APPROVED.trackerValue, subtaskType = "merge", autoApprove = true)
        val deploySubtask = subtaskIssue(subtaskPhase = SubtaskPhase.DEPLOYING.trackerValue, subtaskType = "deploy", autoApprove = true)

        val (targets, stage) =
            service.deployRolloutView("SF-1", story, listOf(mergeSubtask, deploySubtask), mutableListOf())

        assertEquals(DeployRolloutStage.MERGED_AWAITING_DEPLOY, stage)
        assertTrue(targets.all { it.status == DeployTargetRuntimeStatus.IN_PROGRESS })
    }

    @Test
    fun `deployRolloutView reports DEPLOYED once merged and every matched target is approved`() {
        val fakeApi = DeployTargetStatusApi { _, _ ->
            listOf(MatchedDeployTarget(DeployTarget(name = "backend", config = DeployConfig.Skip()), watched = true))
        }
        val service = createQueries(FakeTrackerApi(), ProjectConfiguration(emptyMap()), fakeApi)
        val story = storyIssue(storyPhase = "development", autoApprove = true)
        val mergeSubtask = subtaskIssue(subtaskPhase = SubtaskPhase.MERGE_APPROVED.trackerValue, subtaskType = "merge", autoApprove = true)
        val deploySubtask = subtaskIssue(subtaskPhase = SubtaskPhase.DEPLOY_APPROVED.trackerValue, subtaskType = "deploy", autoApprove = true)

        val (targets, stage) =
            service.deployRolloutView("SF-1", story, listOf(mergeSubtask, deploySubtask), mutableListOf())

        assertEquals(DeployRolloutStage.DEPLOYED, stage)
        assertEquals(DeployTargetRuntimeStatus.DONE, targets.single().status)
    }

    @Test
    fun `deployRolloutView reports DEPLOY_FAILED when the deploy subtask failed after merging`() {
        val fakeApi = DeployTargetStatusApi { _, _ ->
            listOf(MatchedDeployTarget(DeployTarget(name = "backend", config = DeployConfig.Skip()), watched = true))
        }
        val service = createQueries(FakeTrackerApi(), ProjectConfiguration(emptyMap()), fakeApi)
        val story = storyIssue(storyPhase = "development", autoApprove = true)
        val mergeSubtask = subtaskIssue(subtaskPhase = SubtaskPhase.MERGE_APPROVED.trackerValue, subtaskType = "merge", autoApprove = true)
        val deploySubtask = subtaskIssue(subtaskPhase = SubtaskPhase.DEPLOY_FAILED.trackerValue, subtaskType = "deploy", autoApprove = true)

        val (targets, stage) =
            service.deployRolloutView("SF-1", story, listOf(mergeSubtask, deploySubtask), mutableListOf())

        assertEquals(DeployRolloutStage.DEPLOY_FAILED, stage)
        assertEquals(DeployTargetRuntimeStatus.FAILED, targets.single().status)
    }

    @Test
    fun `deployRolloutView treats a matched but unwatched Skip target as DONE regardless of phase`() {
        val fakeApi = DeployTargetStatusApi { _, _ ->
            listOf(
                MatchedDeployTarget(DeployTarget(name = "frontend", config = DeployConfig.Skip()), watched = true),
                MatchedDeployTarget(DeployTarget(name = "docs-skip", config = DeployConfig.Skip()), watched = false),
            )
        }
        val service = createQueries(FakeTrackerApi(), ProjectConfiguration(emptyMap()), fakeApi)
        val story = storyIssue(storyPhase = "development", autoApprove = true)
        val mergeSubtask = subtaskIssue(subtaskPhase = SubtaskPhase.START.trackerValue, subtaskType = "merge", autoApprove = true)
        val deploySubtask = subtaskIssue(subtaskPhase = SubtaskPhase.START.trackerValue, subtaskType = "deploy", autoApprove = true)

        val (targets, stage) =
            service.deployRolloutView("SF-1", story, listOf(mergeSubtask, deploySubtask), mutableListOf())

        assertEquals(DeployRolloutStage.IN_PULL_REQUEST, stage)
        assertEquals(DeployTargetRuntimeStatus.PENDING, targets.single { it.name == "frontend" }.status)
        assertEquals(DeployTargetRuntimeStatus.DONE, targets.single { it.name == "docs-skip" }.status)
    }

    // ── rollout (Story 5: deployedAt/Rollout-tab) ───────────────────────────────────

    private fun uiStoryRun(
        storyKey: String = "SF-500",
        targetRepo: String = "git@github.com:robbert/sf.git",
        prNumber: Int? = 42,
        deployedAt: OffsetDateTime? = null,
    ) = nl.vdzon.softwarefactory.dashboard.models.UiStoryRun(
        id = 1L,
        storyKey = storyKey,
        targetRepo = targetRepo,
        workspacePath = null,
        startedAt = OffsetDateTime.parse("2026-07-01T09:00:00Z"),
        endedAt = OffsetDateTime.parse("2026-07-01T10:00:00Z"),
        finalStatus = "merged",
        branchName = "feature/x",
        prNumber = prNumber,
        prUrl = "https://github.example/pr/42",
        baseBranch = "main",
        branchPrefix = null,
        previewUrlTemplate = null,
        previewNamespaceTemplate = null,
        totalInputTokens = 0,
        totalOutputTokens = 0,
        totalCacheReadTokens = 0,
        totalCacheCreationTokens = 0,
        totalCostUsdEst = 0.0,
        deployedAt = deployedAt,
    )

    @Test
    fun `rolloutTargetsFor delegates to the DeployRolloutStatusApi with the run's storyKey, targetRepo and prNumber`() {
        var seenArgs: Triple<String, String, Int>? = null
        val fakeApi = DeployRolloutStatusApi { storyKey, targetRepo, prNumber ->
            seenArgs = Triple(storyKey, targetRepo, prNumber)
            listOf(DeployTargetLiveStatus("backend", live = true))
        }
        val service = createQueries(FakeTrackerApi(), ProjectConfiguration(emptyMap()), deployRolloutStatusApi = fakeApi)
        val run = uiStoryRun()

        val targets = service.rolloutTargetsFor(run, mutableListOf())

        assertEquals(Triple("SF-500", "git@github.com:robbert/sf.git", 42), seenArgs)
        assertEquals(listOf(DeployTargetLiveStatus("backend", true)), targets)
    }

    @Test
    fun `rolloutTargetsFor is null (status unknown) when the run has no PR number`() {
        val fakeApi = DeployRolloutStatusApi { _, _, _ -> error("should not be called without a PR number") }
        val service = createQueries(FakeTrackerApi(), ProjectConfiguration(emptyMap()), deployRolloutStatusApi = fakeApi)
        val run = uiStoryRun(prNumber = null)

        val targets = service.rolloutTargetsFor(run, mutableListOf())

        assertEquals(null, targets)
    }

    @Test
    fun `rolloutTargetsFor records an error and returns null instead of throwing when the port fails`() {
        val fakeApi = DeployRolloutStatusApi { _, _, _ -> throw IllegalStateException("gh unavailable") }
        val service = createQueries(FakeTrackerApi(), ProjectConfiguration(emptyMap()), deployRolloutStatusApi = fakeApi)
        val errors = mutableListOf<String>()

        val targets = service.rolloutTargetsFor(uiStoryRun(), errors)

        assertEquals(null, targets)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `rollout degrades gracefully (empty list + error) when the repository query fails`() {
        // StubJdbcTemplate heeft geen echte DataSource: repository.runsAwaitingDeployConfirmation()
        // gooit, en rollout() moet dat afvangen (zelfde load()-recept als de andere pagina's) i.p.v.
        // te crashen.
        val service = createQueries(FakeTrackerApi(), ProjectConfiguration(emptyMap()))

        val page = service.rollout()

        assertEquals(emptyList<Any>(), page.items)
        assertTrue(page.errors.isNotEmpty())
    }

    private fun mainRun(status: String, headSha: String, updatedAt: String? = null): WorkflowRunInfo =
        WorkflowRunInfo(
            repository = "robbert/sf",
            projectKey = "SF",
            workflowName = "Build",
            status = status,
            conclusion = if (status == "completed") "success" else null,
            branch = "main",
            event = "push",
            durationSeconds = null,
            updatedAt = updatedAt,
            htmlUrl = "",
            headSha = headSha,
            runStartedAt = updatedAt,
        )

    private class TestDashboardServices(
        private val queries: DashboardQueryService,
        private val commands: DashboardCommandService,
    ) {
        fun awaitsHuman(issue: TrackerIssue) = queries.awaitsHuman(issue)
        fun parsePrdVersionJson(json: String) = queries.parsePrdVersionJson(json)
        fun repoMatchesProject(actual: String, expected: String) = queries.repoMatchesProject(actual, expected)
        fun storyStatusBucket(status: String?) = queries.storyStatusBucket(status)
        fun setApprovalMode(storyKey: String, mode: String) = commands.setApprovalMode(storyKey, mode)
        fun createStory(
            projectKey: String?, title: String, description: String?, repo: String?, aiSupplier: String?,
            aiModel: String?, start: Boolean, autoApprove: Boolean = false, silent: Boolean = false,
        ) = commands.createStory(CreateStoryCommand(
            projectKey, title, description, repo, aiSupplier, aiModel, start,
            questionsAllowed = !silent,
            approvalMode = if (autoApprove) ApprovalMode.AUTOMATIC.trackerValue else ApprovalMode.EVERY_STEP.trackerValue,
        ))
    }

    /** Bouwt een [DashboardQueryService] met een expliciete [projectResolver] (i.p.v. de lege default van [createService]). */
    private fun createQueries(
        issueTracker: TrackerApi,
        projectResolver: ProjectConfiguration,
        deployTargetStatusApi: DeployTargetStatusApi = DeployTargetStatusApi { _, _ -> emptyList() },
        deployRolloutStatusApi: DeployRolloutStatusApi = DeployRolloutStatusApi { _, _, _ -> null },
    ): DashboardQueryService {
        val secrets = FakeFactorySecrets()
        val repository = FactoryDashboardRepository(StubJdbcTemplate(), secrets)
        val operations = FactoryOperationsService(
            issueTrackerClient = issueTracker,
            orchestratorApi = FakeOrchestratorApi(),
            repository = repository,
            previewApi = FakePreviewApi(),
        )
        val jobsReader = nl.vdzon.softwarefactory.nightly.services.NightlyJobsReader()
        val settings = nl.vdzon.softwarefactory.nightly.repositories.NightlySettingsRepository(StubJdbcTemplate(), secrets)
        val deployClient = ProjectDeployClient()
        val workspaceLauncher = WorkspaceDesktopLauncher()
        val materializer = nl.vdzon.softwarefactory.runtime.services.SubtaskPlanMaterializer(issueTracker, projectResolver)
        return DashboardQueryService(
            issueTrackerClient = issueTracker,
            orchestratorApi = FakeOrchestratorApi(),
            repository = repository,
            factorySecrets = secrets,
            operations = operations,
            projectRepoResolver = projectResolver,
            versionService = FactoryVersionService(),
            nightlySettingsRepository = settings,
            nightlyRunRepository = nl.vdzon.softwarefactory.nightly.repositories.NightlyRunRepository(StubJdbcTemplate(), secrets),
            nightlyRunJobRepository = nl.vdzon.softwarefactory.nightly.repositories.NightlyRunJobRepository(StubJdbcTemplate(), secrets),
            nightlyJobsReader = jobsReader,
            deployClient = deployClient,
            workspaceLauncher = workspaceLauncher,
            gitHubReleaseClient = nl.vdzon.softwarefactory.dashboard.services.GitHubReleaseClient(secrets),
            gitHubActionsClient = nl.vdzon.softwarefactory.dashboard.services.GitHubActionsClient(secrets),
            deploymentStatusProbe = DeploymentStatusProbe { _, _ -> null },
            subtaskPlanMaterializer = materializer,
            agentLogApi = AgentLogService(JdbcAgentEventRepository(StubJdbcTemplate(), secrets, jacksonObjectMapper()), jacksonObjectMapper()),
            deployTargetStatusApi = deployTargetStatusApi,
            deployRolloutStatusApi = deployRolloutStatusApi,
        )
    }

    private fun createService(issueTracker: TrackerApi): TestDashboardServices {
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
        val projectResolver = ProjectConfiguration(emptyMap())
        val jobsReader = nl.vdzon.softwarefactory.nightly.services.NightlyJobsReader()
        val settings = nl.vdzon.softwarefactory.nightly.repositories.NightlySettingsRepository(StubJdbcTemplate(), secrets)
        val deployClient = ProjectDeployClient()
        val workspaceLauncher = WorkspaceDesktopLauncher()
        val materializer = nl.vdzon.softwarefactory.runtime.services.SubtaskPlanMaterializer(issueTracker, projectResolver)
        val queries = DashboardQueryService(
            issueTrackerClient = issueTracker,
            orchestratorApi = FakeOrchestratorApi(),
            repository = repository,
            factorySecrets = secrets,
            operations = operations,
            projectRepoResolver = projectResolver,
            versionService = FactoryVersionService(),
            nightlySettingsRepository = settings,
            nightlyRunRepository = nl.vdzon.softwarefactory.nightly.repositories.NightlyRunRepository(StubJdbcTemplate(), secrets),
            nightlyRunJobRepository = nl.vdzon.softwarefactory.nightly.repositories.NightlyRunJobRepository(StubJdbcTemplate(), secrets),
            // Geen defaults meer in productie-code: de echte beans expliciet meegeven.
            nightlyJobsReader = jobsReader,
            deployClient = deployClient,
            workspaceLauncher = workspaceLauncher,
            gitHubReleaseClient = nl.vdzon.softwarefactory.dashboard.services.GitHubReleaseClient(secrets),
            gitHubActionsClient = nl.vdzon.softwarefactory.dashboard.services.GitHubActionsClient(secrets),
            deploymentStatusProbe = DeploymentStatusProbe { _, _ -> null },
            subtaskPlanMaterializer = materializer,
            agentLogApi = AgentLogService(JdbcAgentEventRepository(StubJdbcTemplate(), secrets, jacksonObjectMapper()), jacksonObjectMapper()),
            deployTargetStatusApi = DeployTargetStatusApi { _, _ -> emptyList() },
            deployRolloutStatusApi = DeployRolloutStatusApi { _, _, _ -> null },
        )
        val commands = DashboardCommandService(
            issueTracker, secrets, projectResolver, jobsReader, materializer, settings,
            FakeOrchestratorApi(), deployClient, repository, workspaceLauncher,
        )
        return TestDashboardServices(queries, commands)
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
                approvalMode = if (autoApprove) ApprovalMode.AUTOMATIC.trackerValue else ApprovalMode.EVERY_STEP.trackerValue,
            ),
        )

    /**
     * Een subtaak leest de goedkeuring-as ALTIJD via de parent (HumanActionPolicy.autoApproveActive),
     * nooit het eigen veld — het eigen `approvalMode` blijft dus op de class-default staan.
     */
    private fun subtaskIssue(subtaskPhase: String?, subtaskType: String = "review", autoApprove: Boolean = false): TrackerIssue =
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
                approvalMode = if (autoApprove) ApprovalMode.AUTOMATIC.trackerValue else ApprovalMode.EVERY_STEP.trackerValue,
            ),
        )

    private class StubJdbcTemplate : JdbcTemplate()

    private fun FakeFactorySecrets(): FactorySecrets =
        FactorySecrets(
            trackerProjects = emptyList(),
            githubToken = "fake",
            factoryDatabaseUrl = "jdbc:fake",
            factoryDatabaseSchema = "fake",
            kubeconfig = "fake",
            aiCredentialsDir = "fake",
            aiOauthToken = null,
            loadedFrom = "fake",
        )

    private class FakeOrchestratorApi : OrchestratorApi {
        override fun pollOnce() = OrchestratorPollResult(emptyList())
        override fun processIssue(issue: TrackerIssue) = IssueProcessResult.Skipped(issue.key, "test")
        override fun queueCommand(storyKey: String, command: FactoryCommand, reason: String?) = Unit
        override fun purgeStory(storyKey: String) = Unit
    }

    private class FakePreviewApi : PreviewApi {
        override fun render(template: String?, prNumber: Int?) = null
        override fun cleanup(namespace: String) = false
    }

    private class FakeTrackerApi : TrackerApi {
        var lastUpdatedKey: String? = null
        var lastFieldUpdate: TrackerFieldUpdate? = null
        var lastCreatedProjectKey: String? = null
        var lastCreatedTitle: String? = null
        var lastCreatedAiSupplier: String? = null
        var lastCreatedAiModel: String? = null
        var configuredProjects: List<TrackerProject> = emptyList()
        // Parent-story voor autoApproveActive-parent-lookup (HumanActionPolicy.autoApproveActive
        // resolvet een subtaak's goedkeuring-as altijd via de parent, nooit via het eigen veld).
        var parentIssue: TrackerIssue? = null
        private var createdStoryCounter = 0

        override fun ensureConfiguredProjects(): List<TrackerProject> = configuredProjects
        override fun findAiIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> = emptyList()
        override fun findWorkIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> = emptyList()
        override fun getIssue(issueKey: String): TrackerIssue =
            parentIssue?.takeIf { it.key == issueKey } ?: throw UnsupportedOperationException()
        override fun parentStoryKey(subtaskKey: String): String =
            parentIssue?.key ?: throw UnsupportedOperationException()
        override fun subtasksOf(parentKey: String): List<TrackerIssue> = emptyList()
        override fun createStory(projectKey: String, title: String, description: String?, repo: String?, aiSupplier: String?, aiModel: String?, start: Boolean, questionsAllowed: Boolean): TrackerIssue {
            lastCreatedProjectKey = projectKey
            lastCreatedTitle = title
            lastCreatedAiSupplier = aiSupplier
            lastCreatedAiModel = aiModel
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
        override fun createSubtask(parentKey: String, spec: nl.vdzon.softwarefactory.core.contracts.SubtaskSpec, supplier: String?): TrackerIssue = throw UnsupportedOperationException()
        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            lastUpdatedKey = issueKey
            lastFieldUpdate = update
        }
        override fun transitionIssue(issueKey: String, statusName: String) = Unit
        override fun postComment(issueKey: String, message: String): TrackerComment = throw UnsupportedOperationException()
        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment = throw UnsupportedOperationException()
    }
}
