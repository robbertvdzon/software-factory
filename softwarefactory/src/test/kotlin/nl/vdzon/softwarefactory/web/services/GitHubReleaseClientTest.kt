package nl.vdzon.softwarefactory.dashboard.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dekt de pure parsing-logica van [GitHubReleaseClient] (`.apk`-assets uit één release-node halen)
 * zonder een echte HTTP-call — zelfde recept als [GitHubActionsClientTest].
 */
class GitHubReleaseClientTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `haalt apk-assets uit één release`() {
        val release = objectMapper.readTree(
            """
            {
              "tag_name": "wind-latest",
              "html_url": "https://github.com/robbert/ra/releases/tag/wind-latest",
              "published_at": "2026-07-10T08:00:00Z",
              "assets": [
                {"name": "app-release.apk", "size": 12345, "created_at": "2026-07-10T08:00:05Z", "browser_download_url": "https://example.com/wind.apk"},
                {"name": "app-release.aab", "size": 999, "browser_download_url": "https://example.com/wind.aab"}
              ]
            }
            """.trimIndent(),
        )

        val downloads = GitHubReleaseClient.apkDownloadsFromRelease(release, "robbert/ra", "RA")

        assertEquals(1, downloads.size)
        val apk = downloads.single()
        assertEquals("app-release.apk", apk.name)
        assertEquals("robbert/ra", apk.repository)
        assertEquals("RA", apk.projectKey)
        assertEquals("wind-latest", apk.releaseTag)
        assertEquals("https://github.com/robbert/ra/releases/tag/wind-latest", apk.releaseUrl)
        assertEquals("2026-07-10T08:00:05Z", apk.createdAt)
        assertEquals(12345L, apk.size)
    }

    @Test
    fun `valt terug op published_at als het asset geen created_at heeft`() {
        val release = objectMapper.readTree(
            """
            {
              "tag_name": "notities-latest",
              "published_at": "2026-07-10T09:00:00Z",
              "assets": [{"name": "app-release.apk", "browser_download_url": "https://example.com/n.apk"}]
            }
            """.trimIndent(),
        )

        val downloads = GitHubReleaseClient.apkDownloadsFromRelease(release, "robbert/ra", "RA")

        assertEquals("2026-07-10T09:00:00Z", downloads.single().createdAt)
    }

    @Test
    fun `release zonder apk-assets levert een lege lijst`() {
        val release = objectMapper.readTree(
            """{"tag_name": "v1", "assets": [{"name": "readme.txt"}]}""",
        )

        assertEquals(0, GitHubReleaseClient.apkDownloadsFromRelease(release, "robbert/ra", "RA").size)
    }

    @Test
    fun `meerdere releases (bv drie apps met eigen vaste tag) leveren elk hun eigen apk op`() {
        val releases = objectMapper.readTree(
            """
            [
              {"tag_name": "wind-latest", "assets": [{"name": "app-release.apk", "browser_download_url": "https://example.com/wind.apk"}]},
              {"tag_name": "robberts-assistent-latest", "assets": [{"name": "app-release.apk", "browser_download_url": "https://example.com/ra.apk"}]},
              {"tag_name": "notities-latest", "assets": [{"name": "app-release.apk", "browser_download_url": "https://example.com/notities.apk"}]}
            ]
            """.trimIndent(),
        )

        val downloads = releases.flatMap { GitHubReleaseClient.apkDownloadsFromRelease(it, "robbert/ra", "RA") }

        assertEquals(3, downloads.size)
        assertEquals(setOf("wind-latest", "robberts-assistent-latest", "notities-latest"), downloads.map { it.releaseTag }.toSet())
    }
}
