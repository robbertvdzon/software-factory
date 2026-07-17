package nl.vdzon.softwarefactory.tracker.clients

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.FactoryStateChangedEvent
import nl.vdzon.softwarefactory.core.contracts.FinishedStatus
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.contracts.SubtaskSpec
import nl.vdzon.softwarefactory.core.contracts.TrackerAttachment
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.core.contracts.TrackerProject
import nl.vdzon.softwarefactory.tracker.errors.TrackerApiException
import nl.vdzon.softwarefactory.tracker.errors.TrackerIssueNotFoundException
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import nl.vdzon.softwarefactory.tracker.repositories.ProcessedCommentStore
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * [TrackerCapabilities]-implementatie tegen de factory's eigen lokale Postgres, zie [TrackerClientConfiguration].
 *
 * Eén unified `issues`-tabel (stories én subtaken, onderscheiden via `parent_key`) — zie migratie
 * `V15__tracker_issues.sql`. Comment-verwerkingsmarkers hergebruiken de al bestaande, al actief
 * gebruikte [ProcessedCommentStore] (niet opnieuw gebouwd). Attachments (tester-screenshots) worden
 * als losse bestanden op de laptop-schijf weggeschreven onder `FactorySecrets.trackerAttachmentsDir`,
 * met alleen metadata + het lokale pad in de DB.
 */
class PostgresTrackerClient(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
    private val processedCommentStore: ProcessedCommentStore,
    private val eventPublisher: ApplicationEventPublisher? = null,
) : TrackerCapabilities {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val schema get() = factorySecrets.factoryDatabaseSchema
    private val attachmentsRoot: Path by lazy { Path.of(factorySecrets.trackerAttachmentsDir).toAbsolutePath() }
    private val issueKeySequence = PostgresIssueKeySequence(jdbcTemplate, factorySecrets)

    /** Wekt de poller direct na een succesvolle schrijf; falen mag de write nooit laten mislukken. */
    private fun publishStateChanged(origin: String) {
        runCatching { eventPublisher?.publishEvent(FactoryStateChangedEvent("tracker-write:$origin")) }
            .onFailure { logger.debug("Kon FactoryStateChangedEvent niet publiceren (genegeerd).", it) }
    }

    override fun ensureConfiguredProjects(): List<TrackerProject> {
        val configured = factorySecrets.trackerProjects
        val keys = configured.ifEmpty {
            jdbcTemplate.query(
                "SELECT DISTINCT project_key FROM $schema.issues ORDER BY project_key",
            ) { rs, _ -> rs.getString("project_key") }
        }
        if (keys.isEmpty()) {
            throw TrackerApiException(
                "No Software Factory tracker projects configured. Set SF_TRACKER_PROJECTS, " +
                    "or create at least one story so a project becomes known.",
            )
        }
        return keys.map { TrackerProject(id = it, key = it, name = it) }
    }

    override fun findWorkIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> =
        findAiIssues(maxResults = maxResults, includeFinished = includeFinished)

    override fun findAiIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> {
        // Fail fast/luid bij een echt foute config i.p.v. stilzwijgend een lege lijst
        // (de interface-default).
        ensureConfiguredProjects()
        val configuredProjects = factorySecrets.trackerProjects
        val projectFilter = if (configuredProjects.isEmpty()) {
            ""
        } else {
            "AND project_key IN (${configuredProjects.joinToString(",") { "?" }})"
        }
        val baseWhere = """
            WHERE ai_supplier IS NOT NULL AND lower(ai_supplier) NOT IN ('', 'none')
            $projectFilter
        """.trimIndent()
        // SF-862: naast de top-N by updated_at ook alle (sub)taken die op een mens wachten
        // (niet-terminale subtask_phase) altijd meenemen, zodat ze nooit buiten de LIMIT vallen.
        // De niet-terminale subset blijft expliciet begrensd via PENDING_SUBSET_LIMIT.
        val terminalPhases = SubtaskPhase.entries.filter { it.isTerminal }.map { it.trackerValue }
        val terminalPlaceholders = terminalPhases.joinToString(",") { "?" }
        // De top-N-("recent")-tak sluit afgeronde issues uit voor de poller (includeFinished=false,
        // de default), zodat de poll-logging alleen nog actief werk toont. Nu veilig sinds SF-903 de
        // status pas écht bij afronding zet (en niet meer telkens opnieuw bumpt). Het
        // dashboard-stories-overzicht wil juist alle stories zien (incl. de "Klaar"-tab) en geeft
        // includeFinished=true door — dat pad blijft ongefilterd, net als vóór SF-918.
        val finishedStatuses = FinishedStatus.VALUES.toList()
        val doneFilter = if (includeFinished) {
            ""
        } else {
            "AND (status IS NULL OR lower(status) NOT IN (${finishedStatuses.joinToString(",") { "?" }}))"
        }
        val sql = """
            SELECT * FROM (
                (${issueSelect()}
                $baseWhere
                $doneFilter
                ORDER BY updated_at DESC
                LIMIT ?)
                UNION
                (${issueSelect()}
                $baseWhere
                AND subtask_phase IS NOT NULL AND subtask_phase NOT IN ($terminalPlaceholders)
                LIMIT ?)
            ) AS combined
            ORDER BY updated_at DESC
        """.trimIndent()
        val args = mutableListOf<Any?>().apply {
            addAll(configuredProjects)
            if (!includeFinished) addAll(finishedStatuses)
            add(maxResults.coerceAtLeast(1))
            addAll(configuredProjects)
            addAll(terminalPhases)
            add(PENDING_SUBSET_LIMIT)
        }
        return jdbcTemplate.query(sql, { rs, _ -> mapRow(rs) }, *args.toTypedArray())
            .map { withComments(it) }
    }

    override fun getIssue(issueKey: String): TrackerIssue {
        val issue = jdbcTemplate.query(
            "${issueSelect()} WHERE issue_key = ?",
            { rs, _ -> mapRow(rs) },
            issueKey,
        ).firstOrNull() ?: throw TrackerIssueNotFoundException(issueKey)
        return withComments(issue)
    }

    override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
        if (update.values.isEmpty()) {
            return
        }
        val setClauses = mutableListOf("updated_at = now()")
        val args = mutableListOf<Any?>()
        update.values.forEach { (field, value) ->
            setClauses += "${columnFor(field)} = ?"
            args += columnValue(field, value)
        }
        args += issueKey
        // No-op-guard (SF-904): sla de write (en dus het event) over als alle opgegeven velden al
        // gelijk zijn aan de huidige rij-waarden, anders bumpt `updated_at` eeuwig door en wekt de
        // poller zichzelf op via findAiIssues (SF-903).
        val changeClauses = update.values.keys.joinToString(" OR ") { "${columnFor(it)} IS DISTINCT FROM ?" }
        update.values.forEach { (field, value) -> args += columnValue(field, value) }
        val updated = jdbcTemplate.update(
            "UPDATE $schema.issues SET ${setClauses.joinToString(", ")} WHERE issue_key = ? AND ($changeClauses)",
            *args.toTypedArray(),
        )
        if (updated > 0) {
            publishStateChanged("updateIssueFields:$issueKey")
        }
    }

    override fun createSubtask(parentKey: String, spec: SubtaskSpec, supplier: String?): TrackerIssue {
        val projectKey = parentKey.substringBefore('-', missingDelimiterValue = "")
        val subtaskKey = issueKeySequence.next(projectKey)
        val effectiveSupplier = supplier?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        jdbcTemplate.update(
            """
            INSERT INTO $schema.issues
                (issue_key, project_key, summary, description, parent_key, type, subtask_type, ai_supplier, ai_model, ai_reasoning_effort)
            VALUES (?, ?, ?, ?, ?, 'Task', ?, ?, ?, ?)
            """.trimIndent(),
            subtaskKey,
            projectKey,
            spec.title,
            spec.description?.takeIf { it.isNotBlank() },
            parentKey,
            spec.type.trackerValue,
            effectiveSupplier,
            spec.model?.takeIf { it.isNotBlank() },
            spec.effort?.takeIf { it.isNotBlank() },
        )
        publishStateChanged("createSubtask:$subtaskKey")
        return getIssue(subtaskKey)
    }

    override fun createStory(
        projectKey: String,
        title: String,
        description: String?,
        repo: String?,
        aiSupplier: String?,
        aiModel: String?,
        start: Boolean,
        silent: Boolean,
    ): TrackerIssue {
        val storyKey = issueKeySequence.next(projectKey)
        val effectiveSupplier = aiSupplier?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        jdbcTemplate.update(
            """
            INSERT INTO $schema.issues
                (issue_key, project_key, summary, description, type, repo, ai_supplier, ai_model, silent, story_phase)
            VALUES (?, ?, ?, ?, 'User Story', ?, ?, ?, ?, ?)
            """.trimIndent(),
            storyKey,
            projectKey,
            title,
            description?.takeIf { it.isNotBlank() },
            repo?.takeIf { it.isNotBlank() },
            effectiveSupplier,
            aiModel?.takeIf { it.isNotBlank() },
            silent,
            if (start) "start" else null,
        )
        publishStateChanged("createStory:$storyKey")
        return getIssue(storyKey)
    }

    override fun existingSubtaskTitles(parentKey: String): Set<String> =
        jdbcTemplate.query(
            "SELECT summary FROM $schema.issues WHERE parent_key = ?",
            { rs, _ -> rs.getString("summary") },
            parentKey,
        ).toSet()

    override fun parentStoryKey(subtaskKey: String): String? =
        jdbcTemplate.query(
            "SELECT parent_key FROM $schema.issues WHERE issue_key = ?",
            { rs, _ -> rs.getString("parent_key") },
            subtaskKey,
        ).firstOrNull()

    override fun subtasksOf(parentKey: String): List<TrackerIssue> =
        // Aanmaakvolgorde = insertievolgorde = id ASC.
        jdbcTemplate.query(
            "${issueSelect()} WHERE parent_key = ? ORDER BY id ASC",
            { rs, _ -> mapRow(rs) },
            parentKey,
        ).map { withComments(it) }

    override fun updateIssueSummary(issueKey: String, summary: String) {
        jdbcTemplate.update(
            "UPDATE $schema.issues SET summary = ?, updated_at = now() WHERE issue_key = ?",
            summary,
            issueKey,
        )
        publishStateChanged("updateIssueSummary:$issueKey")
    }

    override fun updateIssueDescription(issueKey: String, description: String) {
        jdbcTemplate.update(
            "UPDATE $schema.issues SET description = ?, updated_at = now() WHERE issue_key = ?",
            description,
            issueKey,
        )
        publishStateChanged("updateIssueDescription:$issueKey")
    }

    override fun transitionIssue(issueKey: String, statusName: String) {
        // No-op-guard (SF-904): zie updateIssueFields hierboven.
        val updated = jdbcTemplate.update(
            "UPDATE $schema.issues SET status = ?, updated_at = now() WHERE issue_key = ? AND status IS DISTINCT FROM ?",
            statusName,
            issueKey,
            statusName,
        )
        if (updated > 0) {
            publishStateChanged("transitionIssue:$issueKey")
        }
    }

    override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
        postComment(issueKey, "${role.commentPrefix} $message")

    override fun postComment(issueKey: String, message: String): TrackerComment {
        val comment = jdbcTemplate.query(
            """
            INSERT INTO $schema.issue_comments (issue_key, author_account_id, author_display_name, body)
            VALUES (?, ?, ?, ?)
            RETURNING id, created_at
            """.trimIndent(),
            { rs, _ ->
                TrackerComment(
                    id = rs.getLong("id").toString(),
                    authorAccountId = COMMENT_AUTHOR_ACCOUNT,
                    authorDisplayName = COMMENT_AUTHOR_DISPLAY_NAME,
                    body = message,
                    created = rs.getObject("created_at", OffsetDateTime::class.java),
                )
            },
            issueKey,
            COMMENT_AUTHOR_ACCOUNT,
            COMMENT_AUTHOR_DISPLAY_NAME,
            message,
        ).first()
        publishStateChanged("postComment:$issueKey")
        return comment
    }

    override fun listIssueAttachments(issueKey: String): List<TrackerAttachment> =
        jdbcTemplate.query(
            """
            SELECT id, name, mime_type, size_bytes, created_at
            FROM $schema.issue_attachments
            WHERE issue_key = ?
            ORDER BY id
            """.trimIndent(),
            { rs, _ ->
                TrackerAttachment(
                    id = rs.getLong("id").toString(),
                    name = rs.getString("name"),
                    // Geen echte URL — downloadAttachmentBytes() zoekt op id, niet op url.
                    url = null,
                    mimeType = rs.getString("mime_type"),
                    size = (rs.getObject("size_bytes") as Number?)?.toLong(),
                    created = rs.getObject("created_at", OffsetDateTime::class.java)?.toInstant()?.toEpochMilli(),
                )
            },
            issueKey,
        )

    override fun uploadIssueAttachment(issueKey: String, name: String, mimeType: String, bytes: ByteArray): TrackerAttachment {
        val dir = attachmentsRoot.resolve(issueKey)
        Files.createDirectories(dir)
        val target = dir.resolve(name)
        Files.write(target, bytes)
        return jdbcTemplate.query(
            """
            INSERT INTO $schema.issue_attachments (issue_key, name, mime_type, size_bytes, local_path)
            VALUES (?, ?, ?, ?, ?)
            RETURNING id, created_at
            """.trimIndent(),
            { rs, _ ->
                TrackerAttachment(
                    id = rs.getLong("id").toString(),
                    name = name,
                    url = null,
                    mimeType = mimeType,
                    size = bytes.size.toLong(),
                    created = rs.getObject("created_at", OffsetDateTime::class.java)?.toInstant()?.toEpochMilli(),
                )
            },
            issueKey,
            name,
            mimeType,
            bytes.size.toLong(),
            target.toString(),
        ).first()
    }

    override fun downloadAttachmentBytes(attachment: TrackerAttachment): ByteArray? {
        val id = attachment.id.toLongOrNull() ?: return null
        val path = jdbcTemplate.query(
            "SELECT local_path FROM $schema.issue_attachments WHERE id = ?",
            { rs, _ -> rs.getString("local_path") },
            id,
        ).firstOrNull() ?: return null
        // Soft-fail: null i.p.v. gooien, zodat callers
        // (Telegram-melding) netjes kunnen degraderen.
        return runCatching { Files.readAllBytes(Path.of(path)) }.getOrNull()
    }

    override fun deleteIssueAttachment(issueKey: String, attachmentId: String) {
        val id = attachmentId.toLongOrNull() ?: return
        val path = jdbcTemplate.query(
            "SELECT local_path FROM $schema.issue_attachments WHERE id = ? AND issue_key = ?",
            { rs, _ -> rs.getString("local_path") },
            id,
            issueKey,
        ).firstOrNull()
        jdbcTemplate.update(
            "DELETE FROM $schema.issue_attachments WHERE id = ? AND issue_key = ?",
            id,
            issueKey,
        )
        path?.let { runCatching { Files.deleteIfExists(Path.of(it)) } }
    }

    override fun deleteAgentComments(issueKey: String): Int {
        // Filteren via de bestaande TrackerComment.isAgentComment (TrackerCommentParser) i.p.v.
        // de prefix-regel een tweede keer in SQL te implementeren — voorkomt drift.
        val agentCommentIds = fetchComments(issueKey).filter { it.isAgentComment }.map { it.id.toLong() }
        if (agentCommentIds.isEmpty()) {
            return 0
        }
        val placeholders = agentCommentIds.joinToString(",") { "?" }
        val args: Array<Any> = (listOf<Any>(issueKey) + agentCommentIds).toTypedArray()
        jdbcTemplate.update(
            "DELETE FROM $schema.issue_comments WHERE issue_key = ? AND id IN ($placeholders)",
            *args,
        )
        return agentCommentIds.size
    }

    override fun deleteIssue(issueKey: String) {
        jdbcTemplate.update("DELETE FROM $schema.issues WHERE issue_key = ?", issueKey)
    }

    override fun hasProcessedCommentMarker(issueKey: String, commentId: String, role: AgentRole): Boolean =
        processedCommentStore.isProcessed(issueKey, commentId, role)

    override fun markCommentProcessed(issueKey: String, commentId: String, role: AgentRole): Boolean {
        processedCommentStore.markProcessed(issueKey, commentId, role)
        return true
    }

    private fun issueSelect(): String = "SELECT $ISSUE_COLUMNS FROM $schema.issues"

    private fun fetchComments(issueKey: String): List<TrackerComment> =
        jdbcTemplate.query(
            """
            SELECT id, author_account_id, author_display_name, body, created_at
            FROM $schema.issue_comments
            WHERE issue_key = ?
            ORDER BY id
            """.trimIndent(),
            { rs, _ ->
                TrackerComment(
                    id = rs.getLong("id").toString(),
                    authorAccountId = rs.getString("author_account_id"),
                    authorDisplayName = rs.getString("author_display_name"),
                    body = rs.getString("body"),
                    created = rs.getObject("created_at", OffsetDateTime::class.java),
                )
            },
            issueKey,
        )

    private fun withComments(issue: TrackerIssue): TrackerIssue =
        issue.copy(comments = fetchComments(issue.key))

    private fun mapRow(rs: ResultSet): TrackerIssue =
        TrackerIssue(
            key = rs.getString("issue_key"),
            summary = rs.getString("summary"),
            description = rs.getString("description"),
            status = rs.getString("status") ?: "",
            fields = TrackerIssueFields(
                targetRepo = null,
                repo = rs.getString("repo"),
                aiSupplier = rs.getString("ai_supplier"),
                autoApprove = rs.getBoolean("auto_approve"),
                aiPhase = rs.getString("ai_phase"),
                aiLevel = (rs.getObject("ai_level") as Number?)?.toInt(),
                aiMaxDeveloperLoopbacks = (rs.getObject("ai_max_developer_loopbacks") as Number?)?.toInt(),
                aiMaxTestChainResets = (rs.getObject("ai_max_test_chain_resets") as Number?)?.toInt(),
                aiTokenBudget = (rs.getObject("ai_token_budget") as Number?)?.toLong(),
                aiTokensUsed = (rs.getObject("ai_tokens_used") as Number?)?.toLong(),
                agentStartedAt = rs.getObject("agent_started_at", OffsetDateTime::class.java),
                paused = rs.getBoolean("paused"),
                silent = rs.getBoolean("silent"),
                error = rs.getString("error"),
                type = rs.getString("type"),
                subtaskType = rs.getString("subtask_type"),
                aiModel = rs.getString("ai_model"),
                aiReasoningEffort = rs.getString("ai_reasoning_effort"),
                storyPhase = rs.getString("story_phase"),
                subtaskPhase = rs.getString("subtask_phase"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
            ),
            comments = emptyList(),
            projectKey = rs.getString("project_key"),
            parentKey = rs.getString("parent_key"),
        )

    // Zelfde opsplitsing als TrackerIssueFields.applying (WorkflowModels.kt): de OUTER when
    // blijft exhaustief over alle TrackerField-waarden (compiler dwingt een nieuwe kolom af),
    // opgedeeld in groepen om de CyclomaticComplexMethod-drempel niet te overschrijden.
    private fun columnFor(field: TrackerField): String = when (field) {
        TrackerField.AI_PHASE, TrackerField.AI_LEVEL, TrackerField.AI_MAX_DEVELOPER_LOOPBACKS,
        TrackerField.AI_MAX_TEST_CHAIN_RESETS, TrackerField.AI_TOKEN_BUDGET, TrackerField.AI_TOKENS_USED,
        TrackerField.AI_SUPPLIER, TrackerField.AI_MODEL, TrackerField.AI_REASONING_EFFORT,
        -> columnForAiField(field)

        TrackerField.AGENT_STARTED_AT, TrackerField.PAUSED, TrackerField.SILENT,
        TrackerField.ERROR, TrackerField.AUTO_APPROVE,
        -> columnForLifecycleField(field)

        TrackerField.STORY_PHASE, TrackerField.SUBTASK_PHASE, TrackerField.SUBTASK_TYPE, TrackerField.REPO,
        -> columnForRoutingField(field)
    }

    private fun columnForAiField(field: TrackerField): String = when (field) {
        TrackerField.AI_PHASE -> "ai_phase"
        TrackerField.AI_LEVEL -> "ai_level"
        TrackerField.AI_MAX_DEVELOPER_LOOPBACKS -> "ai_max_developer_loopbacks"
        TrackerField.AI_MAX_TEST_CHAIN_RESETS -> "ai_max_test_chain_resets"
        TrackerField.AI_TOKEN_BUDGET -> "ai_token_budget"
        TrackerField.AI_TOKENS_USED -> "ai_tokens_used"
        TrackerField.AI_SUPPLIER -> "ai_supplier"
        TrackerField.AI_MODEL -> "ai_model"
        TrackerField.AI_REASONING_EFFORT -> "ai_reasoning_effort"
        else -> error("columnForAiField ontving onverwacht veld: $field")
    }

    private fun columnForLifecycleField(field: TrackerField): String = when (field) {
        TrackerField.AGENT_STARTED_AT -> "agent_started_at"
        TrackerField.PAUSED -> "paused"
        TrackerField.SILENT -> "silent"
        TrackerField.ERROR -> "error"
        TrackerField.AUTO_APPROVE -> "auto_approve"
        else -> error("columnForLifecycleField ontving onverwacht veld: $field")
    }

    private fun columnForRoutingField(field: TrackerField): String = when (field) {
        TrackerField.STORY_PHASE -> "story_phase"
        TrackerField.SUBTASK_PHASE -> "subtask_phase"
        TrackerField.SUBTASK_TYPE -> "subtask_type"
        TrackerField.REPO -> "repo"
        else -> error("columnForRoutingField ontving onverwacht veld: $field")
    }

    /** Coerceert de door callers gebruikte waarde-representaties (zie TrackerIssueFields.applying) naar echte kolomtypes. */
    private fun columnValue(field: TrackerField, value: Any?): Any? = when (field) {
        TrackerField.PAUSED, TrackerField.SILENT, TrackerField.AUTO_APPROVE -> toBoolean(value)
        TrackerField.AI_LEVEL, TrackerField.AI_MAX_DEVELOPER_LOOPBACKS,
        TrackerField.AI_MAX_TEST_CHAIN_RESETS,
        -> (value as? Number)?.toInt()
        TrackerField.AI_TOKEN_BUDGET, TrackerField.AI_TOKENS_USED -> (value as? Number)?.toLong()
        TrackerField.AGENT_STARTED_AT -> value as? OffsetDateTime
        TrackerField.REPO -> when (value) {
            null -> null
            is Collection<*> -> value.firstOrNull()?.toString()
            else -> value.toString()
        }
        else -> value?.toString()
    }

    private fun toBoolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true) || value.equals("on", ignoreCase = true)
        else -> false
    }

    private companion object {
        const val COMMENT_AUTHOR_ACCOUNT = "factory"
        const val COMMENT_AUTHOR_DISPLAY_NAME = "Software Factory"

        // SF-862: bovengrens op de niet-terminale/wacht-op-mens-subset in findAiIssues, zodat de
        // query begrensd blijft — er zijn altijd weinig open wachtende gates t.o.v. het totaal.
        const val PENDING_SUBSET_LIMIT = 500
        const val ISSUE_COLUMNS = "issue_key, project_key, summary, description, parent_key, status, " +
            "repo, ai_supplier, auto_approve, ai_phase, ai_level, ai_max_developer_loopbacks, " +
            "ai_max_test_chain_resets, ai_token_budget, ai_tokens_used, agent_started_at, paused, silent, error, " +
            "type, subtask_type, ai_model, ai_reasoning_effort, story_phase, subtask_phase, " +
            "created_at, updated_at"
    }
}
