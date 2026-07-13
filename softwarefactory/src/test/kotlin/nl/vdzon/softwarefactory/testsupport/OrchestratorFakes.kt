package nl.vdzon.softwarefactory.testsupport

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.CostMonitor
import nl.vdzon.softwarefactory.core.contracts.CostMonitorCheckResult
import nl.vdzon.softwarefactory.core.contracts.CreditsPause
import nl.vdzon.softwarefactory.core.contracts.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.core.contracts.ManualCommandApplication
import nl.vdzon.softwarefactory.core.contracts.ManualCommandProcessor
import nl.vdzon.softwarefactory.core.contracts.PreparedStoryWorkspace
import nl.vdzon.softwarefactory.core.contracts.RepositorySyncResult
import nl.vdzon.softwarefactory.core.contracts.StoryRunRecord
import nl.vdzon.softwarefactory.core.contracts.StoryWorkspaceApi
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.tracker.repositories.ProcessedCommentStore
import java.nio.file.Path
import java.time.OffsetDateTime

/** In-memory [ProcessedCommentStore]: houdt verwerkte (story, comment, rol)-triples in een set bij. */
class InMemoryProcessedCommentStore : ProcessedCommentStore {
    private val processed = mutableSetOf<Triple<String, String, AgentRole>>()

    override fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean =
        Triple(storyKey, commentId, role) in processed

    override fun markProcessed(storyKey: String, commentId: String, role: AgentRole) {
        processed += Triple(storyKey, commentId, role)
    }
}

/** [PreviewApi]-fake: rendert templates zoals productie, maar registreert cleanups alleen in [cleanedNamespaces]. */
class FakePreviewEnvironmentCleaner : PreviewApi {
    override fun render(template: String?, prNumber: Int?): String? = PreviewApi.renderTemplate(template, prNumber)

    val cleanedNamespaces = mutableListOf<String>()

    override fun cleanup(namespace: String): Boolean {
        cleanedNamespaces += namespace
        return true
    }
}

/** [StoryWorkspaceApi]-fake: 'prepareert' een workspace onder /tmp zonder echt te clonen. */
class FakeStoryWorkspaceService : StoryWorkspaceApi {
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

/** [CostMonitor]-fake: budgetcheck slaat alleen aan wanneer een test [paused] op true zet. */
class FakeCostMonitor : CostMonitor {
    var paused = false

    override fun applyBudgetTriggers(issue: TrackerIssue): TrackerIssue =
        issue

    override fun checkBudget(issue: TrackerIssue, storyRun: StoryRunRecord): CostMonitorCheckResult =
        CostMonitorCheckResult(storyRun.totalTokens, issue.fields.aiTokenBudget ?: 40000, paused, emptyList())

    override fun checkCompletedRun(storyKey: String, storyRun: StoryRunRecord) = Unit
}

/** [CreditsPauseCoordinator]-fake: de actieve pauze is instelbaar via [pause]; exhausted-meldingen worden gelogd. */
class FakeCreditsPauseCoordinator : CreditsPauseCoordinator {
    var pause: CreditsPause? = null
    val exhaustedStories = mutableListOf<String>()

    override fun activePause(now: OffsetDateTime): CreditsPause? =
        pause

    override fun handleCreditsExhausted(storyKey: String, summaryText: String?) {
        exhaustedStories += storyKey
    }
}

/** [ManualCommandProcessor]-noop: geeft elke issue ongewijzigd terug (geen handmatige commando's). */
class NoopManualCommandProcessor : ManualCommandProcessor {
    override fun apply(issue: TrackerIssue): ManualCommandApplication =
        ManualCommandApplication(issue)
}
