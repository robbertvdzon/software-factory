package nl.vdzon.softwarefactory.testsupport

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.core.contracts.AgentRunRecord
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.CompletedAgentRun
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * In-memory [AgentRunRepository]. Naast het interface biedt [addEnded] een seed-helper om
 * afgeronde runs (outcome/summary/subtaskKey/endedAt) klaar te zetten voor cap- en recovery-tests.
 */
class InMemoryAgentRunRepository : AgentRunRepository {
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

    /** Seed-helper: registreert een al-afgeronde agent-run met de gegeven outcome/summary. */
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
