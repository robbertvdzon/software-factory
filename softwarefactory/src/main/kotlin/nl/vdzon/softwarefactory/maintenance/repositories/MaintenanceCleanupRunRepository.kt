package nl.vdzon.softwarefactory.maintenance.repositories

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * Eén opruimronde van de nachtelijke maintenance-cleanup voor één project.
 *
 * [deletedReleaseTags] en [deletedPackageVersions] zijn de leesbare opsomming voor het
 * detailscherm; bij een dry-run staan hier de *geplande* verwijderingen (er is niets weggegooid).
 * [error] is gereserveerd voor het falen van de héle projectrun — een individuele mislukte delete is
 * fail-soft en telt simpelweg niet mee in de aantallen.
 */
data class MaintenanceCleanupRunRecord(
    val id: Long,
    val project: String,
    val startedAt: OffsetDateTime,
    val finishedAt: OffsetDateTime,
    val releasesDeleted: Int,
    val releasesKept: Int,
    val packagesDeleted: Int,
    val packagesKept: Int,
    val dryRun: Boolean,
    val error: String?,
    val deletedReleaseTags: List<String>,
    val deletedPackageVersions: List<String>,
)

/**
 * Wat er van één opruimronde wordt vastgelegd — [MaintenanceCleanupRunRecord] is dezelfde inhoud,
 * maar dan mét de door de database toegekende id.
 */
data class NewMaintenanceCleanupRun(
    val project: String,
    val startedAt: OffsetDateTime,
    val finishedAt: OffsetDateTime,
    val releasesDeleted: Int,
    val releasesKept: Int,
    val packagesDeleted: Int,
    val packagesKept: Int,
    val dryRun: Boolean,
    val error: String? = null,
    val deletedReleaseTags: List<String> = emptyList(),
    val deletedPackageVersions: List<String> = emptyList(),
)

/**
 * JSON-vorm van de `details`-kolom; alleen presentatiemateriaal voor het detailscherm. Bewust een
 * top-level (file-private) type: Jackson construeert 'm reflectief.
 */
private data class Details(
    val releaseTags: List<String> = emptyList(),
    val packageVersions: List<String> = emptyList(),
)

/**
 * Historie van de maintenance-cleanup (zie V30). Zelfde recept als
 * [nl.vdzon.softwarefactory.audit.repositories.AuditReportRepository]: `JdbcTemplate` +
 * [FactorySecrets] voor het schema, `INSERT … RETURNING id`, en een lijstquery die nieuwste-eerst
 * sorteert.
 */
// `open` puur voor testbaarheid (geen mock-framework in deze repo): MaintenanceCleanupSchedulerTest
// vervangt 'm door een in-memory subklasse-fake.
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
                INSERT INTO $table (project, started_at, finished_at, releases_deleted, releases_kept,
                                    packages_deleted, packages_kept, dry_run, error, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                run.project,
                run.startedAt,
                run.finishedAt,
                run.releasesDeleted,
                run.releasesKept,
                run.packagesDeleted,
                run.packagesKept,
                run.dryRun,
                run.error,
                objectMapper.writeValueAsString(Details(run.deletedReleaseTags, run.deletedPackageVersions)),
            ),
        )
        return requireNotNull(get(id)) { "maintenance_cleanup_runs $id ontbreekt na insert" }
    }

    open fun get(id: Long): MaintenanceCleanupRunRecord? =
        jdbcTemplate.query("${select()} WHERE id = ?", { rs, _ -> rs.toRun() }, id).firstOrNull()

    /** Nieuwste eerst, optioneel op één project gefilterd — de enige leesroute van het Maintenance-scherm. */
    open fun recent(project: String? = null, limit: Int = DEFAULT_LIMIT): List<MaintenanceCleanupRunRecord> =
        if (project == null) {
            jdbcTemplate.query("${select()} ORDER BY started_at DESC, id DESC LIMIT ?", { rs, _ -> rs.toRun() }, limit)
        } else {
            jdbcTemplate.query(
                "${select()} WHERE project = ? ORDER BY started_at DESC, id DESC LIMIT ?",
                { rs, _ -> rs.toRun() },
                project,
                limit,
            )
        }

    /** Retentie: gooit alles weg dat vóór [cutoff] gestart is; geeft het aantal verwijderde rijen terug. */
    open fun deleteOlderThan(cutoff: OffsetDateTime): Int =
        jdbcTemplate.update("DELETE FROM $table WHERE started_at < ?", cutoff)

    private fun select(): String =
        """
        SELECT id, project, started_at, finished_at, releases_deleted, releases_kept,
               packages_deleted, packages_kept, dry_run, error, details
        FROM $table
        """.trimIndent()

    private fun ResultSet.toRun(): MaintenanceCleanupRunRecord {
        val details = parseDetails(getString("details"))
        return MaintenanceCleanupRunRecord(
            id = getLong("id"),
            project = getString("project"),
            startedAt = getObject("started_at", OffsetDateTime::class.java),
            finishedAt = getObject("finished_at", OffsetDateTime::class.java),
            releasesDeleted = getInt("releases_deleted"),
            releasesKept = getInt("releases_kept"),
            packagesDeleted = getInt("packages_deleted"),
            packagesKept = getInt("packages_kept"),
            dryRun = getBoolean("dry_run"),
            error = getString("error"),
            deletedReleaseTags = details.releaseTags,
            deletedPackageVersions = details.packageVersions,
        )
    }

    /** Onleesbare/ontbrekende details mogen de lijst nooit laten omvallen: dan gewoon lege opsommingen. */
    private fun parseDetails(raw: String?): Details =
        raw?.let { runCatching { objectMapper.readValue<Details>(it) }.getOrNull() } ?: Details()

    private companion object {
        const val DEFAULT_LIMIT = 200
    }
}
