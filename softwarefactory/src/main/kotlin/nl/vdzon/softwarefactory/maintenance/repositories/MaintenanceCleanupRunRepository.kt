package nl.vdzon.softwarefactory.maintenance.repositories

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * De vijf opruimmechanismen die in deze log terechtkomen. Vrije tekst in de database (zie V31), hier
 * als afspraak vastgelegd zodat schrijvers en het scherm dezelfde waarden gebruiken.
 */
object CleanupKinds {
    const val GITHUB_RELEASES = "github-releases"
    const val AGENT_EVENTS = "agent-events"
    const val AGENT_RUNS = "agent-runs"
    const val COMPLETION_PAYLOADS = "completion-payloads"
    const val WORKSPACES = "workspaces"

    /** Volgorde van het soort-filter in het scherm. */
    val ALL = listOf(GITHUB_RELEASES, AGENT_EVENTS, AGENT_RUNS, COMPLETION_PAYLOADS, WORKSPACES)
}

/**
 * JSON-vorm van de `details`-kolom: puur presentatiemateriaal voor het detailscherm, nooit een
 * queryable dimensie. Alleen [CleanupKinds.GITHUB_RELEASES] vult 'm; de vier andere mechanismen
 * hebben niets uit te splitsen en laten alles op de defaults staan.
 */
data class CleanupDetails(
    val releaseTags: List<String> = emptyList(),
    val packageVersions: List<String> = emptyList(),
    val releasesDeleted: Int = 0,
    val releasesKept: Int = 0,
    val packagesDeleted: Int = 0,
    val packagesKept: Int = 0,
)

/**
 * Eén opruimronde van één mechanisme.
 *
 * [project] is NULL voor factory-brede rondes (alles behalve de GitHub-cleanup, die per project
 * draait). Bij een dry-run staan in [details] de *geplande* verwijderingen (er is niets weggegooid).
 * [error] is gereserveerd voor het falen van de héle ronde — een individuele mislukte delete is
 * fail-soft en telt simpelweg niet mee in de aantallen.
 */
data class MaintenanceCleanupRunRecord(
    val id: Long,
    val kind: String,
    val project: String?,
    val startedAt: OffsetDateTime,
    val finishedAt: OffsetDateTime,
    val itemsDeleted: Int,
    val itemsKept: Int,
    val dryRun: Boolean,
    val error: String?,
    val details: CleanupDetails,
)

/**
 * Wat er van één opruimronde wordt vastgelegd — [MaintenanceCleanupRunRecord] is dezelfde inhoud,
 * maar dan mét de door de database toegekende id.
 */
data class NewMaintenanceCleanupRun(
    val kind: String,
    val startedAt: OffsetDateTime,
    val finishedAt: OffsetDateTime,
    val itemsDeleted: Int,
    val itemsKept: Int = 0,
    val project: String? = null,
    val dryRun: Boolean = false,
    val error: String? = null,
    val details: CleanupDetails = CleanupDetails(),
)

/**
 * Historie van álle opruimrondes (zie V30/V31). Zelfde recept als
 * [nl.vdzon.softwarefactory.audit.repositories.AuditReportRepository]: `JdbcTemplate` +
 * [FactorySecrets] voor het schema, `INSERT … RETURNING id`, en een lijstquery die nieuwste-eerst
 * sorteert.
 */
// `open` puur voor testbaarheid (geen mock-framework in deze repo): MaintenanceCleanupSchedulerTest
// en de poller-tests vervangen 'm door een in-memory subklasse-fake.
@Repository
open class MaintenanceCleanupRunRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) {
    private val objectMapper = jacksonObjectMapper()

    private val table get() = "${factorySecrets.factoryDatabaseSchema}.maintenance_cleanup_runs"

    open fun add(run: NewMaintenanceCleanupRun): MaintenanceCleanupRunRecord {
        val id = requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO $table (kind, project, started_at, finished_at, items_deleted, items_kept,
                                    dry_run, error, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                run.kind,
                run.project,
                run.startedAt,
                run.finishedAt,
                run.itemsDeleted,
                run.itemsKept,
                run.dryRun,
                run.error,
                objectMapper.writeValueAsString(run.details),
            ),
        )
        return requireNotNull(get(id)) { "maintenance_cleanup_runs $id ontbreekt na insert" }
    }

    open fun get(id: Long): MaintenanceCleanupRunRecord? =
        jdbcTemplate.query("${select()} WHERE id = ?", { rs, _ -> rs.toRun() }, id).firstOrNull()

    /**
     * Nieuwste eerst, optioneel op één project en/of één soort gefilterd — de enige leesroute van
     * het Opruimen-scherm.
     */
    open fun recent(
        project: String? = null,
        kind: String? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<MaintenanceCleanupRunRecord> {
        val conditions = mutableListOf<String>()
        val arguments = mutableListOf<Any>()
        project?.let {
            conditions += "project = ?"
            arguments += it
        }
        kind?.let {
            conditions += "kind = ?"
            arguments += it
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        arguments += limit
        return jdbcTemplate.query(
            "${select()} $where ORDER BY started_at DESC, id DESC LIMIT ?",
            { rs, _ -> rs.toRun() },
            *arguments.toTypedArray(),
        )
    }

    /** Retentie: gooit alles weg dat vóór [cutoff] gestart is; geeft het aantal verwijderde rijen terug. */
    open fun deleteOlderThan(cutoff: OffsetDateTime): Int =
        jdbcTemplate.update("DELETE FROM $table WHERE started_at < ?", cutoff)

    private fun select(): String =
        """
        SELECT id, kind, project, started_at, finished_at, items_deleted, items_kept, dry_run, error, details
        FROM $table
        """.trimIndent()

    private fun ResultSet.toRun(): MaintenanceCleanupRunRecord =
        MaintenanceCleanupRunRecord(
            id = getLong("id"),
            kind = getString("kind"),
            project = getString("project"),
            startedAt = getObject("started_at", OffsetDateTime::class.java),
            finishedAt = getObject("finished_at", OffsetDateTime::class.java),
            itemsDeleted = getInt("items_deleted"),
            itemsKept = getInt("items_kept"),
            dryRun = getBoolean("dry_run"),
            error = getString("error"),
            details = parseDetails(getString("details")),
        )

    /** Onleesbare/ontbrekende details mogen de lijst nooit laten omvallen: dan gewoon lege opsommingen. */
    private fun parseDetails(raw: String?): CleanupDetails =
        raw?.let { runCatching { objectMapper.readValue<CleanupDetails>(it) }.getOrNull() } ?: CleanupDetails()

    private companion object {
        const val DEFAULT_LIMIT = 200
    }
}
