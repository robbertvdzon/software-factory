package nl.vdzon.softwarefactory.dashboard.services

import nl.vdzon.softwarefactory.core.contracts.ApkReleaseInfo
import nl.vdzon.softwarefactory.core.contracts.ApkReleaseProbe
import nl.vdzon.softwarefactory.dashboard.models.DownloadInfo
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/** Adapter voor [ApkReleaseProbe]: hergebruikt [GitHubReleaseClient] (dezelfde bron als de downloads-slice). */
@Component
class GitHubApkReleaseProbe(private val gitHubReleaseClient: GitHubReleaseClient) : ApkReleaseProbe {
    override fun newestApkReleaseAfter(repoUrl: String, projectKey: String, after: OffsetDateTime): ApkReleaseInfo? {
        val slug = GitHubSlug.fromUrl(repoUrl) ?: return null
        return newestAfter(gitHubReleaseClient.apkDownloads(slug, projectKey), after)
    }

    internal companion object {
        /** Puur/testbaar zonder HTTP: het meest recente asset dat ná [after] is aangemaakt. */
        internal fun newestAfter(downloads: List<DownloadInfo>, after: OffsetDateTime): ApkReleaseInfo? =
            downloads
                .mapNotNull { asset -> parseTimestamp(asset.createdAt)?.let { asset to it } }
                .filter { (_, createdAt) -> createdAt.isAfter(after) }
                .maxByOrNull { (_, createdAt) -> createdAt }
                ?.let { (asset, createdAt) -> ApkReleaseInfo(asset.downloadUrl, createdAt) }

        private fun parseTimestamp(value: String?): OffsetDateTime? =
            value?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
    }
}
