package nl.vdzon.softwarefactory.config

import nl.vdzon.softwarefactory.config.services.ProjectCatalogDocument
import nl.vdzon.softwarefactory.config.services.ProjectCatalogRepository
import nl.vdzon.softwarefactory.config.services.ReloadableProjectCatalog
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReloadableProjectCatalogTest {
    @TempDir
    lateinit var tempDir: Path

    private class InMemoryRepository(var document: ProjectCatalogDocument? = null) : ProjectCatalogRepository {
        var writes = 0
        override fun read() = document
        override fun write(yaml: String, updatedBy: String): ProjectCatalogDocument {
            writes++
            return ProjectCatalogDocument(yaml, OffsetDateTime.parse("2026-09-12T20:00:00Z"), updatedBy).also { document = it }
        }
    }

    private val catalogYaml = """
        projects:
          - name: sample
            repo: https://github.com/robbert/sample
            runtimeAlias: sample
            merge:
              requiredChecks: [verify]
    """.trimIndent()

    @Test
    fun `leest de catalogus uit de database als die er is`() {
        val repository = InMemoryRepository(ProjectCatalogDocument(catalogYaml, OffsetDateTime.parse("2026-09-12T10:00:00Z"), "robbert"))

        val catalog = ReloadableProjectCatalog(repository, importFile = tempDir.resolve("ontbreekt.yaml"))

        assertEquals(listOf("sample"), catalog.projectNames())
        assertEquals("https://github.com/robbert/sample", catalog.repoFor("sample"))
        assertEquals("robbert", catalog.updatedBy())
        assertEquals(0, repository.writes)
    }

    @Test
    fun `importeert eenmalig het bestand als de database nog leeg is`() {
        val file = tempDir.resolve("projects.yaml").also { it.writeText(catalogYaml) }
        val repository = InMemoryRepository()

        val catalog = ReloadableProjectCatalog(repository, importFile = file)

        assertEquals(listOf("sample"), catalog.projectNames())
        assertEquals(1, repository.writes)
        assertEquals(catalogYaml, repository.document?.yaml)
        assertEquals("import:$file", catalog.updatedBy())
    }

    @Test
    fun `zonder database-inhoud en zonder bestand is de catalogus leeg`() {
        val catalog = ReloadableProjectCatalog(InMemoryRepository(), importFile = tempDir.resolve("ontbreekt.yaml"))

        assertEquals(emptyList(), catalog.projectNames())
        assertNull(catalog.yaml())
    }

    @Test
    fun `save valideert, bewaart en maakt de nieuwe catalogus direct actief`() {
        val repository = InMemoryRepository()
        val catalog = ReloadableProjectCatalog(repository, importFile = tempDir.resolve("ontbreekt.yaml"))
        val updated = catalogYaml + "\n  - name: tweede\n    repo: https://github.com/robbert/tweede\n    merge:\n      requiredChecks: [build]\n"

        catalog.save(updated, "robbert@example.com")

        assertEquals(listOf("sample", "tweede"), catalog.projectNames())
        assertEquals(setOf("build"), catalog.requiredChecksFor("tweede"))
        assertEquals("robbert@example.com", catalog.updatedBy())
        assertEquals(updated, catalog.yaml())
    }

    @Test
    fun `save weigert ongeldige YAML en een project zonder mergepolicy zonder iets te wijzigen`() {
        val repository = InMemoryRepository()
        val catalog = ReloadableProjectCatalog(repository, seed = ProjectConfiguration.parseYamlText(catalogYaml))

        val invalidYaml = assertFailsWith<IllegalArgumentException> { catalog.save("projects: [\n", "x") }
        assert(invalidYaml.message!!.startsWith("Ongeldige YAML")) { invalidYaml.message!! }
        assertFailsWith<IllegalArgumentException> { catalog.save("geen: projectenlijst", "x") }
        val missingPolicy = assertFailsWith<IllegalArgumentException> {
            catalog.save("projects:\n  - name: kaal\n    repo: https://github.com/robbert/kaal\n", "x")
        }
        assert(missingPolicy.message!!.contains("merge.requiredChecks")) { missingPolicy.message!! }
        assertFailsWith<IllegalArgumentException> { catalog.save("   ", "x") }

        assertEquals(listOf("sample"), catalog.projectNames())
        assertEquals(0, repository.writes)
    }
}
