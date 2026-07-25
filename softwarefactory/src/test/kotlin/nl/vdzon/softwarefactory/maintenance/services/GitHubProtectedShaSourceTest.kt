package nl.vdzon.softwarefactory.maintenance.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
