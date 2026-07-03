package nl.vdzon.softwarefactory.testsupport

import nl.vdzon.softwarefactory.core.StoryRunRecord
import nl.vdzon.softwarefactory.core.StoryRunRepository
import java.time.OffsetDateTime

/**
 * In-memory [StoryRunRepository]: één open run per story-key, met oplopende id's.
 * [close] verwijdert de run en registreert het (id, status)-paar in [closed].
 */
class InMemoryStoryRunRepository : StoryRunRepository {
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
