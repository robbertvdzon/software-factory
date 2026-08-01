package nl.vdzon.softwarefactory.testsupport

import nl.vdzon.softwarefactory.core.contracts.StoryRunRecord
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import java.time.OffsetDateTime

/**
 * In-memory [StoryRunRepository]: één open run per story-key, met oplopende id's.
 * [close] verwijdert de run uit de "open"-index ([runs], zoals `ended_at IS NULL` in de echte DB)
 * en registreert het (id, status)-paar in [closed] — maar de record zelf blijft bewaard in [byId]
 * zodat [latestFor] 'm ná het sluiten nog kan terugvinden (mirrort `JdbcStoryRunRepository.latestFor`,
 * dat ook geen `ended_at`-filter heeft). Zonder die historie zou deze testdouble de SF-1710/SF-1717-
 * regressie (zie [nl.vdzon.softwarefactory.pipeline.service.DeploySubtaskHandler]) niet kunnen testen.
 */
class InMemoryStoryRunRepository : StoryRunRepository {
    private val runs = mutableMapOf<String, StoryRunRecord>()
    private val byId = mutableMapOf<Long, StoryRunRecord>()
    private var nextId = 1L
    val closed = mutableListOf<Pair<Long, String>>()

    override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
        runs.getOrPut(storyKey) { StoryRunRecord(nextId++, storyKey, targetRepo) }.also { byId[it.id] = it }

    /** Testhulp: maakt (of haalt) een run op met een expliciete [startedAt], voor leeftijd-afhankelijke asserts. */
    fun openOrCreate(storyKey: String, targetRepo: String, startedAt: OffsetDateTime): StoryRunRecord =
        runs.getOrPut(storyKey) { StoryRunRecord(nextId++, storyKey, targetRepo, startedAt = startedAt) }.also { byId[it.id] = it }

    override fun latestFor(storyKey: String): StoryRunRecord? =
        byId.values.filter { it.storyKey == storyKey }.maxByOrNull { it.id }

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
        val updated = entry.value.copy(
            branchName = branchName,
            prNumber = prNumber,
            prUrl = prUrl,
            baseBranch = baseBranch,
            branchPrefix = branchPrefix,
            previewUrlTemplate = previewUrlTemplate,
            previewNamespaceTemplate = previewNamespaceTemplate,
            previewDbSecretRecipe = previewDbSecretRecipe,
        )
        entry.setValue(updated)
        byId[storyRunId] = updated
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
        val updated = entry.value.copy(
            workspacePath = workspacePath,
            branchName = branchName,
            baseBranch = baseBranch,
            branchPrefix = branchPrefix,
            previewUrlTemplate = previewUrlTemplate,
            previewNamespaceTemplate = previewNamespaceTemplate,
            previewDbSecretRecipe = previewDbSecretRecipe,
        )
        entry.setValue(updated)
        byId[storyRunId] = updated
    }

    override fun activePullRequests(): List<StoryRunRecord> =
        runs.values.filter { it.prNumber != null }

    override fun activeRuns(): List<StoryRunRecord> =
        runs.values.toList()

    override fun activeRunForRepo(targetRepo: String): StoryRunRecord? =
        runs.values.firstOrNull { it.targetRepo == targetRepo }

    override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) {
        closed += storyRunId to finalStatus
        val entry = runs.entries.first { it.value.id == storyRunId }
        runs.remove(entry.key)
    }
}
