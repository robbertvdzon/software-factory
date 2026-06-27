package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.orchestrator.services.*

import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.core.*
import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.orchestrator.*

import nl.vdzon.softwarefactory.core.AgentRuntime
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.AgentRunRepository
import nl.vdzon.softwarefactory.core.CostMonitor
import nl.vdzon.softwarefactory.core.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.core.OrchestratorSettings
import nl.vdzon.softwarefactory.pipeline.service.StoryPipelineService
import nl.vdzon.softwarefactory.pipeline.service.AgentDispatcher
import nl.vdzon.softwarefactory.pipeline.service.StoryRefinementCoordinator
import nl.vdzon.softwarefactory.pipeline.service.SubtaskExecutionCoordinator
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.youtrack.services.ProcessedCommentService
import nl.vdzon.softwarefactory.youtrack.repositories.ProcessedCommentStore
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.orchestrator.services.OrchestratorService
import nl.vdzon.softwarefactory.core.PreparedStoryWorkspace
import nl.vdzon.softwarefactory.core.RepositorySyncResult
import nl.vdzon.softwarefactory.core.StoryWorkspaceApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.nio.file.Path

class OrchestratorServiceTest {
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-05-23T20:00:00Z")
    private val clock: Clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    @Test
    fun `poll skips paused and errored issues and dispatches empty phase to refiner`() {
        val issueTracker = FakeYouTrackApi(
            listOf(
                issue("KAN-1", paused = true),
                issue("KAN-2", error = "blocked"),
                issue("KAN-3", phase = null),
            ),
        )
        val runtime = FakeAgentRuntime(now)
        val agentRuns = InMemoryAgentRunRepository()
        val service = service(issueTracker, runtime = runtime, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertEquals(
            listOf(
                IssueProcessResult.Skipped("KAN-1", "paused"),
                IssueProcessResult.Skipped("KAN-2", "error"),
                IssueProcessResult.Dispatched("KAN-3", AgentRole.REFINER, "factory-KAN-3-refiner"),
            ),
            result.issueResults,
        )
        // v2 fase 2a: een verse story (geen AI Phase) start de refine-flow op het Story Phase-veld.
        assertEquals("refining", issueTracker.lastUpdate("KAN-3").values[TrackerField.STORY_PHASE])
        assertEquals(now, issueTracker.lastUpdate("KAN-3").values[TrackerField.AGENT_STARTED_AT])
        assertEquals("KAN-3", runtime.dispatches.single().labels["story-key"])
        assertEquals("refiner", runtime.dispatches.single().labels["role"])
        assertEquals(5, runtime.dispatches.single().aiLevel)
        assertEquals("claude-opus-4-8", runtime.dispatches.single().aiModel)
        assertEquals("medium", runtime.dispatches.single().aiEffort)
        assertEquals(listOf("factory-KAN-3-refiner" to 1L), runtime.logCaptures)
        assertEquals(1, agentRuns.countForRole(1, AgentRole.REFINER))
    }

    @Test
    fun `fase 2a story refine flow waits and dispatches on the Story Phase field`() {
        val issueTracker = FakeYouTrackApi(
            listOf(
                issue("KAN-30", storyPhase = "refined-with-questions"),
                issue("KAN-31", storyPhase = "refined"),
                issue("KAN-33", storyPhase = "questions-answered"),
                issue("KAN-34", storyPhase = "refined-rejected"),
            ),
        )
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime)

        val result = service.pollOnce()

        assertEquals(
            listOf(
                IssueProcessResult.Skipped("KAN-30", "waiting-for-user"),
                IssueProcessResult.Skipped("KAN-31", "waiting-for-approval"),
                IssueProcessResult.Dispatched("KAN-33", AgentRole.REFINER, "factory-KAN-33-refiner"),
                IssueProcessResult.Dispatched("KAN-34", AgentRole.REFINER, "factory-KAN-34-refiner"),
            ),
            result.issueResults,
        )
        // Re-dispatch (questions-answered / refined-rejected) zet de actieve status op het Story Phase-veld.
        assertEquals("refining", issueTracker.lastUpdate("KAN-33").values[TrackerField.STORY_PHASE])
        assertEquals("refining", issueTracker.lastUpdate("KAN-34").values[TrackerField.STORY_PHASE])
        assertEquals(listOf("refiner", "refiner"), runtime.dispatches.map { it.labels["role"] })
    }

    @Test
    fun `fase 2b story plan flow dispatches planner and is terminal on planning-approved`() {
        val issueTracker = FakeYouTrackApi(
            listOf(
                issue("KAN-40", storyPhase = "refined-approved"),
                issue("KAN-41", storyPhase = "planned-with-questions"),
                issue("KAN-42", storyPhase = "planned"),
                issue("KAN-43", storyPhase = "planning-approved"),
                issue("KAN-44", storyPhase = "planning-rejected"),
                issue("KAN-45", storyPhase = "in-progress"),
            ),
        )
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime)

        val result = service.pollOnce()

        assertEquals(
            listOf(
                IssueProcessResult.Dispatched("KAN-40", AgentRole.PLANNER, "factory-KAN-40-planner"),
                IssueProcessResult.Skipped("KAN-41", "waiting-for-user"),
                IssueProcessResult.Skipped("KAN-42", "waiting-for-approval"),
                IssueProcessResult.Skipped("KAN-43", "refinement-done"),
                IssueProcessResult.Dispatched("KAN-44", AgentRole.PLANNER, "factory-KAN-44-planner"),
                IssueProcessResult.Skipped("KAN-45", "development-in-progress"),
            ),
            result.issueResults,
        )
        // refined-approved / planning-rejected starten de planner op het Story Phase-veld.
        assertEquals("planning", issueTracker.lastUpdate("KAN-40").values[TrackerField.STORY_PHASE])
        assertEquals("planning", issueTracker.lastUpdate("KAN-44").values[TrackerField.STORY_PHASE])
        assertEquals(listOf("planner", "planner"), runtime.dispatches.map { it.labels["role"] })
    }

    @Test
    fun `refined-approved promotes refiner proposed-description into the story description`() {
        val refinerComment = TrackerComment(
            "refiner-1",
            null,
            "Factory",
            """
            [REFINER] Ik heb de docs gelezen.
            <!-- proposed-description:start -->
            ## Scope
            De afgesproken spec.
            <!-- proposed-description:end -->
            {"phase":"refined"}
            """.trimIndent(),
            null,
        )
        val issueTracker = FakeYouTrackApi(
            listOf(
                issue(
                    "KAN-50",
                    storyPhase = "refined-approved",
                    description = "Originele ruwe aanvraag.",
                    comments = listOf(refinerComment),
                ),
            ),
        )
        val service = service(issueTracker)

        service.pollOnce()

        val promoted = issueTracker.descriptionUpdates.getValue("KAN-50")
        assertTrue(promoted.startsWith("<!-- refined-by-factory -->"), "Sentinel-marker ontbreekt: $promoted")
        assertTrue(promoted.contains("## Scope"))
        assertTrue(promoted.contains("De afgesproken spec."))
        assertFalse(promoted.contains("proposed-description:start"), "Markers moeten gestript zijn")
        assertFalse(promoted.contains("Ik heb de docs gelezen"), "Preambule hoort niet in description")
        assertFalse(promoted.contains("\"phase\""), "JSON-control-regel hoort niet in description")
        assertFalse(promoted.contains("## Oorspronkelijke aanvraag"), "Oorspronkelijke-aanvraag-blok hoort niet meer in description")
        assertFalse(promoted.contains("Originele ruwe aanvraag."), "Oorspronkelijke ruwe aanvraagtekst hoort niet meer in description")
    }

    @Test
    fun `refined-approved without a proposed-description block leaves the description untouched`() {
        val issueTracker = FakeYouTrackApi(
            listOf(
                issue(
                    "KAN-51",
                    storyPhase = "refined-approved",
                    description = "Originele ruwe aanvraag.",
                    comments = listOf(TrackerComment("refiner-1", null, "Factory", "[REFINER] Geen blok hier.", null)),
                ),
            ),
        )
        val service = service(issueTracker)

        service.pollOnce()

        assertFalse(issueTracker.descriptionUpdates.containsKey("KAN-51"), "Description mag niet gewijzigd zijn")
    }

    @Test
    fun `dispatching an agent moves the issue to the In progress lane`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-21", phase = null)))
        val service = service(issueTracker)

        val result = service.pollOnce()

        assertTrue(result.issueResults.single() is IssueProcessResult.Dispatched)
        assertEquals(listOf("KAN-21" to "In Progress"), issueTracker.transitions)
    }

    @Test
    fun `story with an empty repo field gets an error and is not dispatched`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-22", phase = null, repo = null)))
        val runtime = FakeAgentRuntime(now)

        val result = service(issueTracker, runtime = runtime).pollOnce()

        val res = result.issueResults.single()
        assertTrue(res is IssueProcessResult.Errored, "Verwacht Errored, kreeg $res")
        assertTrue((res as IssueProcessResult.Errored).message.contains("Repo"))
        assertTrue(runtime.dispatches.isEmpty(), "Geen dispatch zonder repo")
        assertTrue(issueTracker.transitions.isEmpty(), "Geen lane-transitie zonder repo")
    }

    @Test
    fun `a repo-field value not in the config is used directly as the repo url`() {
        val literalRepo = "git@github.com:robbertvdzon/direct.git"
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-23", phase = null, repo = literalRepo)))
        val runtime = FakeAgentRuntime(now)

        val result = service(issueTracker, runtime = runtime).pollOnce()

        assertTrue(result.issueResults.single() is IssueProcessResult.Dispatched)
        assertEquals(literalRepo, runtime.dispatches.single().targetRepo)
    }

    @Test
    fun `posts workspace link when story workspace is created`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-21", phase = null)))
        val service = service(issueTracker)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Dispatched("KAN-21", AgentRole.REFINER, "factory-KAN-21-refiner"), result.issueResults.single())
        val comment = issueTracker.postedComments.single()
        assertEquals("KAN-21", comment.first)
        assertTrue(comment.second.contains("Work folder aangemaakt"))
        assertTrue(comment.second.contains("/tmp/software-factory-test-workspaces/KAN-21/repo"))
        assertTrue(comment.second.contains("open -a \"IntelliJ IDEA\""))
    }

    @Test
    fun `does not repost workspace link when story already has workspace`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-22", phase = null)))
        val storyRuns = InMemoryStoryRunRepository()
        storyRuns.openOrCreate("KAN-22", "git@example/repo.git")
        storyRuns.updateWorkspace(
            storyRunId = 1,
            workspacePath = "/tmp/existing-workspace",
            branchName = "ai/KAN-22",
            baseBranch = "main",
            branchPrefix = "ai/",
            previewUrlTemplate = null,
            previewNamespaceTemplate = null,
            previewDbSecretRecipe = null,
        )
        val service = service(issueTracker, storyRuns = storyRuns)

        service.pollOnce()

        assertTrue(issueTracker.postedComments.isEmpty())
    }

                        @Test
    fun `recovers old missing container issue error by returning to previous phase`() {
        val issueTracker = FakeYouTrackApi(
            listOf(
                issue(
                    "KAN-20",
                    phase = "developing",
                    error = "[ORCHESTRATOR] Geen actieve container gevonden voor developing; handmatige triage nodig.",
                    agentStartedAt = now.minusHours(2),
                ),
            ),
        )
        val service = service(issueTracker)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Recovered("KAN-20", "refined-finished")), result.issueResults)
        val update = issueTracker.lastUpdate("KAN-20")
        assertEquals(null, update.values[TrackerField.ERROR])
        assertEquals("refined-finished", update.values[TrackerField.AI_PHASE])
    }

    @Test
    fun `recovery keeps the planner question instead of forcing planned`() {
        val issueTracker = FakeYouTrackApi(
            listOf(issue("KAN-50", storyPhase = "planning", agentStartedAt = now.minusMinutes(2))),
        )
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-50", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            addEnded(storyRun.id, AgentRole.PLANNER, outcome = "questions", summary = "(dummy) vraag aan PO")
        }
        val runtime = FakeAgentRuntime(now) // planner draait niet meer
        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Recovered("KAN-50", "planned-with-questions")), result.issueResults)
        assertEquals(
            "planned-with-questions",
            issueTracker.lastUpdate("KAN-50").values[TrackerField.STORY_PHASE],
        )
    }

        @Test
    fun `uses story developer loopback override before writing cap error`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-10", phase = "reviewed-with-feedback-for-developer", maxDeveloperLoopbacks = 7)))
        val storyRuns = InMemoryStoryRunRepository()
        val cappedRun = storyRuns.openOrCreate("KAN-10", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            repeat(7) { addEnded(cappedRun.id, AgentRole.DEVELOPER, outcome = "developed", summary = "done") }
        }
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertTrue(result.issueResults.single() is IssueProcessResult.Dispatched)
        assertEquals(1, runtime.dispatches.size)
    }

    @Test
    fun `developer loopback cap is counted per subtask, not story-wide`() {
        // SF-8 (development-subtaak) is in review afgekeurd -> wil een developer-fix (loopback). De story
        // heeft al 6 developer-runs van een ANDERE subtaak; story-breed zou dat de default-cap (5)
        // overschrijden. Per subtaak telt SF-8 = 0 developer-runs, dus de fix mag gewoon dispatchen.
        val sub = issue("SF-8", type = "Task", subtaskType = "development", subtaskPhase = "review-rejected")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "SF-1", subtasks = listOf(sub))
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("SF-1", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            repeat(6) { addEnded(storyRun.id, AgentRole.DEVELOPER, outcome = "developed", summary = "done", subtaskKey = "SF-2") }
        }
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        val dispatched = result.issueResults.single()
        assertTrue(dispatched is IssueProcessResult.Dispatched, "Verwacht Dispatched, kreeg $dispatched")
        assertEquals(AgentRole.DEVELOPER, (dispatched as IssueProcessResult.Dispatched).role)
    }

        @Test
    fun `PR factory comment is claimed and creates a development subtask`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-12", storyPhase = "planning-approved")))
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-12", "git@github.com:robbertvdzon/sample-build-project.git")
        storyRuns.updatePullRequest(
            storyRun.id,
            "ai/KAN-12",
            124,
            "https://github.com/robbertvdzon/sample-build-project/pull/124",
            "main",
            "ai/",
            "https://sample-pr-{pr_num}.example.com",
            "sample-pr-{pr_num}",
            null,
        )
        val pullRequests = FakeGitHubApi(
            commentsByPr = mapOf(124 to listOf(PullRequestComment(9001, "@factory kun je deze tekst aanpassen?"))),
        )
        val service = service(issueTracker, storyRuns = storyRuns, pullRequests = pullRequests)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.PrCommentTriggered("KAN-12", 124, 1), result.issueResults[1])
        // v2: PR-feedback wordt een nieuwe development-subtask, op fase `start` voor de keten.
        assertEquals(
            nl.vdzon.softwarefactory.core.SubtaskType.DEVELOPMENT,
            issueTracker.createdSubtasks.single().type,
        )
        assertEquals("start", issueTracker.lastUpdate("KAN-12-sub1").values[TrackerField.SUBTASK_PHASE])
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
        assertEquals(9001, pullRequests.claimedComments.single())
    }

        @Test
    fun `dispatch task context includes issue tracker description and only relevant unprocessed comments`() {
        val issue = issue(
            "KAN-15",
            phase = null,
            description = "Als PO wil ik duidelijke context in task markdown.",
            comments = listOf(
                TrackerComment("user-1", null, "Robbert", "Dit antwoord moet de refiner meenemen.", null),
                TrackerComment("user-2", null, "Robbert", "Dit antwoord is al verwerkt.", null),
                TrackerComment("review-1", null, "Reviewer", "[REVIEWER] niet relevant voor refiner.", null),
            ),
        )
        val issueTracker = FakeYouTrackApi(listOf(issue))
        val runtime = FakeAgentRuntime(now)
        val processed = InMemoryProcessedCommentStore().apply {
            markProcessed("KAN-15", "user-2", AgentRole.REFINER)
        }
        val service = service(issueTracker, runtime = runtime, processedCommentStore = processed)

        service.pollOnce()

        val context = runtime.dispatches.single().trackerContext.orEmpty()
        assertTrue(context.contains("Als PO wil ik duidelijke context"))
        assertTrue(context.contains("Dit antwoord moet de refiner meenemen."))
        assertFalse(context.contains("Dit antwoord is al verwerkt."))
        assertFalse(context.contains("niet relevant voor refiner"))
    }


        @Test
    fun `system credits pause prevents new dispatches`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-14", phase = null)))
        val runtime = FakeAgentRuntime(now)
        val credits = FakeCreditsPauseCoordinator().apply {
            pause = CreditsPause(now.plusMinutes(15), "credits exhausted")
        }
        val service = service(issueTracker, runtime = runtime, creditsPauseCoordinator = credits)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Skipped("KAN-14", "credits-paused")), result.issueResults)
        assertEquals(emptyList<AgentDispatchRequest>(), runtime.dispatches)
    }

    @Test
    fun `budget cap prevents dispatch`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-15", phase = null)))
        val runtime = FakeAgentRuntime(now)
        val costMonitor = FakeCostMonitor().apply { paused = true }
        val service = service(issueTracker, runtime = runtime, costMonitor = costMonitor)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Skipped("KAN-15", "budget-exceeded")), result.issueResults)
        assertEquals(emptyList<AgentDispatchRequest>(), runtime.dispatches)
    }

    @Test
    fun `terminal subtask chains to next not-done sibling`() {
        val s1 = issue("PF-7", type = "Task", subtaskType = "manual", subtaskPhase = "manual-action-done")
        val s2 = issue("PF-8", type = "Task", subtaskType = "development", subtaskPhase = null)
        val s3 = issue("PF-9", type = "Task", subtaskType = "development", subtaskPhase = "review-approved")
        val issueTracker = FakeYouTrackApi(listOf(s1, s2, s3), parentKey = "PF-1", subtasks = listOf(s1, s2, s3))

        val result = service(issueTracker).processIssue(s1)

        assertEquals(IssueProcessResult.Chained("PF-7", "PF-8"), result)
        // De volgende subtaak wordt op fase `start` gezet (geen labels meer).
        assertEquals("start", issueTracker.lastUpdate("PF-8").values[TrackerField.SUBTASK_PHASE])
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.removedTags)
        // De afgeronde subtask gaat naar Done; de story nog niet (er volgt een subtask).
        assertEquals(listOf("PF-7" to "Done"), issueTracker.transitions)
    }

    @Test
    fun `last terminal subtask untags itself and chains to nothing`() {
        val only = issue("PF-9", type = "Task", subtaskType = "summary", subtaskPhase = "summary-approved")
        val issueTracker = FakeYouTrackApi(listOf(only), parentKey = "PF-1", subtasks = listOf(only))

        val result = service(issueTracker).processIssue(only)

        assertEquals(IssueProcessResult.Chained("PF-9", null), result)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.removedTags)
        // Laatste subtask klaar → subtask Done én de hele story Done.
        assertEquals(listOf("PF-9" to "Done", "PF-1" to "Done"), issueTracker.transitions)
    }

    @Test
    fun `terminal subtask does not restart an already-running next sibling`() {
        // Regressie: een terminale subtaak wordt elke poll opnieuw verwerkt (geen labels meer).
        // De al-lopende volgende subtaak mag NIET telkens terug op `start` worden gezet.
        val finished = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "review-approved")
        val running = issue("PF-8", type = "Task", subtaskType = "development", subtaskPhase = "developing")
        val issueTracker = FakeYouTrackApi(
            listOf(finished, running),
            parentKey = "PF-1",
            subtasks = listOf(finished, running),
        )

        val result = service(issueTracker).processIssue(finished)

        assertEquals(IssueProcessResult.Chained("PF-7", "PF-8"), result)
        // PF-8 loopt al → geen fase-update (geen herstart-loop).
        assertFalse(issueTracker.updates.containsKey("PF-8"))
        // De afgeronde subtaak gaat wel naar Done; de story niet (er loopt nog een subtaak).
        assertEquals(listOf("PF-7" to "Done"), issueTracker.transitions)
    }

    @Test
    fun `developed subtask waits for human approval`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "developed")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "waiting-for-approval"), result)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
    }

    @Test
    fun `development subtask starts developer agent`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1")
        val runtime = FakeAgentRuntime(now)

        val result = service(issueTracker, runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.DEVELOPER, (result as IssueProcessResult.Dispatched).role)
        val dispatch = runtime.dispatches.single()
        assertEquals(AgentRole.DEVELOPER, dispatch.role)
        assertEquals("developing", dispatch.phase)
        assertEquals("PF-7", dispatch.storyKey)
    }

    @Test
    fun `development subtask after dev-approval starts reviewer`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "development-approved")
        val runtime = FakeAgentRuntime(now)

        val result = service(FakeYouTrackApi(listOf(sub), parentKey = "PF-1"), runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.REVIEWER, (result as IssueProcessResult.Dispatched).role)
        assertEquals("reviewing", runtime.dispatches.single().phase)
    }

    @Test
    fun `review-rejected starts a fix developer`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "review-rejected")
        val runtime = FakeAgentRuntime(now)

        val result = service(FakeYouTrackApi(listOf(sub), parentKey = "PF-1"), runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.DEVELOPER, (result as IssueProcessResult.Dispatched).role)
        assertEquals("developing", runtime.dispatches.single().phase)
    }

    @Test
    fun `review subtask re-reviews after a fix without separate dev approval`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "review", subtaskPhase = "developed")
        val runtime = FakeAgentRuntime(now)

        val result = service(FakeYouTrackApi(listOf(sub), parentKey = "PF-1"), runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.REVIEWER, (result as IssueProcessResult.Dispatched).role)
    }

    @Test
    fun `manual subtask without phase moves to awaiting-human`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "manual")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Recovered("PF-7", "awaiting-human"), result)
    }

    @Test
    fun `subtask inherits AI-supplier from parent when its own is empty`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", aiSupplier = "")
        val parent = issue("PF-1", aiSupplier = "claude")
        val issueTracker = FakeYouTrackApi(listOf(sub, parent), parentKey = "PF-1")
        val runtime = FakeAgentRuntime(now)

        val result = service(issueTracker, runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.DEVELOPER, (result as IssueProcessResult.Dispatched).role)
        assertEquals("claude", runtime.dispatches.single().aiSupplier)
    }

    @Test
    fun `subtask dispatch is serialized on the parent branch`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1")
        val runtime = FakeAgentRuntime(now, runningStories = setOf("PF-1"))

        val result = service(issueTracker, runtime = runtime).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "concurrency-cap"), result)
        assertEquals(emptyList<AgentDispatchRequest>(), runtime.dispatches)
    }

    @Test
    fun `paused parent story halts subtask dispatch`() {
        val parent = issue("PF-1", paused = true)
        val sub = issue("PF-7", type = "Task", subtaskType = "development")
        val issueTracker = FakeYouTrackApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "parent-paused"), result)
    }

    @Test
    fun `summarized subtask waits for approval`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarized")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "waiting-for-approval"), result)
    }

    @Test
    fun `auto-approve advances refined story to refined-approved`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-31", storyPhase = "refined", autoApprove = true)))

        val result = service(issueTracker).processIssue(issueTracker.getIssue("KAN-31"))

        assertEquals(IssueProcessResult.Recovered("KAN-31", "refined-approved"), result)
        assertEquals("refined-approved", issueTracker.lastUpdate("KAN-31").values[TrackerField.STORY_PHASE])
    }

    @Test
    fun `auto-approve advances planned story to planning-approved`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-42", storyPhase = "planned", autoApprove = true)))

        val result = service(issueTracker).processIssue(issueTracker.getIssue("KAN-42"))

        assertEquals(IssueProcessResult.Recovered("KAN-42", "planning-approved"), result)
        assertEquals("planning-approved", issueTracker.lastUpdate("KAN-42").values[TrackerField.STORY_PHASE])
    }

    @Test
    fun `auto-approve off keeps refined story waiting for approval`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-31", storyPhase = "refined")))

        val result = service(issueTracker).processIssue(issueTracker.getIssue("KAN-31"))

        assertEquals(IssueProcessResult.Skipped("KAN-31", "waiting-for-approval"), result)
    }

    @Test
    fun `auto-approve on parent advances developed subtask to development-approved`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "developed")
        val parent = issue("PF-1", autoApprove = true)
        val issueTracker = FakeYouTrackApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Recovered("PF-7", "development-approved"), result)
        assertEquals("development-approved", issueTracker.lastUpdate("PF-7").values[TrackerField.SUBTASK_PHASE])
    }

    @Test
    fun `auto-approve on parent advances summarized subtask to summary-approved`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarized")
        val parent = issue("PF-1", autoApprove = true)
        val issueTracker = FakeYouTrackApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Recovered("PF-7", "summary-approved"), result)
        assertEquals("summary-approved", issueTracker.lastUpdate("PF-7").values[TrackerField.SUBTASK_PHASE])
    }

    // ── SF-335: silent — autonoom doorzetten, vragen → error, geen wachten ───────

    @Test
    fun `silent implies auto-approve advances refined story to refined-approved`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-31", storyPhase = "refined", silent = true)))

        val result = service(issueTracker).processIssue(issueTracker.getIssue("KAN-31"))

        assertEquals(IssueProcessResult.Recovered("KAN-31", "refined-approved"), result)
        assertEquals("refined-approved", issueTracker.lastUpdate("KAN-31").values[TrackerField.STORY_PHASE])
    }

    @Test
    fun `silent story with refiner questions goes to clarification error instead of waiting`() {
        val story = issue(
            "KAN-31",
            storyPhase = "refined-with-questions",
            silent = true,
            comments = listOf(TrackerComment("c-1", null, "Factory", "[REFINER] Welke kleur moet de knop hebben?", null)),
        )
        val issueTracker = FakeYouTrackApi(listOf(story))

        val result = service(issueTracker).processIssue(story)

        assertTrue(result is IssueProcessResult.Errored, "verwacht Errored, was $result")
        val error = issueTracker.lastUpdate("KAN-31").values[TrackerField.ERROR] as String
        assertEquals(ErrorCategory.CLARIFICATION, ErrorCategory.of(error))
        assertTrue(error.contains("Welke kleur"), error)
    }

    @Test
    fun `non-silent story with refiner questions keeps waiting`() {
        val story = issue("KAN-31", storyPhase = "refined-with-questions")
        val issueTracker = FakeYouTrackApi(listOf(story))

        val result = service(issueTracker).processIssue(story)

        assertEquals(IssueProcessResult.Skipped("KAN-31", "waiting-for-user"), result)
    }

    @Test
    fun `silent parent makes developed-with-questions subtask error instead of waiting`() {
        val sub = issue(
            "PF-7",
            type = "Task",
            subtaskType = "development",
            subtaskPhase = "developed-with-questions",
            comments = listOf(TrackerComment("c-1", null, "Factory", "[DEVELOPER] Welke endpoint moet ik gebruiken?", null)),
        )
        val parent = issue("PF-1", silent = true)
        val issueTracker = FakeYouTrackApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertTrue(result is IssueProcessResult.Errored, "verwacht Errored, was $result")
        val error = issueTracker.lastUpdate("PF-7").values[TrackerField.ERROR] as String
        assertEquals(ErrorCategory.CLARIFICATION, ErrorCategory.of(error))
    }

    @Test
    fun `silent parent advances developed subtask to development-approved`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "developed")
        val parent = issue("PF-1", silent = true)
        val issueTracker = FakeYouTrackApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Recovered("PF-7", "development-approved"), result)
        assertEquals("development-approved", issueTracker.lastUpdate("PF-7").values[TrackerField.SUBTASK_PHASE])
    }

    @Test
    fun `documentation start dispatches the documenter`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "documentation", subtaskPhase = "start", aiSupplier = "claude")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1")
        val runtime = FakeAgentRuntime(now)

        val result = service(issueTracker, runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.DOCUMENTER, (result as IssueProcessResult.Dispatched).role)
        assertEquals("documenting", runtime.dispatches.single().phase)
    }

    @Test
    fun `documented subtask waits for approval`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "documentation", subtaskPhase = "documented")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "waiting-for-approval"), result)
    }

    @Test
    fun `auto-approve on parent advances documented subtask to documentation-approved`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "documentation", subtaskPhase = "documented")
        val parent = issue("PF-1", autoApprove = true)
        val issueTracker = FakeYouTrackApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Recovered("PF-7", "documentation-approved"), result)
        assertEquals("documentation-approved", issueTracker.lastUpdate("PF-7").values[TrackerField.SUBTASK_PHASE])
    }

    @Test
    fun `documentation-approved subtask chains to the next sibling`() {
        val doc = issue("PF-7", type = "Task", subtaskType = "documentation", subtaskPhase = "documentation-approved")
        val gate = issue("PF-8", type = "Task", subtaskType = "manual-approve", subtaskPhase = null)
        val issueTracker = FakeYouTrackApi(listOf(doc, gate), parentKey = "PF-1", subtasks = listOf(doc, gate))

        val result = service(issueTracker).processIssue(doc)

        assertEquals(IssueProcessResult.Chained("PF-7", "PF-8"), result)
        assertEquals("start", issueTracker.lastUpdate("PF-8").values[TrackerField.SUBTASK_PHASE])
        assertEquals(listOf("PF-7" to "Done"), issueTracker.transitions)
    }

    @Test
    fun `auto-approve does not advance a developed-with-questions subtask`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "developed-with-questions")
        val parent = issue("PF-1", autoApprove = true)
        val issueTracker = FakeYouTrackApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "waiting-for-user"), result)
    }

    @Test
    fun `subtask recovery waits for a recently dispatched agent instead of re-dispatching`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarizing", agentStartedAt = now.minusSeconds(5))
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1", subtasks = listOf(sub))
        val runtime = FakeAgentRuntime(now) // geen draaiende container
        val service = service(issueTracker, runtime = runtime)

        val result = service.pollOnce()

        // Net gedispatcht → niet meteen opnieuw starten, maar wachten op de completion.
        assertEquals(IssueProcessResult.Skipped("PF-7", "waiting-for-active-phase-recovery"), result.issueResults.single())
        assertTrue(runtime.dispatches.isEmpty())
    }

    @Test
    fun `subtask recovery re-dispatches a long-hanging agent`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarizing", agentStartedAt = now.minusMinutes(5))
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1", subtasks = listOf(sub))
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime)

        val result = service.pollOnce()

        assertTrue(result.issueResults.single() is IssueProcessResult.Dispatched)
        assertTrue(runtime.dispatches.isNotEmpty())
    }

    @Test
    fun `subtask recovery waits while a finished agent completion is still being processed`() {
        // Lang geleden gestart (tijd-grace verlopen) + geen draaiende container, MAAR de laatste
        // agent-run is nog niet afgerond (endedAt == null): de container stopte, maar de completion
        // is nog niet verwerkt. Recovery mag dan NIET herstarten (dat was de race), maar wachten.
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarizing", agentStartedAt = now.minusMinutes(5))
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1", subtasks = listOf(sub))
        val runtime = FakeAgentRuntime(now) // geen draaiende container
        val storyRuns = InMemoryStoryRunRepository()
        val agentRuns = InMemoryAgentRunRepository()
        val storyRun = storyRuns.openOrCreate("PF-1", "repo")
        agentRuns.recordStarted(
            storyRunId = storyRun.id,
            role = AgentRole.SUMMARIZER,
            containerName = "factory-pf-7-summarizer",
            model = null,
            effort = null,
            level = null,
            workspacePath = null,
            subtaskKey = "PF-7",
        )

        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)
        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Skipped("PF-7", "awaiting-agent-completion"), result.issueResults.single())
        assertTrue(runtime.dispatches.isEmpty())
    }

    @Test
    fun `subtask recovery waits shortly after a run ended while completion writes the phase`() {
        // Race: de run is NET geëindigd (endedAt gezet), maar de completion schrijft de nieuwe fase
        // pas daarna naar YouTrack. In dat venster is de fase nog actief; recovery mag niet herstarten.
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarizing", agentStartedAt = now.minusMinutes(5))
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1", subtasks = listOf(sub))
        val runtime = FakeAgentRuntime(now)
        val storyRuns = InMemoryStoryRunRepository()
        val agentRuns = InMemoryAgentRunRepository()
        val storyRun = storyRuns.openOrCreate("PF-1", "repo")
        agentRuns.addEnded(storyRun.id, AgentRole.SUMMARIZER, outcome = "summarized", summary = "done", subtaskKey = "PF-7", endedAt = now)

        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)
        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Skipped("PF-7", "awaiting-completion-settle"), result.issueResults.single())
        assertTrue(runtime.dispatches.isEmpty())
    }

    @Test
    fun `subtask recovery re-dispatches when a run ended long ago but the phase is still active`() {
        // Completion-grace verlopen + fase nog actief → echte hang: wél herstarten.
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarizing", agentStartedAt = now.minusMinutes(5))
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1", subtasks = listOf(sub))
        val runtime = FakeAgentRuntime(now)
        val storyRuns = InMemoryStoryRunRepository()
        val agentRuns = InMemoryAgentRunRepository()
        val storyRun = storyRuns.openOrCreate("PF-1", "repo")
        agentRuns.addEnded(storyRun.id, AgentRole.SUMMARIZER, outcome = "summarized", summary = "done", subtaskKey = "PF-7", endedAt = now.minusMinutes(5))

        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)
        val result = service.pollOnce()

        assertTrue(result.issueResults.single() is IssueProcessResult.Dispatched)
    }

    // ---- SF-192: manual-approve-poort (coördinator-fase-overgangen + reset) ----

    @Test
    fun `manual-approve start moves the gate to manual-approve-needed`() {
        val gate = issue(key = "KAN-1-sub1", type = "Task", subtaskType = "manual-approve", subtaskPhase = "start")
        val issueTracker = FakeYouTrackApi(listOf(gate), parentKey = "KAN-1", subtasks = listOf(gate))

        service(issueTracker).processIssue(gate)

        assertEquals(
            "manual-approve-needed",
            issueTracker.lastUpdate("KAN-1-sub1").values[TrackerField.SUBTASK_PHASE],
        )
    }

    @Test
    fun `manual-approve-needed waits for a human`() {
        val gate = issue(key = "KAN-1-sub1", type = "Task", subtaskType = "manual-approve", subtaskPhase = "manual-approve-needed")
        val issueTracker = FakeYouTrackApi(listOf(gate), parentKey = "KAN-1", subtasks = listOf(gate))

        val result = service(issueTracker).processIssue(gate)

        assertTrue(result is IssueProcessResult.Skipped)
        assertTrue(issueTracker.updates["KAN-1-sub1"] == null)
    }

    @Test
    fun `manually-approved advances the chain to the next subtask`() {
        val gate = issue(key = "KAN-1-sub1", type = "Task", subtaskType = "manual-approve", subtaskPhase = "manually-approved")
        val merge = issue(key = "KAN-1-sub2", type = "Task", subtaskType = "merge", subtaskPhase = null)
        val issueTracker = FakeYouTrackApi(listOf(gate, merge), parentKey = "KAN-1", subtasks = listOf(gate, merge))

        service(issueTracker).processIssue(gate)

        // De poort is afgerond → Done; de eerstvolgende subtaak (merge) gaat op `start`.
        assertTrue(issueTracker.transitions.contains("KAN-1-sub1" to "Done"))
        assertEquals("start", issueTracker.lastUpdate("KAN-1-sub2").values[TrackerField.SUBTASK_PHASE])
    }

    @Test
    fun `manually-not-approved resets every subtask to todo and restarts the chain`() {
        val dev = issue(key = "KAN-1-sub1", type = "Task", subtaskType = "development", subtaskPhase = "review-approved")
        val merge = issue(key = "KAN-1-sub2", type = "Task", subtaskType = "merge", subtaskPhase = null)
        val gate = issue(key = "KAN-1-sub3", type = "Task", subtaskType = "manual-approve", subtaskPhase = "manually-not-approved")
        val issueTracker = FakeYouTrackApi(
            listOf(dev, merge, gate),
            parentKey = "KAN-1",
            subtasks = listOf(dev, merge, gate),
        )

        service(issueTracker).processIssue(gate)

        // Alle subtaken (incl. de poort zelf) zijn fase-leeggemaakt en naar de todo-lane gezet.
        listOf("KAN-1-sub1", "KAN-1-sub2", "KAN-1-sub3").forEach { key ->
            assertTrue(issueTracker.transitions.contains(key to "Open"), "$key moet naar de todo-lane")
            assertTrue(
                issueTracker.updates.getValue(key).any { it.values[TrackerField.SUBTASK_PHASE] == null },
                "$key moet fase-leeg gemaakt zijn",
            )
        }
        // De story zelf gaat ook terug naar todo en de eerste subtaak start opnieuw.
        assertTrue(issueTracker.transitions.contains("KAN-1" to "Open"))
        assertEquals("start", issueTracker.lastUpdate("KAN-1-sub1").values[TrackerField.SUBTASK_PHASE])
    }

    // ---- SF-200: test-bevinding reset de hele keten i.p.v. developer-loopback ----

    @Test
    fun `test-rejected resets the whole chain and writes the test reason to the story`() {
        val dev = issue(key = "SF-1-sub1", type = "Task", subtaskType = "development", subtaskPhase = "review-approved")
        val test = issue(
            key = "SF-1-sub2",
            type = "Task",
            subtaskType = "test",
            subtaskPhase = "test-rejected",
            description = "Story-omschrijving",
        )
        val parent = issue(key = "SF-1", description = "Story-omschrijving")
        val issueTracker = FakeYouTrackApi(
            listOf(dev, test, parent),
            parentKey = "SF-1",
            subtasks = listOf(dev, test),
        )
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("SF-1", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            addEnded(storyRun.id, AgentRole.TESTER, outcome = "test-rejected", summary = "Knop doet niets bij klik.")
        }
        val runtime = FakeAgentRuntime(now)

        val result = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)
            .processIssue(test)

        // De tester start GEEN developer-fix meer.
        assertTrue(runtime.dispatches.isEmpty(), "test-bevinding mag geen agent dispatchen")
        assertTrue(result is IssueProcessResult.Chained, "verwacht een keten-reset, kreeg $result")
        // Alle subtaken (incl. de test-subtaak zelf) → fase-leeg + todo-lane; eerste subtaak op start.
        listOf("SF-1-sub1", "SF-1-sub2").forEach { key ->
            assertTrue(issueTracker.transitions.contains(key to "Open"), "$key moet naar de todo-lane")
        }
        assertTrue(issueTracker.transitions.contains("SF-1" to "Open"))
        assertEquals("start", issueTracker.lastUpdate("SF-1-sub1").values[TrackerField.SUBTASK_PHASE])
        // De testreden staat in een gemarkeerd blok in de story-description.
        val updatedDescription = issueTracker.descriptionUpdates.getValue("SF-1")
        assertTrue(updatedDescription.contains("<!-- test-feedback:start -->"))
        assertTrue(updatedDescription.contains("Knop doet niets bij klik."))
    }

    @Test
    fun `repeated test reason replaces the marker block instead of stacking`() {
        val test = issue(key = "SF-1-sub2", type = "Task", subtaskType = "test", subtaskPhase = "test-rejected")
        val parent = issue(
            key = "SF-1",
            description = "Story-omschrijving\n\n<!-- test-feedback:start -->\n## Test-feedback\nOude bevinding.\n<!-- test-feedback:end -->",
        )
        val issueTracker = FakeYouTrackApi(
            listOf(test, parent),
            parentKey = "SF-1",
            subtasks = listOf(test),
        )
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("SF-1", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            addEnded(storyRun.id, AgentRole.TESTER, outcome = "test-rejected", summary = "Nieuwe bevinding.")
        }

        service(issueTracker, storyRuns = storyRuns, agentRuns = agentRuns).processIssue(test)

        val updatedDescription = issueTracker.descriptionUpdates.getValue("SF-1")
        assertTrue(updatedDescription.contains("Nieuwe bevinding."))
        assertFalse(updatedDescription.contains("Oude bevinding."), "vorige test-feedback mag vervangen zijn")
        // Geen gestapelde blokken.
        assertEquals(1, "<!-- test-feedback:start -->".toRegex().findAll(updatedDescription).count())
    }

    @Test
    fun `test reason falls back to a placeholder when the tester run has no summary`() {
        val test = issue(key = "SF-1-sub2", type = "Task", subtaskType = "test", subtaskPhase = "test-rejected")
        val parent = issue(key = "SF-1", description = "Story-omschrijving")
        val issueTracker = FakeYouTrackApi(listOf(test, parent), parentKey = "SF-1", subtasks = listOf(test))
        val storyRuns = InMemoryStoryRunRepository()
        // Geen TESTER-run geseed -> geen reden beschikbaar.
        service(issueTracker, storyRuns = storyRuns).processIssue(test)

        assertTrue(issueTracker.descriptionUpdates.getValue("SF-1").contains("(geen reden opgegeven)"))
    }

    @Test
    fun `test-chain reset cap stops the chain with an error instead of resetting again`() {
        val test = issue(key = "SF-1-sub2", type = "Task", subtaskType = "test", subtaskPhase = "test-rejected")
        val parent = issue(key = "SF-1", description = "Story-omschrijving")
        val issueTracker = FakeYouTrackApi(listOf(test, parent), parentKey = "SF-1", subtasks = listOf(test))
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("SF-1", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            // Default-cap = 3 -> blokkeren vanaf de 4e TESTER-run.
            repeat(4) { addEnded(storyRun.id, AgentRole.TESTER, outcome = "test-rejected", summary = "bevinding") }
        }

        val result = service(issueTracker, storyRuns = storyRuns, agentRuns = agentRuns).processIssue(test)

        assertTrue(result is IssueProcessResult.Errored, "verwacht Errored bij overschrijden cap, kreeg $result")
        // De error staat op de test-subtaak zelf (zoals de developer-loopback-cap) zodat de top-level
        // error-guard 'm daarna skipt.
        val error = issueTracker.lastUpdate("SF-1-sub2").values[TrackerField.ERROR] as String
        assertTrue(error.contains("Test-chain reset cap bereikt"))
        // De triage-melding mag NIET het niet-werkende developer-cap-pad beloven: de test-cap kent geen
        // resume-increment, dus enkel `Error` legen herstart niets (re-error-loop op de volgende poll).
        // Ze moet juist de wél werkende herstelpaden noemen (pauzeren / re-implement → verse story-run).
        assertFalse(
            error.contains("leeg `Error` om opnieuw te proberen"),
            "triage-melding mag het niet-werkende 'leeg Error om opnieuw te proberen'-pad niet beloven",
        )
        assertTrue(error.contains("Paused = true"), "triage-melding moet pauzeren als werkende escape noemen")
        assertTrue(error.contains("re-implement"), "triage-melding moet re-implement als werkende escape noemen")
        // Geen reset: noch de story noch de subtaak is terug naar de todo-lane gezet en er is geen
        // test-feedback weggeschreven.
        assertFalse(issueTracker.transitions.contains("SF-1" to "Open"))
        assertTrue(issueTracker.descriptionUpdates.isEmpty())
    }

    @Test
    fun `chain is idempotent after a test reset - empty phase does nothing`() {
        // Na een reset is de test-subtaak fase-leeg; de eerstvolgende poll mag geen nieuwe reset triggeren.
        val test = issue(key = "SF-1-sub2", type = "Task", subtaskType = "test", subtaskPhase = null)
        val issueTracker = FakeYouTrackApi(listOf(test), parentKey = "SF-1", subtasks = listOf(test))

        val result = service(issueTracker).processIssue(test)

        assertEquals(IssueProcessResult.Skipped("SF-1-sub2", "not-started"), result)
        assertTrue(issueTracker.descriptionUpdates.isEmpty())
        assertTrue(issueTracker.transitions.isEmpty())
    }

    private fun service(
        issueTracker: FakeYouTrackApi,
        runtime: FakeAgentRuntime = FakeAgentRuntime(now),
        storyRuns: InMemoryStoryRunRepository = InMemoryStoryRunRepository(),
        agentRuns: InMemoryAgentRunRepository = InMemoryAgentRunRepository(),
        pullRequests: FakeGitHubApi = FakeGitHubApi(),
        processedCommentStore: InMemoryProcessedCommentStore = InMemoryProcessedCommentStore(),
        previewCleaner: FakePreviewEnvironmentCleaner = FakePreviewEnvironmentCleaner(),
        storyWorkspaceService: StoryWorkspaceApi = FakeStoryWorkspaceService(),
        costMonitor: FakeCostMonitor = FakeCostMonitor(),
        creditsPauseCoordinator: FakeCreditsPauseCoordinator = FakeCreditsPauseCoordinator(),
        manualCommandProcessor: ManualCommandProcessor = NoopManualCommandProcessor(),
        projectRepoResolver: ProjectRepoResolver = ProjectRepoResolver(mapOf("demo" to "git@example/repo.git")),
    ): OrchestratorService {
        val settings = OrchestratorSettings(
            pollInterval = java.time.Duration.ofSeconds(15),
            maxParallelRefiner = 1,
            maxParallelDeveloper = 2,
            maxParallelReviewer = 2,
            maxParallelTester = 1,
            maxParallelTotal = 4,
            maxDeveloperLoopbacks = 5,
            maxTransientRetries = 2,
            hardTimeout = java.time.Duration.ofMinutes(60),
            costMonitorInterval = java.time.Duration.ofMinutes(5),
            creditsPauseDefault = java.time.Duration.ofMinutes(30),
        )
        val dispatcher = AgentDispatcher(
            issueTrackerClient = issueTracker,
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            agentRunRepository = agentRuns,
            pullRequestClient = pullRequests,
            processedCommentService = ProcessedCommentService(issueTracker, processedCommentStore),
            previewApi = previewCleaner,
            storyWorkspaceService = storyWorkspaceService,
            costMonitor = costMonitor,
            projectRepoResolver = projectRepoResolver,
            settings = settings,
            clock = clock,
        )
        val pipeline = StoryPipelineService(
            issueTrackerClient = issueTracker,
            costMonitor = costMonitor,
            manualCommandProcessor = manualCommandProcessor,
            storyRefinementCoordinator = StoryRefinementCoordinator(
                issueTrackerClient = issueTracker,
                agentRuntime = runtime,
                storyRunRepository = storyRuns,
                agentRunRepository = agentRuns,
                settings = settings,
                clock = clock,
                dispatcher = dispatcher,
            ),
            subtaskExecutionCoordinator = SubtaskExecutionCoordinator(
                issueTrackerClient = issueTracker,
                agentRuntime = runtime,
                storyRunRepository = storyRuns,
                agentRunRepository = agentRuns,
                projectRepoResolver = projectRepoResolver,
                settings = settings,
                clock = clock,
                dispatcher = dispatcher,
                gitHubApi = pullRequests,
                factoryEnvironmentProvider = object : nl.vdzon.softwarefactory.config.ConfigApi {
                    override fun resolvedValues(): Map<String, String> = emptyMap()
                },
            ),
        )
        return OrchestratorService(
            issueTrackerClient = issueTracker,
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            pullRequestClient = pullRequests,
            previewApi = previewCleaner,
            storyWorkspaceService = storyWorkspaceService,
            creditsPauseCoordinator = creditsPauseCoordinator,
            clock = clock,
            pipeline = pipeline,
        )
    }

    private fun issue(
        key: String,
        phase: String? = null,
        // Default `start`: de meeste tests verwachten dat de orchestrator de issue oppakt.
        // Tests voor de 'niet gestart'-gate geven expliciet storyPhase/subtaskPhase = null mee.
        storyPhase: String? = "start",
        paused: Boolean = false,
        error: String? = null,
        targetRepo: String? = "git@example/repo.git",
        repo: String? = "demo",
        agentStartedAt: OffsetDateTime? = null,
        description: String? = "Beschrijving voor $key",
        comments: List<TrackerComment> = emptyList(),
        aiSupplier: String = "claude",
        maxDeveloperLoopbacks: Int? = null,
        type: String? = null,
        subtaskPhase: String? = "start",
        subtaskType: String? = null,
        autoApprove: Boolean = false,
        silent: Boolean = false,
    ): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = "Story $key",
            description = description,
            status = "Develop",
            fields = TrackerIssueFields(
                targetRepo = targetRepo,
                repo = repo,
                aiSupplier = aiSupplier,
                aiPhase = phase,
                aiLevel = 5,
                aiMaxDeveloperLoopbacks = maxDeveloperLoopbacks,
                aiTokenBudget = 100000,
                aiTokensUsed = 0,
                agentStartedAt = agentStartedAt,
                paused = paused,
                silent = silent,
                error = error,
                storyPhase = storyPhase,
                type = type,
                subtaskPhase = subtaskPhase,
                subtaskType = subtaskType,
                autoApprove = autoApprove,
            ),
            comments = comments,
        )

    private class FakeYouTrackApi(
        private val issues: List<TrackerIssue>,
        private val parentKey: String? = null,
        private val subtasks: List<TrackerIssue> = emptyList(),
    ) : YouTrackApi {
        val updates: MutableMap<String, MutableList<TrackerFieldUpdate>> = mutableMapOf()
        val transitions: MutableList<Pair<String, String>> = mutableListOf()
        val postedComments: MutableList<Pair<String, String>> = mutableListOf()
        val addedTags: MutableList<Pair<String, String>> = mutableListOf()
        val removedTags: MutableList<Pair<String, String>> = mutableListOf()

        override fun findAiIssues(projectKey: String, maxResults: Int): List<TrackerIssue> =
            issues

        override fun getIssue(issueKey: String): TrackerIssue =
            issues.first { it.key == issueKey }

        override fun parentStoryKey(subtaskKey: String): String? = parentKey

        override fun subtasksOf(parentKey: String): List<TrackerIssue> = subtasks

        val createdSubtasks: MutableList<nl.vdzon.softwarefactory.core.SubtaskSpec> = mutableListOf()

        override fun createSubtask(
            parentKey: String,
            spec: nl.vdzon.softwarefactory.core.SubtaskSpec,
            supplier: String?,
        ): TrackerIssue {
            createdSubtasks += spec
            return TrackerIssue(
                key = "$parentKey-sub${createdSubtasks.size}",
                summary = spec.title,
                description = spec.description,
                status = "Develop",
                fields = TrackerIssueFields(
                    targetRepo = null,
                    aiSupplier = supplier,
                    aiPhase = null,
                    aiLevel = null,
                    aiTokenBudget = null,
                    aiTokensUsed = null,
                    agentStartedAt = null,
                    paused = false,
                    error = null,
                    type = "Task",
                    subtaskType = spec.type.trackerValue,
                ),
                comments = emptyList(),
            )
        }

        override fun addTag(issueKey: String, tag: String) {
            addedTags += issueKey to tag
        }

        override fun removeTag(issueKey: String, tag: String) {
            removedTags += issueKey to tag
        }

        val descriptionUpdates: MutableMap<String, String> = mutableMapOf()

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            updates.getOrPut(issueKey) { mutableListOf() } += update
        }

        override fun updateIssueDescription(issueKey: String, description: String) {
            descriptionUpdates[issueKey] = description
        }

        override fun transitionIssue(issueKey: String, statusName: String) {
            transitions += issueKey to statusName
        }

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            throw UnsupportedOperationException()

        override fun postComment(issueKey: String, message: String): TrackerComment {
            postedComments += issueKey to message
            return TrackerComment("posted-${postedComments.size}", null, "Factory", message, null)
        }

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean =
            false

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean =
            false

        fun lastUpdate(issueKey: String): TrackerFieldUpdate =
            updates.getValue(issueKey).last()
    }

    private class InMemoryProcessedCommentStore : ProcessedCommentStore {
        private val processed = mutableSetOf<Triple<String, String, AgentRole>>()

        override fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean =
            Triple(storyKey, commentId, role) in processed

        override fun markProcessed(storyKey: String, commentId: String, role: AgentRole) {
            processed += Triple(storyKey, commentId, role)
        }
    }

    private class FakeAgentRuntime(
        private val now: OffsetDateTime,
        private val runningStories: Set<String> = emptySet(),
    ) : AgentRuntime {
        val dispatches: MutableList<AgentDispatchRequest> = mutableListOf()
        val logCaptures: MutableList<Pair<String, Long>> = mutableListOf()
        val runningByRole: MutableMap<AgentRole, Int> = mutableMapOf()

        override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
            dispatches += request
            return AgentDispatchResult(
                containerName = "factory-${request.storyKey}-${request.role.markerKeyPart}",
                startedAt = now,
            )
        }

        override fun captureLogs(containerName: String, agentRunId: Long) {
            logCaptures += containerName to agentRunId
        }

        override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean =
            false

        override fun isContainerRunning(containerName: String): Boolean =
            false

        override fun isAnyAgentRunningForStory(storyKey: String): Boolean =
            storyKey in runningStories

        override fun runningCount(role: AgentRole?): Int =
            if (role == null) runningByRole.values.sum() else runningByRole[role] ?: 0

        override fun killForStory(storyKey: String): Int =
            0
    }

    private class InMemoryStoryRunRepository : StoryRunRepository {
        private val runs = mutableMapOf<String, StoryRunRecord>()
        private var nextId = 1L
        val closed = mutableListOf<Pair<Long, String>>()

        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            runs.getOrPut(storyKey) { StoryRunRecord(nextId++, storyKey, targetRepo) }

        override fun get(storyRunId: Long): StoryRunRecord? =
            runs.values.firstOrNull { it.id == storyRunId }

        override fun updatePullRequest(
            storyRunId: Long,
            branchName: String,
            prNumber: Int?,
            prUrl: String?,
            baseBranch: String?,
            branchPrefix: String?,
            previewUrlTemplate: String?,
            previewNamespaceTemplate: String?,
            previewDbSecretRecipe: String?,
        ) {
            val entry = runs.entries.first { it.value.id == storyRunId }
            entry.setValue(
                entry.value.copy(
                    branchName = branchName,
                    prNumber = prNumber,
                    prUrl = prUrl,
                    baseBranch = baseBranch,
                    branchPrefix = branchPrefix,
                    previewUrlTemplate = previewUrlTemplate,
                    previewNamespaceTemplate = previewNamespaceTemplate,
                    previewDbSecretRecipe = previewDbSecretRecipe,
                ),
            )
        }

        override fun updateWorkspace(
            storyRunId: Long,
            workspacePath: String,
            branchName: String,
            baseBranch: String?,
            branchPrefix: String?,
            previewUrlTemplate: String?,
            previewNamespaceTemplate: String?,
            previewDbSecretRecipe: String?,
        ) {
            val entry = runs.entries.first { it.value.id == storyRunId }
            entry.setValue(
                entry.value.copy(
                    workspacePath = workspacePath,
                    branchName = branchName,
                    baseBranch = baseBranch,
                    branchPrefix = branchPrefix,
                    previewUrlTemplate = previewUrlTemplate,
                    previewNamespaceTemplate = previewNamespaceTemplate,
                    previewDbSecretRecipe = previewDbSecretRecipe,
                ),
            )
        }

        override fun activePullRequests(): List<StoryRunRecord> =
            runs.values.filter { it.prNumber != null }

        override fun activeRuns(): List<StoryRunRecord> =
            runs.values.toList()

        override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) {
            closed += storyRunId to finalStatus
            val entry = runs.entries.first { it.value.id == storyRunId }
            runs.remove(entry.key)
        }
    }

    private class InMemoryAgentRunRepository : AgentRunRepository {
        private val runs = mutableListOf<AgentRunRecord>()
        private val subtaskKeys = mutableMapOf<Long, String?>()
        private var nextId = 1L

        override fun recordStarted(
            storyRunId: Long,
            role: AgentRole,
            containerName: String,
            model: String?,
            effort: String?,
            level: Int?,
            workspacePath: String?,
            subtaskKey: String?,
        ): Long {
            val id = nextId++
            subtaskKeys[id] = subtaskKey
            runs += AgentRunRecord(
                id = id,
                storyRunId = storyRunId,
                role = role,
                containerName = containerName,
                startedAt = OffsetDateTime.now(),
                endedAt = null,
                outcome = null,
                summaryText = null,
                model = model,
                effort = effort,
                level = level,
                workspacePath = workspacePath,
            )
            return id
        }

        override fun complete(
            containerName: String,
            completion: AgentRunCompletionRecord,
            endedAt: OffsetDateTime,
        ): CompletedAgentRun? = null

        override fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord) = Unit

        override fun activeRuns(): List<AgentRunRecord> =
            runs.filter { it.endedAt == null }

        override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? =
            recentForRole(storyRunId, role, limit = 1).firstOrNull()

        override fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord> =
            runs.filter { it.storyRunId == storyRunId && it.role == role }
                .sortedByDescending { it.id }
                .take(limit)

        override fun countForRole(storyRunId: Long, role: AgentRole): Int =
            runs.count { it.storyRunId == storyRunId && it.role == role }

        override fun countForRoleAndSubtask(storyRunId: Long, role: AgentRole, subtaskKey: String): Int =
            runs.count { it.storyRunId == storyRunId && it.role == role && subtaskKeys[it.id] == subtaskKey }

        fun addEnded(
            storyRunId: Long,
            role: AgentRole,
            outcome: String,
            summary: String,
            subtaskKey: String? = null,
            endedAt: OffsetDateTime = OffsetDateTime.now(),
        ) {
            val id = nextId++
            subtaskKeys[id] = subtaskKey
            runs += AgentRunRecord(
                id = id,
                storyRunId = storyRunId,
                role = role,
                containerName = "factory-test-ended-$id",
                startedAt = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
                endedAt = endedAt,
                outcome = outcome,
                summaryText = summary,
            )
        }
    }

    private class FakeGitHubApi(
        private val mergedPrs: Set<Int> = emptySet(),
        private val commentsByPr: Map<Int, List<PullRequestComment>> = emptyMap(),
        private val claimedCommentsByPr: Map<Int, List<PullRequestComment>> = emptyMap(),
    ) : GitHubApi {
        val claimedComments = mutableListOf<Long>()

        override fun ensurePullRequest(
            repoRoot: java.nio.file.Path,
            branchName: String,
            baseBranch: String,
            title: String,
            body: String,
        ): PullRequestInfo =
            PullRequestInfo(number = 1, url = "https://github.example/pr/1")

        override fun isMerged(targetRepo: String, prNumber: Int): Boolean =
            prNumber in mergedPrs

        override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> =
            commentsByPr[prNumber].orEmpty()

        override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> =
            claimedCommentsByPr[prNumber].orEmpty()

        override fun markCommentClaimed(targetRepo: String, commentId: Long) {
            claimedComments += commentId
        }

        override fun markCommentDone(targetRepo: String, commentId: Long) = Unit

        override fun markCommentFailed(targetRepo: String, commentId: Long) = Unit

        override fun closePullRequest(targetRepo: String, prNumber: Int) = Unit

        override fun deleteBranch(targetRepo: String, branchName: String) = Unit

        override fun mergePullRequest(targetRepo: String, prNumber: Int) = Unit
    }

    private class FakePreviewEnvironmentCleaner : PreviewApi {
        override fun render(template: String?, prNumber: Int?): String? = PreviewApi.renderTemplate(template, prNumber)

        val cleanedNamespaces = mutableListOf<String>()

        override fun cleanup(namespace: String): Boolean {
            cleanedNamespaces += namespace
            return true
        }
    }

    private class FakeStoryWorkspaceService : StoryWorkspaceApi {
        override fun prepare(storyRun: StoryRunRecord, role: AgentRole): PreparedStoryWorkspace {
            val workspace = Path.of("/tmp/software-factory-test-workspaces/${storyRun.storyKey}")
            return PreparedStoryWorkspace(
                workspacePath = workspace,
                repoRoot = workspace.resolve("repo"),
                branchName = storyRun.branchName ?: "ai/${storyRun.storyKey}",
                baseBranch = storyRun.baseBranch ?: "main",
                branchPrefix = storyRun.branchPrefix ?: "ai/",
                deploymentConfig = DeploymentConfig(
                    defaultBaseBranch = storyRun.baseBranch ?: "main",
                    branchPrefix = storyRun.branchPrefix ?: "ai/",
                    previewUrlTemplate = storyRun.previewUrlTemplate,
                    previewNamespaceTemplate = storyRun.previewNamespaceTemplate,
                    previewDbSecretRecipe = storyRun.previewDbSecretRecipe,
                ),
            )
        }

        override fun syncAfterAgent(storyRun: StoryRunRecord, role: AgentRole): RepositorySyncResult =
            error("Not used by these tests")

        override fun cleanup(storyKey: String): Boolean =
            true
    }

    private class FakeCostMonitor : CostMonitor {
        var paused = false

        override fun applyBudgetTriggers(issue: TrackerIssue): TrackerIssue =
            issue

        override fun checkBudget(issue: TrackerIssue, storyRun: StoryRunRecord): CostMonitorCheckResult =
            CostMonitorCheckResult(storyRun.totalTokens, issue.fields.aiTokenBudget ?: 40000, paused, emptyList())

        override fun checkCompletedRun(storyKey: String, storyRun: StoryRunRecord) = Unit
    }

    private class FakeCreditsPauseCoordinator : CreditsPauseCoordinator {
        var pause: CreditsPause? = null
        val exhaustedStories = mutableListOf<String>()

        override fun activePause(now: OffsetDateTime): CreditsPause? =
            pause

        override fun handleCreditsExhausted(storyKey: String, summaryText: String?) {
            exhaustedStories += storyKey
        }
    }

    private class NoopManualCommandProcessor : ManualCommandProcessor {
        override fun apply(issue: TrackerIssue): ManualCommandApplication =
            ManualCommandApplication(issue)
    }
}
