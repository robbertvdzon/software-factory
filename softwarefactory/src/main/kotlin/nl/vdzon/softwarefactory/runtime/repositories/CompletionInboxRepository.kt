package nl.vdzon.softwarefactory.runtime.repositories

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.contracts.CompletionProgress
import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.models.AgentRunEventPayload
import nl.vdzon.softwarefactory.runtime.models.AcceptedCompletion
import nl.vdzon.softwarefactory.runtime.models.CompletionStepState
import nl.vdzon.softwarefactory.runtime.models.DurableCompletion
import nl.vdzon.softwarefactory.runtime.errors.CompletionPayloadConflictException
import nl.vdzon.softwarefactory.runtime.errors.CompletionPayloadRejectedException
import nl.vdzon.softwarefactory.runtime.types.CompletionStatus
import nl.vdzon.softwarefactory.runtime.types.CompletionStep
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

interface CompletionInboxRepository {
    fun accept(request: AgentRunCompleteRequest): AcceptedCompletion?
    fun storeValidatedPayload(id: Long, request: AgentRunCompleteRequest): AcceptedCompletion
    fun get(id: Long): DurableCompletion?
    fun steps(id: Long): List<CompletionStepState>
    fun claimStep(id: Long, step: CompletionStep, owner: String, lease: Duration): CompletionStepState?
    fun completeStep(id: Long, step: CompletionStep)
    fun failStep(id: Long, step: CompletionStep, error: Throwable, maxAttempts: Int, backoff: Duration)
    fun dueCompletionIds(limit: Int = 25): List<Long>
    fun manualRequeue(id: Long, step: CompletionStep, requestedBy: String, reason: String)
    fun purgePayloads(retention: Duration): Int
}

@Repository
class JdbcCompletionInboxRepository(
    private val jdbc: JdbcTemplate,
    private val secrets: FactorySecrets,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : CompletionInboxRepository {
    private val schema get() = secrets.factoryDatabaseSchema
    private val logger = LoggerFactory.getLogger(JdbcCompletionInboxRepository::class.java)

    override fun accept(request: AgentRunCompleteRequest): AcceptedCompletion? {
        val truncated = truncateOversizedEvents(capEventCount(request))
        val payload = objectMapper.writeValueAsString(truncated)
        validateCompletion(truncated, payload.toByteArray(StandardCharsets.UTF_8).size)
        val hash = payload.sha256()
        val run = findAcceptableRun(jdbc, schema, truncated.containerName) ?: return null
        insertCompletion(jdbc, schema, run, CompletionEnvelope(truncated, payload, hash))
        val completion = requireNotNull(findCompletionByRun(jdbc, schema, run.agentRunId))
        if (completion.payloadHash != hash) {
            throw CompletionPayloadConflictException(
                "Completion ${completion.id} for agent run ${run.agentRunId} already has a different payload hash",
            )
        }
        ensureSteps(jdbc, schema, completion.id, OffsetDateTime.now(clock))
        val stored = requireNotNull(get(completion.id))
        return AcceptedCompletion(
            stored,
            objectMapper.readValue<AgentRunCompleteRequest>(requireNotNull(stored.payloadJson)),
        )
    }

    /**
     * SF-1234: een run met méér dan [MAX_COLLECTION_ENTRIES] events (lange developer-runs met
     * honderden turns) mag de héle completion niet laten afwijzen — anders retryt de poller
     * eeuwig dezelfde te-grote payload en blijft de story voor altijd op `developing` hangen.
     * Laat de oudste events vallen (behoud de meest recente, meest relevante turns) en zet één
     * marker-event op de eerste plek i.p.v. de request te verwerpen. `knowledgeUpdates`/`subtasks`
     * worden bewust NIET zo afgekapt: die bevatten functioneel gedrag (subtaak-aanmaak,
     * kennis-updates) dat niet stilzwijgend verloren mag gaan; een run die dáár de limiet
     * overschrijdt blijft via [validateCompletion] afgewezen.
     */
    private fun capEventCount(request: AgentRunCompleteRequest): AgentRunCompleteRequest {
        val overflow = request.events.size - MAX_COLLECTION_ENTRIES
        if (overflow <= 0) return request
        val dropped = overflow + 1
        val kept = request.events.takeLast(MAX_COLLECTION_ENTRIES - 1)
        val marker = AgentRunEventPayload(
            kind = "truncated-events",
            payload = "...[afgekapt: $dropped oudste events weggelaten, origineel ${request.events.size} events]",
        )
        logger.warn(
            "Dropped {} oldest event(s) (of {} total) for storyKey={} containerName={} to stay within the {} entry limit",
            dropped, request.events.size, request.storyKey, request.containerName, MAX_COLLECTION_ENTRIES,
        )
        return request.copy(events = listOf(marker) + kept)
    }

    /**
     * SF-1214: een individuele event-payload > [MAX_EVENT_BYTES] mag de héle completion niet
     * meer laten afwijzen (een klare tester-/developer-run bleef anders eeuwig op
     * `testing`/`developing` hangen). Kap zo'n payload af tot ≤ [MAX_EVENT_BYTES] (incl. marker,
     * geldige UTF-8) i.p.v. de request te verwerpen; overige events blijven ongewijzigd.
     */
    private fun truncateOversizedEvents(request: AgentRunCompleteRequest): AgentRunCompleteRequest {
        var truncatedCount = 0
        var truncatedOriginalBytes = 0L
        val events = request.events.map { event ->
            val size = event.payload.toByteArray(StandardCharsets.UTF_8).size
            if (size <= MAX_EVENT_BYTES) return@map event
            truncatedCount++
            truncatedOriginalBytes += size
            event.copy(payload = truncateEventPayload(event.payload, size))
        }
        if (truncatedCount == 0) return request
        logger.warn(
            "Truncated {} oversized event payload(s) ({} bytes original) for storyKey={} containerName={}",
            truncatedCount, truncatedOriginalBytes, request.storyKey, request.containerName,
        )
        return request.copy(events = events)
    }

    override fun storeValidatedPayload(id: Long, request: AgentRunCompleteRequest): AcceptedCompletion {
        jdbc.update(
            """
            UPDATE $schema.agent_run_completions
            SET payload_json = ?::jsonb, payload_validated = true, updated_at = ?
            WHERE id = ? AND status <> 'COMPLETED'
            """.trimIndent(),
            objectMapper.writeValueAsString(request), OffsetDateTime.now(clock), id,
        )
        val completion = requireNotNull(get(id))
        return AcceptedCompletion(completion, request)
    }

    override fun get(id: Long): DurableCompletion? = jdbc.query(
        """
        SELECT id, agent_run_id, story_run_id, story_key, container_name, workspace_path,
               payload_hash, payload_json::text AS payload_json, payload_validated, status
        FROM $schema.agent_run_completions
        WHERE id = ?
        """.trimIndent(),
        { rs, _ -> rs.toCompletion() }, id,
    ).firstOrNull()

    override fun steps(id: Long): List<CompletionStepState> = jdbc.query(
        """
        SELECT step_key, status, attempts, next_attempt_at, lease_until, last_error
        FROM $schema.agent_run_completion_steps
        WHERE completion_id = ?
        ORDER BY step_order
        """.trimIndent(),
        { rs, _ -> rs.toStepState() }, id,
    )

    override fun claimStep(id: Long, step: CompletionStep, owner: String, lease: Duration): CompletionStepState? {
        val now = OffsetDateTime.now(clock)
        val claimed = jdbc.query(
            """
            UPDATE $schema.agent_run_completion_steps
            SET status = 'IN_PROGRESS', attempts = attempts + 1, lease_owner = ?, lease_until = ?,
                started_at = COALESCE(started_at, ?), updated_at = ?, last_error = NULL
            WHERE completion_id = ? AND step_key = ?
              AND (
                (status IN ('PENDING', 'FAILED_RETRYABLE') AND next_attempt_at <= ?)
                OR (status = 'IN_PROGRESS' AND lease_until < ?)
              )
            RETURNING step_key, status, attempts, next_attempt_at, lease_until, last_error
            """.trimIndent(),
            { rs, _ -> rs.toStepState() }, owner, now.plus(lease), now, now, id, step.name, now, now,
        ).firstOrNull()
        if (claimed != null) {
            jdbc.update(
                "UPDATE $schema.agent_run_completions SET status = 'IN_PROGRESS', updated_at = ? WHERE id = ? AND status <> 'COMPLETED'",
                now, id,
            )
        }
        return claimed
    }

    override fun completeStep(id: Long, step: CompletionStep) {
        val now = OffsetDateTime.now(clock)
        jdbc.update(
            """
            UPDATE $schema.agent_run_completion_steps
            SET status = 'COMPLETED', completed_at = ?, lease_owner = NULL, lease_until = NULL,
                last_error = NULL, updated_at = ?
            WHERE completion_id = ? AND step_key = ? AND status = 'IN_PROGRESS'
            """.trimIndent(),
            now, now, id, step.name,
        )
        val remaining = requireNotNull(jdbc.queryForObject(
            "SELECT count(*) FROM $schema.agent_run_completion_steps WHERE completion_id = ? AND status <> 'COMPLETED'",
            Int::class.java, id,
        ))
        if (remaining == 0) {
            jdbc.update(
                """
                UPDATE $schema.agent_run_completions
                SET status = 'COMPLETED', completed_at = ?, updated_at = ?, last_error = NULL
                WHERE id = ?
                """.trimIndent(),
                now, now, id,
            )
        }
    }

    override fun failStep(id: Long, step: CompletionStep, error: Throwable, maxAttempts: Int, backoff: Duration) {
        val now = OffsetDateTime.now(clock)
        val attempts = steps(id).first { it.step == step }.attempts
        val permanent = attempts >= maxAttempts
        val status = if (permanent) CompletionStatus.FAILED_PERMANENT else CompletionStatus.FAILED_RETRYABLE
        val delay = backoff.multipliedBy(1L shl (attempts - 1).coerceIn(0, 10))
        val message = error.message.orEmpty().ifBlank { error.javaClass.simpleName }.take(2000)
        jdbc.update(
            """
            UPDATE $schema.agent_run_completion_steps
            SET status = ?, next_attempt_at = ?, lease_owner = NULL, lease_until = NULL,
                last_error = ?, updated_at = ?
            WHERE completion_id = ? AND step_key = ? AND status = 'IN_PROGRESS'
            """.trimIndent(),
            status.name, now.plus(delay), message, now, id, step.name,
        )
        jdbc.update(
            "UPDATE $schema.agent_run_completions SET status = ?, last_error = ?, updated_at = ? WHERE id = ?",
            status.name, message, now, id,
        )
    }

    override fun dueCompletionIds(limit: Int): List<Long> {
        val now = OffsetDateTime.now(clock)
        return jdbc.queryForList(
            """
            SELECT DISTINCT c.id
            FROM $schema.agent_run_completions c
            JOIN $schema.agent_run_completion_steps s ON s.completion_id = c.id
            WHERE c.status <> 'COMPLETED' AND c.payload_json IS NOT NULL
              AND (
                (s.status IN ('PENDING', 'FAILED_RETRYABLE') AND s.next_attempt_at <= ?)
                OR (s.status = 'IN_PROGRESS' AND s.lease_until < ?)
              )
            ORDER BY c.id
            LIMIT ?
            """.trimIndent(),
            Long::class.java, now, now, limit,
        )
    }

    override fun manualRequeue(id: Long, step: CompletionStep, requestedBy: String, reason: String) {
        require(requestedBy.isNotBlank()) { "requestedBy is required" }
        require(reason.isNotBlank()) { "reason is required" }
        val previous = steps(id).firstOrNull { it.step == step }?.status
            ?: error("Unknown completion step $id/${step.name}")
        require(previous in setOf(CompletionStatus.FAILED_RETRYABLE, CompletionStatus.FAILED_PERMANENT)) {
            "Only failed completion steps can be requeued"
        }
        val now = OffsetDateTime.now(clock)
        jdbc.update(
            """
            INSERT INTO $schema.agent_run_completion_requeues
              (completion_id, step_key, requested_by, reason, previous_status)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            id, step.name, requestedBy, reason.take(2000), previous.name,
        )
        jdbc.update(
            """
            UPDATE $schema.agent_run_completion_steps
            SET status = 'PENDING', attempts = 0, next_attempt_at = ?, lease_owner = NULL,
                lease_until = NULL, last_error = NULL, updated_at = ?
            WHERE completion_id = ? AND step_key = ?
            """.trimIndent(),
            now, now, id, step.name,
        )
        jdbc.update(
            "UPDATE $schema.agent_run_completions SET status = 'PENDING', last_error = NULL, updated_at = ? WHERE id = ?",
            now, id,
        )
    }

    override fun purgePayloads(retention: Duration): Int {
        val now = OffsetDateTime.now(clock)
        return jdbc.update(
            """
            UPDATE $schema.agent_run_completions
            SET payload_json = NULL, payload_purged_at = ?, updated_at = ?
            WHERE status = 'COMPLETED' AND completed_at < ? AND payload_json IS NOT NULL
            """.trimIndent(),
            now, now, now.minus(retention),
        )
    }

}

@Repository
class JdbcCompletionProgress(
    private val jdbc: JdbcTemplate,
    private val secrets: FactorySecrets,
) : CompletionProgress {
    override fun hasUnfinishedForStory(storyKey: String): Boolean = requireNotNull(
        jdbc.queryForObject(
            """
            SELECT EXISTS(
              SELECT 1 FROM ${secrets.factoryDatabaseSchema}.agent_run_completions
              WHERE story_key = ? AND status <> 'COMPLETED'
            )
            """.trimIndent(),
            Boolean::class.java,
            storyKey,
        ),
    )
}

private const val MAX_CONTAINER_NAME = 255
private const val MAX_STORY_KEY = 120
private const val MAX_SUMMARY_BYTES = 200_000
private const val MAX_COLLECTION_ENTRIES = 1_000
private const val MAX_EVENT_BYTES = 262_144
private const val MAX_PAYLOAD_BYTES = 8_388_608

private data class CompletionRunIdentity(
    val agentRunId: Long,
    val storyRunId: Long,
    val workspacePath: String?,
)

private data class CompletionEnvelope(
    val request: AgentRunCompleteRequest,
    val payload: String,
    val hash: String,
)

private fun findAcceptableRun(jdbc: JdbcTemplate, schema: String, containerName: String): CompletionRunIdentity? =
    jdbc.query(
        """
        SELECT ar.id AS agent_run_id, ar.story_run_id, ar.workspace_path
        FROM $schema.agent_runs ar
        WHERE ar.container_name = ?
          AND (
            ar.ended_at IS NULL
            OR EXISTS (SELECT 1 FROM $schema.agent_run_completions c WHERE c.agent_run_id = ar.id)
          )
        ORDER BY ar.id DESC
        LIMIT 1
        """.trimIndent(),
        { rs, _ ->
            CompletionRunIdentity(
                rs.getLong("agent_run_id"),
                rs.getLong("story_run_id"),
                rs.getString("workspace_path"),
            )
        },
        containerName,
    ).firstOrNull()

private fun insertCompletion(
    jdbc: JdbcTemplate,
    schema: String,
    run: CompletionRunIdentity,
    envelope: CompletionEnvelope,
) {
    jdbc.update(
        """
        INSERT INTO $schema.agent_run_completions
          (agent_run_id, story_run_id, story_key, container_name, workspace_path, payload_json, payload_hash)
        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
        ON CONFLICT (agent_run_id) DO NOTHING
        """.trimIndent(),
        run.agentRunId,
        run.storyRunId,
        envelope.request.storyKey,
        envelope.request.containerName,
        run.workspacePath,
        envelope.payload,
        envelope.hash,
    )
}

private fun findCompletionByRun(jdbc: JdbcTemplate, schema: String, agentRunId: Long): DurableCompletion? =
    jdbc.query(
        """
        SELECT id, agent_run_id, story_run_id, story_key, container_name, workspace_path,
               payload_hash, payload_json::text AS payload_json, payload_validated, status
        FROM $schema.agent_run_completions
        WHERE agent_run_id = ?
        """.trimIndent(),
        { rs, _ -> rs.toCompletion() },
        agentRunId,
    ).firstOrNull()

private fun ensureSteps(jdbc: JdbcTemplate, schema: String, completionId: Long, now: OffsetDateTime) {
    CompletionStep.entries.forEachIndexed { index, step ->
        jdbc.update(
            """
            INSERT INTO $schema.agent_run_completion_steps
              (completion_id, step_key, step_order, next_attempt_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (completion_id, step_key) DO NOTHING
            """.trimIndent(),
            completionId,
            step.name,
            index,
            now,
        )
    }
}

private fun validateCompletion(request: AgentRunCompleteRequest, encodedSize: Int) {
    val error = when {
        request.containerName.isBlank() || request.containerName.length > MAX_CONTAINER_NAME ->
            "containerName must contain 1..$MAX_CONTAINER_NAME characters"
        request.storyKey.isBlank() || request.storyKey.length > MAX_STORY_KEY ->
            "storyKey must contain 1..$MAX_STORY_KEY characters"
        request.summaryText.orEmpty().toByteArray(StandardCharsets.UTF_8).size > MAX_SUMMARY_BYTES ->
            "summaryText exceeds $MAX_SUMMARY_BYTES bytes"
        listOf(request.events.size, request.knowledgeUpdates.size, request.subtasks.size)
            .any { it > MAX_COLLECTION_ENTRIES } -> "completion collection exceeds $MAX_COLLECTION_ENTRIES entries"
        encodedSize > MAX_PAYLOAD_BYTES -> "completion payload exceeds 1 MiB"
        else -> null
    }
    if (error != null) throw CompletionPayloadRejectedException(error)
}

/**
 * Kapt [payload] af tot ≤ [MAX_EVENT_BYTES] bytes inclusief een zichtbare afkap-marker die het
 * originele aantal bytes vermeldt. De marker-tekst hoeft geen geldige JSON te blijven (analoog aan
 * de bestaande `eventsForStory`-afkap in de dashboard-bridge, die de frontend al gracieus opvangt).
 */
private fun truncateEventPayload(payload: String, originalBytes: Int): String {
    val marker = "...[afgekapt: origineel $originalBytes bytes]"
    val markerBytes = marker.toByteArray(StandardCharsets.UTF_8).size
    val budget = (MAX_EVENT_BYTES - markerBytes).coerceAtLeast(0)
    return truncateToUtf8ByteBudget(payload, budget) + marker
}

/** Kapt [text] af tot hooguit [budgetBytes] UTF-8-bytes, zonder een multi-byte teken te splitsen. */
private fun truncateToUtf8ByteBudget(text: String, budgetBytes: Int): String {
    if (budgetBytes <= 0) return ""
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size <= budgetBytes) return text
    var cut = budgetBytes
    while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
    return String(bytes, 0, cut, StandardCharsets.UTF_8)
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun ResultSet.toCompletion() = DurableCompletion(
    id = getLong("id"),
    agentRunId = getLong("agent_run_id"),
    storyRunId = getLong("story_run_id"),
    storyKey = getString("story_key"),
    containerName = getString("container_name"),
    workspacePath = getString("workspace_path"),
    payloadHash = getString("payload_hash"),
    payloadJson = getString("payload_json"),
    payloadValidated = getBoolean("payload_validated"),
    status = CompletionStatus.valueOf(getString("status")),
)

private fun ResultSet.toStepState() = CompletionStepState(
    step = CompletionStep.valueOf(getString("step_key")),
    status = CompletionStatus.valueOf(getString("status")),
    attempts = getInt("attempts"),
    nextAttemptAt = getObject("next_attempt_at", OffsetDateTime::class.java),
    leaseUntil = getObject("lease_until", OffsetDateTime::class.java),
    lastError = getString("last_error"),
)
