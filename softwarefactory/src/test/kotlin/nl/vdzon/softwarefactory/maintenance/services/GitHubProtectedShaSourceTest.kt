package nl.vdzon.softwarefactory.maintenance.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dekt de pure parsing-/extractielogica van [GitHubProtectedShaSource] zonder een echte HTTP-call. */
class GitHubProtectedShaSourceTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `extraheert sha-tags uit kustomization-achtige tekst`() {
        val content = """
            images:
            - name: backend
              newTag: sha-aa68624
            - name: frontend
              newTag: sha-1279c1e
        """.trimIndent()

        assertEquals(setOf("sha-aa68624", "sha-1279c1e"), GitHubProtectedShaSource.extractShaTags(content))
    }

    @Test
    fun `tekst zonder sha-vermelding levert een lege set op`() {
        assertEquals(emptySet(), GitHubProtectedShaSource.extractShaTags("geen sha's hier"))
    }

    @Test
    fun `decodeert base64-content uit een contents-response`() {
        val encoded = Base64.getEncoder().encodeToString("newTag: sha-aa68624".toByteArray())
        val body = objectMapper.readTree("""{"content": "$encoded"}""")

        assertEquals("newTag: sha-aa68624", GitHubProtectedShaSource.decodeContent(body))
    }

    @Test
    fun `ontbrekende content levert null op`() {
        assertNull(GitHubProtectedShaSource.decodeContent(objectMapper.readTree("""{}""")))
    }

    @Test
    fun `haalt head-sha's uit open pull requests`() {
        val body = objectMapper.readTree(
            """[{"head": {"sha": "1105a564086950593f6a9b69ce1008d0e3f0b201"}}, {"head": {}}]""",
        )

        assertEquals(listOf("1105a564086950593f6a9b69ce1008d0e3f0b201"), GitHubProtectedShaSource.parseOpenPullRequestHeadShas(body))
    }

    @Test
    fun `korte sha-tag pakt de eerste zeven tekens`() {
        assertEquals("sha-1105a56", GitHubProtectedShaSource.shortShaTag("1105a564086950593f6a9b69ce1008d0e3f0b201"))
    }

    @Test
    fun `de open pull requests worden over alle pagina's opgehaald`() {
        val sizes = mapOf(1 to 100, 2 to 12)
        val requestedPages = mutableListOf<Int>()

        val pulls = source().openPullRequestHeadShasWith { page ->
            requestedPages += page
            GitHubPage.Fetched(List(sizes.getValue(page)) { "sha$page-$it" })
        }

        assertEquals(112, pulls.items.size)
        assertEquals(listOf(1, 2), requestedPages)
        assertTrue(pulls.complete, "een volledig doorlopen lijst hoort compleet te heten")
    }

    /** Een veiligheidslijst mag niet stilzwijgend halveren: een gefaalde pagina maakt het resultaat incompleet. */
    @Test
    fun `een mislukte pagina maakt de beschermingslijst incompleet`() {
        val pulls = source().openPullRequestHeadShasWith { page ->
            if (page == 2) GitHubPage.Failed else GitHubPage.Fetched(List(100) { "sha-$it" })
        }

        assertEquals(100, pulls.items.size)
        assertFalse(pulls.complete, "een gefaalde pagina hoort de lijst als incompleet te markeren")
    }

    @Test
    fun `de paginagrens maakt de beschermingslijst óók incompleet`() {
        val pulls = source(MaintenanceCleanupSettings(githubPageLimit = 2))
            .openPullRequestHeadShasWith { GitHubPage.Fetched(List(100) { "sha-$it" }) }

        assertEquals(200, pulls.items.size)
        assertFalse(pulls.complete, "een afgekapte lijst is net zo onveilig als een gefaalde pagina")
    }

    private fun source(settings: MaintenanceCleanupSettings = MaintenanceCleanupSettings()) =
        GitHubProtectedShaSource(secrets(), settings = settings)

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
