package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.orchestrator.AgentRuntime
import nl.vdzon.softwarefactory.orchestrator.StoryRunRecord
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import nl.vdzon.softwarefactory.orchestrator.StoryWorkspaceApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Ruimt een hele story synchroon en hard op: het YouTrack-issue + z'n subtaken worden
 * permanent verwijderd, plus de branch, workfolder, PR, preview en de story-run-rij.
 *
 * Bedoeld om (test-)stories op te ruimen vanuit het dashboard. Anders dan het
 * comment-gedreven `delete`-commando (dat alleen `(CANCELLED)` markeert en pas door de
 * poller op een getagde work-issue verwerkt wordt), draait dit direct en onafhankelijk
 * van tags of poll-status.
 *
 * Elke stap is best-effort: een mislukte stap wordt gelogd maar blokkeert de rest niet,
 * zodat een story altijd zo volledig mogelijk verdwijnt. Onomkeerbaar.
 */
@Service
class StoryPurgeService(
    private val issueTrackerClient: YouTrackApi,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val pullRequestClient: GitHubApi,
    private val previewApi: PreviewApi,
    private val storyWorkspaceService: StoryWorkspaceApi,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun purgeStory(storyKey: String) {
        logger.info("Purge gestart voor story {}", storyKey)
        val run = activeRun(storyKey)

        step("kill agents", storyKey) { agentRuntime.killForStory(storyKey) }
        step("close PR", storyKey) { closePullRequest(run) }
        step("delete branch", storyKey) { deleteBranch(run) }
        step("cleanup preview", storyKey) { cleanupPreview(run) }
        step("cleanup workspace", storyKey) { storyWorkspaceService.cleanup(storyKey) }
        run?.let { step("delete run", storyKey) { storyRunRepository.delete(it.id) } }

        // YouTrack als laatste: zo blijven branch/run beschikbaar als een eerdere stap die
        // nog nodig had. Eerst de subtaken, dan de story zelf.
        deleteSubtasks(storyKey)
        step("delete story issue", storyKey) { issueTrackerClient.deleteIssue(storyKey) }
        logger.info("Purge afgerond voor story {}", storyKey)
    }

    private fun deleteSubtasks(storyKey: String) {
        val subtasks = runCatching { issueTrackerClient.subtasksOf(storyKey) }
            .getOrElse { exception ->
                logger.warn("Purge: subtaken laden faalde voor {}", storyKey, exception)
                return
            }
        subtasks.forEach { subtask ->
            step("delete subtask ${subtask.key}", storyKey) { issueTrackerClient.deleteIssue(subtask.key) }
        }
        if (subtasks.isNotEmpty()) {
            logger.info("Purge: {} subtaak(en) verwijderd voor story {}", subtasks.size, storyKey)
        }
    }

    private fun activeRun(storyKey: String): StoryRunRecord? =
        runCatching {
            storyRunRepository.activeRuns()
                .filter { it.storyKey == storyKey }
                .maxByOrNull { it.id }
        }.getOrNull()

    private fun closePullRequest(run: StoryRunRecord?) {
        val prNumber = run?.prNumber ?: return
        pullRequestClient.closePullRequest(run.targetRepo, prNumber)
    }

    private fun deleteBranch(run: StoryRunRecord?) {
        val branchName = run?.branchName?.takeIf { it.isNotBlank() } ?: return
        pullRequestClient.deleteBranch(run.targetRepo, branchName)
    }

    private fun cleanupPreview(run: StoryRunRecord?) {
        val namespace = previewApi.render(run?.previewNamespaceTemplate, run?.prNumber) ?: return
        previewApi.cleanup(namespace)
    }

    private fun step(name: String, storyKey: String, action: () -> Unit) {
        runCatching(action).onFailure { exception ->
            logger.warn("Purge-stap '{}' faalde voor {}", name, storyKey, exception)
        }
    }
}
