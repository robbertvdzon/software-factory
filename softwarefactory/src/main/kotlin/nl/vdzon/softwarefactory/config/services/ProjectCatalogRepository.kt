package nl.vdzon.softwarefactory.config.services

import nl.vdzon.softwarefactory.config.FactorySecrets
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/** Het opgeslagen catalogusdocument (tabel `project_catalog`, altijd rij 1). */
data class ProjectCatalogDocument(val yaml: String, val updatedAt: OffsetDateTime, val updatedBy: String)

interface ProjectCatalogRepository {
    fun read(): ProjectCatalogDocument?
    fun write(yaml: String, updatedBy: String): ProjectCatalogDocument
}

@Repository
class JdbcProjectCatalogRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : ProjectCatalogRepository {
    private val table get() = "${factorySecrets.factoryDatabaseSchema}.project_catalog"

    override fun read(): ProjectCatalogDocument? =
        jdbcTemplate.query("SELECT yaml, updated_at, updated_by FROM $table WHERE id = 1") { rs, _ ->
            ProjectCatalogDocument(rs.getString("yaml"), rs.getObject("updated_at", OffsetDateTime::class.java), rs.getString("updated_by"))
        }.firstOrNull()

    override fun write(yaml: String, updatedBy: String): ProjectCatalogDocument {
        jdbcTemplate.update(
            """
            INSERT INTO $table (id, yaml, updated_at, updated_by) VALUES (1, ?, now(), ?)
            ON CONFLICT (id) DO UPDATE SET yaml = EXCLUDED.yaml, updated_at = now(), updated_by = EXCLUDED.updated_by
            """.trimIndent(),
            yaml, updatedBy,
        )
        return checkNotNull(read()) { "project_catalog is na het schrijven leeg" }
    }
}
