package nl.vdzon.softwarefactory.nightly

import nl.vdzon.softwarefactory.config.FactorySecrets
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Persistente nachtelijke-scheduler-instellingen (één rij). Tijden staan als `HH:MM` in lokale
 * NL-tijd; de conversie naar UTC gebeurt in [NightlyTime].
 */
data class NightlySettings(
    val enabled: Boolean,
    val startTime: LocalTime,
    val summaryTime: LocalTime,
) {
    companion object {
        /** Neutrale defaults bij ontbrekende rij: scheduler uit, start 02:00, summary 07:00 (NL-tijd). */
        val DEFAULT = NightlySettings(
            enabled = false,
            startTime = LocalTime.of(2, 0),
            summaryTime = LocalTime.of(7, 0),
        )
    }
}

/** Eén nachtelijke run (`runDate` = NL-startdatum). Meerdere runs per dag zijn toegestaan. */
data class NightlyRunRecord(
    val id: Long,
    val runDate: LocalDate,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val status: String,
    val summarySentAt: OffsetDateTime?,
    /** De verstuurde digest-tekst (gezet zodra de summary verstuurd is), of null. */
    val summaryText: String? = null,
    /** [NightlyRunKind]: 'scheduled' (dagelijkse auto-run) of 'manual' (handmatig gestart). */
    val kind: String = NightlyRunKind.SCHEDULED,
    /** De digest ging zonder AI-details; een latere tick probeert die alsnog na te sturen. */
    val aiDetailPending: Boolean = false,
)

/** Eén job binnen een nachtelijke run, gekoppeld aan de aangemaakte story. */
data class NightlyRunJobRecord(
    val id: Long,
    val runId: Long,
    val project: String,
    val jobName: String,
    val title: String,
    val status: String,
    val storyKey: String?,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val error: String?,
)

/** Statuswaarden voor [NightlyRunRecord.status]. */
object NightlyRunStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val ENDED = "ended"
}

/** Hoe een run is ontstaan; bepaalt het digest-moment (zie [NightlyPlanner]). */
object NightlyRunKind {
    /** De automatische dagelijkse run; digest op de summary-tijd. */
    const val SCHEDULED = "scheduled"

    /** Handmatig gestart via de "Run nu"-knop; digest zodra de run klaar is (niet vóór summary-tijd). */
    const val MANUAL = "manual"
}

/** Statuswaarden voor [NightlyRunJobRecord.status]. */
object NightlyJobStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"

    /** Handmatig onderbroken (run gestopt voordat deze job klaar was). */
    const val CANCELLED = "cancelled"

    fun isTerminal(status: String): Boolean = status == DONE || status == FAILED || status == CANCELLED
}

@Repository
class NightlySettingsRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) {
    private val table get() = "${factorySecrets.factoryDatabaseSchema}.nightly_settings"

    /** Leest de enkele settings-rij; valt terug op [NightlySettings.DEFAULT] als die er (nog) niet is. */
    fun read(): NightlySettings =
        jdbcTemplate.query(
            "SELECT enabled, start_time, summary_time FROM $table WHERE id = 1",
            { rs, _ ->
                NightlySettings(
                    enabled = rs.getBoolean("enabled"),
                    startTime = NightlyTime.parseHhMm(rs.getString("start_time")),
                    summaryTime = NightlyTime.parseHhMm(rs.getString("summary_time")),
                )
            },
        ).firstOrNull() ?: NightlySettings.DEFAULT

    /** Schrijft de settings weg (upsert op de single-row id=1). */
    fun save(settings: NightlySettings) {
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
            NightlyTime.formatHhMm(settings.startTime),
            NightlyTime.formatHhMm(settings.summaryTime),
        )
    }
}

@Repository
class NightlyRunRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) {
    private val table get() = "${factorySecrets.factoryDatabaseSchema}.nightly_run"

    /** Maakt een nieuwe run aan (meerdere per dag toegestaan) en geeft 'm terug. */
    fun create(
        runDate: LocalDate,
        startedAt: OffsetDateTime,
        status: String = NightlyRunStatus.RUNNING,
        kind: String = NightlyRunKind.SCHEDULED,
    ): NightlyRunRecord {
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
        return requireNotNull(get(id)) { "nightly_run $id ontbreekt na insert" }
    }

    /** Bestaat er al een scheduled run op deze NL-datum? Voorkomt een dubbele dagelijkse auto-run. */
    fun hasScheduledRunOn(runDate: LocalDate): Boolean =
        (
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM $table WHERE run_date = ? AND kind = ?",
                Long::class.java,
                java.sql.Date.valueOf(runDate),
                NightlyRunKind.SCHEDULED,
            ) ?: 0L
        ) > 0L

    /** De meest recente run op deze NL-datum (kan null zijn). Meerdere runs per dag zijn mogelijk. */
    fun forDate(runDate: LocalDate): NightlyRunRecord? =
        jdbcTemplate.query(
            "${select()} WHERE run_date = ? ORDER BY id DESC LIMIT 1",
            { rs, _ -> rs.toRun() },
            java.sql.Date.valueOf(runDate),
        ).firstOrNull()

    fun get(runId: Long): NightlyRunRecord? =
        jdbcTemplate.query("${select()} WHERE id = ?", { rs, _ -> rs.toRun() }, runId).firstOrNull()

    /** De lopende run (status != ended), zo die er is. */
    fun activeRun(): NightlyRunRecord? =
        jdbcTemplate.query(
            "${select()} WHERE status <> ? ORDER BY run_date DESC LIMIT 1",
            { rs, _ -> rs.toRun() },
            NightlyRunStatus.ENDED,
        ).firstOrNull()

    /** De meest recente run, ongeacht status (voor de /nightly-statusweergave). */
    fun latestRun(): NightlyRunRecord? =
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

    /** Zet/wist de vlag dat de AI-details van deze run nog nagestuurd moeten worden. */
    fun setAiDetailPending(runId: Long, pending: Boolean) {
        jdbcTemplate.update("UPDATE $table SET ai_detail_pending = ? WHERE id = ?", pending, runId)
    }

    /** Runs waarvan de AI-details nog openstaan (digest ging zonder samenvatting). */
    fun pendingAiDetail(): List<NightlyRunRecord> =
        jdbcTemplate.query("${select()} WHERE ai_detail_pending = TRUE ORDER BY id", { rs, _ -> rs.toRun() })

    /** Markeert de digest als verstuurd en bewaart de tekst (idempotentie-anker voor de scheduler). */
    fun markSummarySent(runId: Long, at: OffsetDateTime, summaryText: String? = null) {
        jdbcTemplate.update(
            "UPDATE $table SET summary_sent_at = ?, summary_text = ? WHERE id = ?",
            at,
            summaryText,
            runId,
        )
    }

    private fun select(): String =
        "SELECT id, run_date, started_at, ended_at, status, summary_sent_at, summary_text, kind, ai_detail_pending FROM $table"

    private fun ResultSet.toRun(): NightlyRunRecord =
        NightlyRunRecord(
            id = getLong("id"),
            runDate = getObject("run_date", LocalDate::class.java),
            startedAt = getObject("started_at", OffsetDateTime::class.java),
            endedAt = getObject("ended_at", OffsetDateTime::class.java),
            status = getString("status"),
            summarySentAt = getObject("summary_sent_at", OffsetDateTime::class.java),
            summaryText = getString("summary_text"),
            kind = getString("kind") ?: NightlyRunKind.SCHEDULED,
            aiDetailPending = getBoolean("ai_detail_pending"),
        )
}

@Repository
class NightlyRunJobRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) {
    private val table get() = "${factorySecrets.factoryDatabaseSchema}.nightly_run_job"

    fun add(runId: Long, project: String, jobName: String, title: String): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO $table (run_id, project, job_name, title, status)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                runId,
                project,
                jobName,
                title,
                NightlyJobStatus.PENDING,
            ),
        )

    fun forRun(runId: Long): List<NightlyRunJobRecord> =
        jdbcTemplate.query(
            "${select()} WHERE run_id = ? ORDER BY project, id",
            { rs, _ -> rs.toJob() },
            runId,
        )

    fun forRunAndProject(runId: Long, project: String): List<NightlyRunJobRecord> =
        jdbcTemplate.query(
            "${select()} WHERE run_id = ? AND project = ? ORDER BY id",
            { rs, _ -> rs.toJob() },
            runId,
            project,
        )

    fun get(jobId: Long): NightlyRunJobRecord? =
        jdbcTemplate.query("${select()} WHERE id = ?", { rs, _ -> rs.toJob() }, jobId).firstOrNull()

    /** Markeert een job als gestart en koppelt de aangemaakte story. */
    fun markRunning(jobId: Long, storyKey: String, startedAt: OffsetDateTime) {
        jdbcTemplate.update(
            "UPDATE $table SET status = ?, story_key = ?, started_at = ? WHERE id = ?",
            NightlyJobStatus.RUNNING,
            storyKey,
            startedAt,
            jobId,
        )
    }

    /** Markeert een job terminal (done/failed) met optionele eind-tijd en foutmelding. */
    fun markTerminal(jobId: Long, status: String, endedAt: OffsetDateTime, error: String? = null) {
        jdbcTemplate.update(
            "UPDATE $table SET status = ?, ended_at = ?, error = ? WHERE id = ?",
            status,
            endedAt,
            error,
            jobId,
        )
    }

    private fun select(): String =
        "SELECT id, run_id, project, job_name, title, status, story_key, started_at, ended_at, error FROM $table"

    private fun ResultSet.toJob(): NightlyRunJobRecord =
        NightlyRunJobRecord(
            id = getLong("id"),
            runId = getLong("run_id"),
            project = getString("project"),
            jobName = getString("job_name"),
            title = getString("title"),
            status = getString("status"),
            storyKey = getString("story_key"),
            startedAt = getObject("started_at", OffsetDateTime::class.java),
            endedAt = getObject("ended_at", OffsetDateTime::class.java),
            error = getString("error"),
        )
}
