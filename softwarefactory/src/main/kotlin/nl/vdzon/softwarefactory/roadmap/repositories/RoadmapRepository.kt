package nl.vdzon.softwarefactory.roadmap.repositories

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.roadmap.models.RoadmapEpicRecord
import nl.vdzon.softwarefactory.roadmap.types.EpicStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class RoadmapRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val secrets: FactorySecrets,
) {
    private val schema get() = secrets.factoryDatabaseSchema

    fun findAll(): List<RoadmapEpicRecord> {
        val dependencies = jdbcTemplate.query(
            "SELECT epic_id, dependency_id FROM $schema.roadmap_epic_dependencies",
        ) { rs, _ -> rs.getLong("epic_id") to rs.getLong("dependency_id") }
            .groupBy({ it.first }, { it.second })
        return jdbcTemplate.query(
            "SELECT * FROM $schema.roadmap_epics ORDER BY id",
        ) { rs, _ -> mapRow(rs, dependencies[rs.getLong("id")].orEmpty().toSet()) }
    }

    fun lockAll() {
        // Dekt ook de lege roadmap: dan bestaan nog geen rijen voor FOR UPDATE.
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(hashtext('softwarefactory-roadmap'))")
        jdbcTemplate.query("SELECT id FROM $schema.roadmap_epics ORDER BY id FOR UPDATE") { rs, _ -> rs.getLong(1) }
    }

    fun create(title: String, description: String?, customerRank: Int, processRank: Int): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO $schema.roadmap_epics (title, description, customer_rank, process_rank)
                VALUES (?, ?, ?, ?) RETURNING id
                """.trimIndent(),
                Long::class.java,
                title,
                description,
                customerRank,
                processRank,
            ),
        )

    fun update(id: Long, title: String, description: String?, status: EpicStatus) {
        val count = jdbcTemplate.update(
            "UPDATE $schema.roadmap_epics SET title = ?, description = ?, status = ?, updated_at = now() WHERE id = ?",
            title,
            description,
            status.wireValue,
            id,
        )
        require(count == 1) { "Epic $id bestaat niet." }
    }

    fun replaceDependencies(id: Long, dependencyIds: Set<Long>) {
        jdbcTemplate.update("DELETE FROM $schema.roadmap_epic_dependencies WHERE epic_id = ?", id)
        dependencyIds.forEach { dependencyId ->
            jdbcTemplate.update(
                "INSERT INTO $schema.roadmap_epic_dependencies (epic_id, dependency_id) VALUES (?, ?)",
                id,
                dependencyId,
            )
        }
    }

    fun reorderCustomer(id: Long, oldRank: Int, requestedRank: Int, count: Int) {
        val target = requestedRank.coerceIn(1, count)
        when {
            target < oldRank -> jdbcTemplate.update(
                "UPDATE $schema.roadmap_epics SET customer_rank = customer_rank + 1 WHERE customer_rank >= ? AND customer_rank < ? AND id <> ?",
                target,
                oldRank,
                id,
            )
            target > oldRank -> jdbcTemplate.update(
                "UPDATE $schema.roadmap_epics SET customer_rank = customer_rank - 1 WHERE customer_rank > ? AND customer_rank <= ? AND id <> ?",
                oldRank,
                target,
                id,
            )
        }
        jdbcTemplate.update("UPDATE $schema.roadmap_epics SET customer_rank = ?, updated_at = now() WHERE id = ?", target, id)
    }

    fun reorderProcess(id: Long, oldRank: Int, requestedRank: Int, count: Int) {
        val target = requestedRank.coerceIn(1, count)
        when {
            target < oldRank -> jdbcTemplate.update(
                "UPDATE $schema.roadmap_epics SET process_rank = process_rank + 1 WHERE process_rank >= ? AND process_rank < ? AND id <> ?",
                target,
                oldRank,
                id,
            )
            target > oldRank -> jdbcTemplate.update(
                "UPDATE $schema.roadmap_epics SET process_rank = process_rank - 1 WHERE process_rank > ? AND process_rank <= ? AND id <> ?",
                oldRank,
                target,
                id,
            )
        }
        jdbcTemplate.update("UPDATE $schema.roadmap_epics SET process_rank = ?, updated_at = now() WHERE id = ?", target, id)
    }

    private fun mapRow(rs: ResultSet, dependencyIds: Set<Long>) = RoadmapEpicRecord(
        id = rs.getLong("id"),
        title = rs.getString("title"),
        description = rs.getString("description"),
        status = EpicStatus.fromWire(rs.getString("status")),
        customerRank = rs.getInt("customer_rank"),
        processRank = rs.getInt("process_rank"),
        dependencyIds = dependencyIds,
        createdAt = rs.getObject("created_at", java.time.OffsetDateTime::class.java),
        updatedAt = rs.getObject("updated_at", java.time.OffsetDateTime::class.java),
    )
}
