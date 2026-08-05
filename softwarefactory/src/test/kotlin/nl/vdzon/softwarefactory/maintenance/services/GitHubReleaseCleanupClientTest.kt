package nl.vdzon.softwarefactory.maintenance.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dekt de pure parsing-logica van [GitHubReleaseCleanupClient] plus de paginatie eromheen (SF-1938),
 * beide zonder een echte HTTP-call.
 */
class GitHubReleaseCleanupClientTest {

    private val objectMapper = jacksonObjectMapper()
    private val requestedPages = mutableListOf<Int>()

    @Test
    fun `parseert releases uit een releases-array`() {
        val body = objectMapper.readTree(
            """
            [
              {"id": 1, "tag_name": "apk-20260101-abc", "published_at": "2026-01-01T00:00:00Z"},
              {"id": 2, "tag_name": "apk-20260102-def", "created_at": "2026-01-02T00:00:00Z"}
            ]
            """.trimIndent(),
        )

        val releases = GitHubReleaseCleanupClient.parseReleases(body)

        assertEquals(
            listOf(
                ReleaseInfo(1, "apk-20260101-abc", "2026-01-01T00:00:00Z"),
                ReleaseInfo(2, "apk-20260102-def", "2026-01-02T00:00:00Z"),
            ),
            releases,
        )
    }

    @Test
    fun `release zonder id of tag_name wordt overgeslagen`() {
        val body = objectMapper.readTree(
            """[{"tag_name": "no-id"}, {"id": 1}, {"id": 2, "tag_name": "ok"}]""",
        )

        val releases = GitHubReleaseCleanupClient.parseReleases(body)

        assertEquals(listOf(ReleaseInfo(2, "ok", null)), releases)
    }

    @Test
    fun `listReleases pagineert net als de package-client en stopt bij de deelpagina`() {
        val sizes = mapOf(1 to 100, 2 to 100, 3 to 37)

        val releases = client().listReleasesWith("robbert/sf") { page ->
            requestedPages += page
            releasePage(page, sizes.getValue(page))
        }

        assertEquals(237, releases.size)
        assertEquals(listOf(1, 2, 3), requestedPages)
        assertEquals(237, releases.map { it.id }.distinct().size)
    }

    @Test
    fun `listReleases geeft bij een mislukte vervolgpagina terug wat al is opgehaald`() {
        val releases = client().listReleasesWith("robbert/sf") { page ->
            requestedPages += page
            if (page == 2) GitHubPage.Failed else releasePage(page, 100)
        }

        assertEquals(100, releases.size)
        assertEquals(listOf(1, 2), requestedPages)
    }

    @Test
    fun `listReleases levert een lege lijst bij een mislukte eerste pagina`() {
        val releases = client().listReleasesWith("robbert/sf") { GitHubPage.Failed }

        assertEquals(emptyList<ReleaseInfo>(), releases)
    }

    @Test
    fun `listReleases respecteert de ingestelde paginagrens`() {
        val releases = client(MaintenanceCleanupSettings(githubPageLimit = 2)).listReleasesWith("robbert/sf") { page ->
            requestedPages += page
            releasePage(page, 100)
        }

        assertEquals(200, releases.size)
        assertEquals(listOf(1, 2), requestedPages)
    }

    private fun client(settings: MaintenanceCleanupSettings = MaintenanceCleanupSettings()) =
        GitHubReleaseCleanupClient(secrets(), settings = settings)

    private fun releasePage(page: Int, size: Int): GitHubPage<ReleaseInfo> =
        GitHubPage.Fetched(List(size) { ReleaseInfo(page * 1000L + it, "v$page.$it", "2026-01-01T00:00:00Z") })

    private fun secrets() = FactorySecrets(
        trackerProjects = emptyList(),
        githubToken = "token",
        factoryDatabaseUrl = "jdbc:fake",
        factoryDatabaseSchema = "fake",
        kubeconfig = null,
        aiCredentialsDir = null,
        aiOauthToken = null,
        loadedFrom = "test",
    )
}
