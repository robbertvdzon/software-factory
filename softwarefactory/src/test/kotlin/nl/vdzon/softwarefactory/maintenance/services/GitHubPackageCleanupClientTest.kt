package nl.vdzon.softwarefactory.maintenance.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dekt de pure parsing-logica van [GitHubPackageCleanupClient] plus de paginatie eromheen (SF-1938),
 * beide zonder een echte HTTP-call: `listVersionsWith` krijgt de "haal pagina n op"-functie mee.
 */
class GitHubPackageCleanupClientTest {

    private val objectMapper = jacksonObjectMapper()
    private val requestedPages = mutableListOf<Int>()

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

    @Test
    fun `listVersions voegt alle pagina's samen`() {
        val sizes = mapOf(1 to 100, 2 to 100, 3 to 37)

        val versions = client().listVersionsWith("robbert", "app") { page, _ ->
            requestedPages += page
            versionsPage(page, sizes.getValue(page))
        }

        assertEquals(237, versions.size)
        assertEquals(listOf(1, 2, 3), requestedPages)
        assertEquals(237, versions.map { it.id }.distinct().size)
    }

    @Test
    fun `listVersions geeft bij een mislukte vervolgpagina terug wat al is opgehaald`() {
        val versions = client().listVersionsWith("robbert", "app") { page, _ ->
            requestedPages += page
            if (page == 2) GitHubPage.Failed else versionsPage(page, 100)
        }

        assertEquals(100, versions.size)
        assertEquals(listOf(1, 2), requestedPages)
    }

    @Test
    fun `listVersions levert een lege lijst bij een mislukte eerste pagina`() {
        val versions = client().listVersionsWith("robbert", "app") { page, _ ->
            requestedPages += page
            GitHubPage.Failed
        }

        assertEquals(emptyList<PackageVersionInfo>(), versions)
        assertEquals(listOf(1), requestedPages)
    }

    @Test
    fun `listVersions respecteert de ingestelde paginagrens`() {
        val client = client(settings = MaintenanceCleanupSettings(githubPageLimit = 3))

        val versions = client.listVersionsWith("robbert", "app") { page, _ ->
            requestedPages += page
            versionsPage(page, 100)
        }

        assertEquals(300, versions.size)
        assertEquals(listOf(1, 2, 3), requestedPages)
    }

    /** Zonder token blijft het bij een lege lijst — en er wordt géén enkele pagina opgehaald. */
    @Test
    fun `zonder SF_GITHUB_PACKAGES_TOKEN wordt er niets opgehaald`() {
        val client = client(token = null)

        val versions = client.listVersionsWith("robbert", "app") { page, _ ->
            requestedPages += page
            versionsPage(page, 100)
        }

        assertEquals(emptyList<PackageVersionInfo>(), versions)
        assertEquals(emptyList<Int>(), requestedPages)
    }

    private fun client(
        token: String? = "packages-token",
        settings: MaintenanceCleanupSettings = MaintenanceCleanupSettings(),
    ) = GitHubPackageCleanupClient(secrets(token), settings = settings)

    private fun versionsPage(page: Int, size: Int): GitHubPage<PackageVersionInfo> =
        GitHubPage.Fetched(List(size) { PackageVersionInfo(page * 1000L + it, "2026-01-01T00:00:00Z", emptyList()) })

    private fun secrets(packagesToken: String?) = FactorySecrets(
        trackerProjects = emptyList(),
        githubToken = "token",
        factoryDatabaseUrl = "jdbc:fake",
        factoryDatabaseSchema = "fake",
        kubeconfig = null,
        githubPackagesToken = packagesToken,
        aiCredentialsDir = null,
        aiOauthToken = null,
        loadedFrom = "test",
    )
}
