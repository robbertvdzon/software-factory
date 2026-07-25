package nl.vdzon.softwarefactory.maintenance.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

/** Dekt de pure parsing-logica van [GitHubReleaseCleanupClient] zonder een echte HTTP-call. */
class GitHubReleaseCleanupClientTest {

    private val objectMapper = jacksonObjectMapper()

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
}
