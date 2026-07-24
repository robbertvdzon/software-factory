package nl.vdzon.softwarefactory.dashboard.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.dashboard.models.DownloadInfo
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun `haalt de commit-sha uit de release-body`() {
        val release = objectMapper.readTree(
            """
            {
              "tag_name": "wind-latest",
              "body": "Automatisch gebouwde release-APK — build 20, commit 1105a564086950593f6a9b69ce1008d0e3f0b201.",
              "assets": [{"name": "app-release.apk", "browser_download_url": "https://example.com/wind.apk"}]
            }
            """.trimIndent(),
        )

        val apk = GitHubReleaseClient.apkDownloadsFromRelease(release, "robbert/ra", "RA").single()

        assertEquals("1105a564086950593f6a9b69ce1008d0e3f0b201", apk.commitSha)
    }

    @Test
    fun `release zonder herkenbare commit-vermelding levert geen commit-sha op`() {
        val release = objectMapper.readTree(
            """
            {
              "tag_name": "wind-latest",
              "body": "Handmatige release, geen build-info.",
              "assets": [{"name": "app-release.apk", "browser_download_url": "https://example.com/wind.apk"}]
            }
            """.trimIndent(),
        )

        assertNull(GitHubReleaseClient.apkDownloadsFromRelease(release, "robbert/ra", "RA").single().commitSha)
    }

    @Test
    fun `extractCommitSha vindt de sha ongeacht omringende tekst`() {
        assertEquals(
            "cc0294f29694b27cbc6bd29d5ca86b24f74f5c98",
            GitHubReleaseClient.extractCommitSha("build 33, commit cc0294f29694b27cbc6bd29d5ca86b24f74f5c98).\nDownload..."),
        )
        assertNull(GitHubReleaseClient.extractCommitSha(null))
        assertNull(GitHubReleaseClient.extractCommitSha("geen commit-vermelding hier"))
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

/** Dekt [GitHubApkReleaseProbe]'s pure filter-/selectielogica (SF-1134) zonder HTTP-call. */
class GitHubApkReleaseProbeTest {

    private fun asset(name: String, createdAt: String?, url: String = "https://example.com/$name") = DownloadInfo(
        repository = "robbert/ra",
        projectKey = "RA",
        name = name,
        size = 1,
        createdAt = createdAt,
        downloadUrl = url,
        releaseTag = null,
        releaseUrl = null,
    )

    @Test
    fun `kiest het meest recente asset na de referentietijd`() {
        val after = OffsetDateTime.parse("2026-07-10T08:00:00Z")
        val downloads = listOf(
            asset("oud.apk", "2026-07-10T07:59:59Z"),
            asset("nieuw.apk", "2026-07-10T08:00:05Z"),
            asset("nieuwst.apk", "2026-07-10T08:05:00Z"),
        )

        val result = GitHubApkReleaseProbe.newestAfter(downloads, after)

        assertEquals("https://example.com/nieuwst.apk", result?.downloadUrl)
    }

    @Test
    fun `geen asset na de referentietijd levert null`() {
        val after = OffsetDateTime.parse("2026-07-10T08:00:00Z")
        val downloads = listOf(asset("oud.apk", "2026-07-10T07:00:00Z"))

        assertNull(GitHubApkReleaseProbe.newestAfter(downloads, after))
    }

    @Test
    fun `assets zonder (parsebare) createdAt worden genegeerd`() {
        val after = OffsetDateTime.parse("2026-07-10T08:00:00Z")
        val downloads = listOf(asset("kapot.apk", "niet-een-datum"), asset("leeg.apk", null))

        assertNull(GitHubApkReleaseProbe.newestAfter(downloads, after))
    }
}
