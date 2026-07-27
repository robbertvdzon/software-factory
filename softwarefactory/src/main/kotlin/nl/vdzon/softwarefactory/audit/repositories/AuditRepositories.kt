package nl.vdzon.softwarefactory.audit.repositories

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.time.FactoryTime
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Persistente audit-scheduler-instellingen (één rij). Tijden staan als `HH:MM` in lokale
 * NL-tijd; de conversie naar UTC gebeurt in [FactoryTime].
 */
data class AuditSettings(
    val enabled: Boolean,
    val startTime: LocalTime,
    val summaryTime: LocalTime,
) {
    companion object {
        /** Neutrale defaults bij ontbrekende rij: scheduler uit, start 08:00, summary 08:30. */
        val DEFAULT = AuditSettings(
            enabled = false,
            startTime = LocalTime.of(8, 0),
            summaryTime = LocalTime.of(8, 30),
        )
    }
}

/** Eén audit-run (`runDate` = NL-startdatum). */
data class AuditRunRecord(
    val id: Long,
    val runDate: LocalDate,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val status: String,
    val summarySentAt: OffsetDateTime?,
    val summaryText: String? = null,
    val kind: String = AuditRunKind.SCHEDULED,
)

/** Eén gekozen audit binnen een run: precies 1 per project (zie [nl.vdzon.softwarefactory.audit.services.AuditPlanner]). */
data class AuditRunJobRecord(
    val id: Long,
    val runId: Long,
    val project: String,
    val auditType: String,
    val title: String,
    val status: String,
    val reportId: Long?,
    /** Identiteit van de gestarte agent-run (gezet zodra de job 'running' wordt) — geen tracker-story. */
    val containerName: String?,
    val workspacePath: String?,
    val storyRunId: Long?,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val error: String?,
)

/** Eén opgeslagen audit-rapport; de historie per (project, auditType) bepaalt zowel de
 * "laatste-run"-selectie als de score-trend in het dashboard. */
data class AuditReportRecord(
    val id: Long,
    val project: String,
    val auditType: String,
    val generatedAt: OffsetDateTime,
    val content: String,
    val score: Double?,
    val scoreLabel: String?,
    val proposedStoryKey: String?,
    val status: String,
    val error: String?,
    val durationMs: Long?,
)

object AuditRunStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val ENDED = "ended"
}

/** Hoe een run is ontstaan; bepaalt het digest-moment (zie [nl.vdzon.softwarefactory.audit.services.AuditPlanner]). */
object AuditRunKind {
    const val SCHEDULED = "scheduled"
    const val MANUAL = "manual"
}

object AuditJobStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    fun isTerminal(status: String): Boolean = status == DONE || status == FAILED || status == CANCELLED
}

@Repository
class AuditSettingsRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) {
    private val table get() = "${factorySecrets.factoryDatabaseSchema}.audit_settings"

    fun read(): AuditSettings =
        jdbcTemplate.query(
            "SELECT enabled, start_time, summary_time FROM $table WHERE id = 1",
            { rs, _ ->
                AuditSettings(
                    enabled = rs.getBoolean("enabled"),
                    startTime = FactoryTime.parseHhMm(rs.getString("start_time")),
                    summaryTime = FactoryTime.parseHhMm(rs.getString("summary_time")),
                )
            },
        ).firstOrNull() ?: AuditSettings.DEFAULT

    fun save(settings: AuditSettings) {
        jdbcTemplate.update(
            """
            INSERT INTO $table (id, enabled, start_time, summary_time, updated_at)
            VALUES (1, ?, ?, ?, now())
            ON CONFLICT (id) DO UPDATE
            SET enabled = EXCLUDED.enabled,
                start_time = EXCLUDED.start_time,
                summary_time = EXCLUDED.summary_time,
                updated_at = now()
            """.trimIndent(),
            settings.enabled,
            FactoryTime.formatHhMm(settings.startTime),
            FactoryTime.formatHhMm(settings.summaryTime),
        )
    }
}

/** Per-project audit-instelling; `startTime = null` betekent "geen override, val terug op [AuditSettings.startTime]". */
data class AuditProjectSettings(
    val project: String,
    val startTime: LocalTime?,
    val auditCount: Int,
) {
    companion object {
        const val DEFAULT_AUDIT_COUNT = 1
    }
}

@Repository
class AuditProjectSettingsRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) {
    private val table get() = "${factorySecrets.factoryDatabaseSchema}.audit_project_settings"

    fun get(project: String): AuditProjectSettings? =
        jdbcTemplate.query("${select()} WHERE project = ?", { rs, _ -> rs.toSettings() }, project).firstOrNull()

    fun all(): List<AuditProjectSettings> =
        jdbcTemplate.query(select(), { rs, _ -> rs.toSettings() })

    fun save(project: String, startTime: LocalTime?, auditCount: Int) {
        jdbcTemplate.update(
            """
            INSERT INTO $table (project, start_time, audit_count, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (project) DO UPDATE
            SET start_time = EXCLUDED.start_time,
                audit_count = EXCLUDED.audit_count,
                updated_at = now()
            """.trimIndent(),
            project,
            startTime?.let { FactoryTime.formatHhMm(it) },
            auditCount,
        )
    }

    private fun select(): String = "SELECT project, start_time, audit_count FROM $table"

    private fun ResultSet.toSettings(): AuditProjectSettings =
        AuditProjectSettings(
            project = getString("project"),
            startTime = getString("start_time")?.let { FactoryTime.parseHhMm(it) },
            auditCount = getInt("audit_count"),
        )
}

@Repository
class AuditRunRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) {
    private val table get() = "${factorySecrets.factoryDatabaseSchema}.audit_run"

    fun create(
        runDate: LocalDate,
        startedAt: OffsetDateTime,
        status: String = AuditRunStatus.RUNNING,
        kind: String = AuditRunKind.SCHEDULED,
    ): AuditRunRecord {
        val id = requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO $table (run_date, started_at, status, kind)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                java.sql.Date.valueOf(runDate),
                startedAt,
                status,
                kind,
            ),
        )
        return requireNotNull(get(id)) { "audit_run $id ontbreekt na insert" }
    }

    fun hasScheduledRunOn(runDate: LocalDate): Boolean =
        (
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM $table WHERE run_date = ? AND kind = ?",
                Long::class.java,
                java.sql.Date.valueOf(runDate),
                AuditRunKind.SCHEDULED,
            ) ?: 0L
        ) > 0L

    fun get(runId: Long): AuditRunRecord? =
        jdbcTemplate.query("${select()} WHERE id = ?", { rs, _ -> rs.toRun() }, runId).firstOrNull()

    /** De lopende run (status != ended), zo die er is. */
    fun activeRun(): AuditRunRecord? =
        jdbcTemplate.query(
            "${select()} WHERE status <> ? ORDER BY run_date DESC LIMIT 1",
            { rs, _ -> rs.toRun() },
            AuditRunStatus.ENDED,
        ).firstOrNull()

    fun latestRun(): AuditRunRecord? =
        jdbcTemplate.query(
            "${select()} ORDER BY run_date DESC, id DESC LIMIT 1",
            { rs, _ -> rs.toRun() },
        ).firstOrNull()

    fun updateStatus(runId: Long, status: String, endedAt: OffsetDateTime? = null) {
        jdbcTemplate.update(
            "UPDATE $table SET status = ?, ended_at = COALESCE(?, ended_at) WHERE id = ?",
            status,
            endedAt,
            runId,
        )
    }

    fun markSummarySent(runId: Long, at: OffsetDateTime, summaryText: String? = null) {
        jdbcTemplate.update(
            "UPDATE $table SET summary_sent_at = ?, summary_text = ? WHERE id = ?",
            at,
            summaryText,
            runId,
        )
    }

    private fun select(): String =
        "SELECT id, run_date, started_at, ended_at, status, summary_sent_at, summary_text, kind FROM $table"

    private fun ResultSet.toRun(): AuditRunRecord =
        AuditRunRecord(
            id = getLong("id"),
            runDate = getObject("run_date", LocalDate::class.java),
            startedAt = getObject("started_at", OffsetDateTime::class.java),
            endedAt = getObject("ended_at", OffsetDateTime::class.java),
            status = getString("status"),
            summarySentAt = getObject("summary_sent_at", OffsetDateTime::class.java),
            summaryText = getString("summary_text"),
            kind = getString("kind") ?: AuditRunKind.SCHEDULED,
        )
}

@Repository
class AuditRunJobRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) {
    private val table get() = "${factorySecrets.factoryDatabaseSchema}.audit_run_job"

    fun add(runId: Long, project: String, auditType: String, title: String): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO $table (run_id, project, audit_type, title, status)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                runId,
                project,
                auditType,
                title,
                AuditJobStatus.PENDING,
            ),
        )

    fun forRun(runId: Long): List<AuditRunJobRecord> =
        jdbcTemplate.query("${select()} WHERE run_id = ? ORDER BY project, id", { rs, _ -> rs.toJob() }, runId)

    fun get(jobId: Long): AuditRunJobRecord? =
        jdbcTemplate.query("${select()} WHERE id = ?", { rs, _ -> rs.toJob() }, jobId).firstOrNull()

    /** Markeert de job als gestart en koppelt de identiteit van de gedispatchte agent-run. */
    fun markRunning(jobId: Long, containerName: String, workspacePath: String?, storyRunId: Long, startedAt: OffsetDateTime) {
        jdbcTemplate.update(
            "UPDATE $table SET status = ?, container_name = ?, workspace_path = ?, story_run_id = ?, started_at = ? WHERE id = ?",
            AuditJobStatus.RUNNING,
            containerName,
            workspacePath,
            storyRunId,
            startedAt,
            jobId,
        )
    }

    fun markTerminal(jobId: Long, status: String, endedAt: OffsetDateTime, reportId: Long? = null, error: String? = null) {
        jdbcTemplate.update(
            "UPDATE $table SET status = ?, ended_at = ?, report_id = ?, error = ? WHERE id = ?",
            status,
            endedAt,
            reportId,
            error,
            jobId,
        )
    }

    private fun select(): String =
        "SELECT id, run_id, project, audit_type, title, status, report_id, container_name, workspace_path, story_run_id, started_at, ended_at, error FROM $table"

    private fun ResultSet.toJob(): AuditRunJobRecord =
        AuditRunJobRecord(
            id = getLong("id"),
            runId = getLong("run_id"),
            project = getString("project"),
            auditType = getString("audit_type"),
            title = getString("title"),
            status = getString("status"),
            reportId = getObject("report_id") as? Long,
            containerName = getString("container_name"),
            workspacePath = getString("workspace_path"),
            storyRunId = getObject("story_run_id") as? Long,
            startedAt = getObject("started_at", OffsetDateTime::class.java),
            endedAt = getObject("ended_at", OffsetDateTime::class.java),
            error = getString("error"),
        )
}

@Repository
class AuditReportRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) {
    private val table get() = "${factorySecrets.factoryDatabaseSchema}.audit_report"

    fun add(
        project: String,
        auditType: String,
        content: String,
        score: Double? = null,
        scoreLabel: String? = null,
        proposedStoryKey: String? = null,
        status: String = "done",
        error: String? = null,
        durationMs: Long? = null,
    ): AuditReportRecord {
        val id = requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO $table (project, audit_type, content, score, score_label, proposed_story_key, status, error, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                project,
                auditType,
                content,
                score,
                scoreLabel,
                proposedStoryKey,
                status,
                error,
                durationMs,
            ),
        )
        return requireNotNull(get(id)) { "audit_report $id ontbreekt na insert" }
    }

    fun get(id: Long): AuditReportRecord? =
        jdbcTemplate.query("${select()} WHERE id = ?", { rs, _ -> rs.toReport() }, id).firstOrNull()

    /** Meest recente rapporten voor (project, auditType), nieuwste eerst — historische context voor de auditor. */
    fun recentFor(project: String, auditType: String, limit: Int = 5): List<AuditReportRecord> =
        jdbcTemplate.query(
            "${select()} WHERE project = ? AND audit_type = ? ORDER BY generated_at DESC LIMIT ?",
            { rs, _ -> rs.toReport() },
            project,
            auditType,
            limit,
        )

    /** Timestamp van de laatste run voor (project, auditType), of null als 'ie nog nooit draaide (= oudste). */
    fun lastGeneratedAt(project: String, auditType: String): OffsetDateTime? =
        jdbcTemplate.query(
            "SELECT MAX(generated_at) AS last FROM $table WHERE project = ? AND audit_type = ?",
            { rs, _ -> rs.getObject("last", OffsetDateTime::class.java) },
            project,
            auditType,
        ).firstOrNull()

    /** Alle projecten+audit-types met minstens 1 rapport, met hun laatste timestamp en (indien aanwezig) score-trend. */
    fun latestPerProjectAndType(): List<AuditReportRecord> =
        jdbcTemplate.query(
            """
            SELECT DISTINCT ON (project, audit_type) id, project, audit_type, generated_at, content, score,
                   score_label, proposed_story_key, status, error
            FROM $table
            ORDER BY project, audit_type, generated_at DESC
            """.trimIndent(),
            { rs, _ -> rs.toReport() },
        )

    private fun select(): String =
        "SELECT id, project, audit_type, generated_at, content, score, score_label, proposed_story_key, status, error, duration_ms FROM $table"

    private fun ResultSet.toReport(): AuditReportRecord =
        AuditReportRecord(
            id = getLong("id"),
            project = getString("project"),
            auditType = getString("audit_type"),
            generatedAt = getObject("generated_at", OffsetDateTime::class.java),
            content = getString("content"),
            score = getObject("score") as? Double,
            scoreLabel = getString("score_label"),
            proposedStoryKey = getString("proposed_story_key"),
            status = getString("status"),
            error = getString("error"),
            durationMs = getObject("duration_ms") as? Long,
        )
}
