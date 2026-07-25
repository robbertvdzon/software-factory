package nl.vdzon.softwarefactory.maintenance.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

/** Dekt de pure parsing-logica van [GitHubPackageCleanupClient] zonder een echte HTTP-call. */
class GitHubPackageCleanupClientTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `parseert package-versions met tags`() {
        val body = objectMapper.readTree(
            """
            [
              {"id": 1, "created_at": "2026-01-01T00:00:00Z", "metadata": {"container": {"tags": ["sha-aa68624", "main"]}}},
              {"id": 2, "created_at": "2026-01-02T00:00:00Z", "metadata": {"container": {"tags": []}}}
            ]
            """.trimIndent(),
        )

        val versions = GitHubPackageCleanupClient.parseVersions(body)

        assertEquals(
            listOf(
                PackageVersionInfo(1, "2026-01-01T00:00:00Z", listOf("sha-aa68624", "main")),
                PackageVersionInfo(2, "2026-01-02T00:00:00Z", emptyList()),
            ),
            versions,
        )
    }

    @Test
    fun `version zonder id wordt overgeslagen`() {
        val body = objectMapper.readTree("""[{"created_at": "2026-01-01T00:00:00Z"}, {"id": 5}]""")

        val versions = GitHubPackageCleanupClient.parseVersions(body)

        assertEquals(listOf(PackageVersionInfo(5, null, emptyList())), versions)
    }

    @Test
    fun `ontbrekende metadata levert lege tags-lijst op`() {
        val body = objectMapper.readTree("""[{"id": 1}]""")

        assertEquals(listOf(PackageVersionInfo(1, null, emptyList())), GitHubPackageCleanupClient.parseVersions(body))
    }
}
