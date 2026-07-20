package nl.vdzon.softwarefactory.core.contracts

import java.time.OffsetDateTime

/**
 * Poort naar "is er een nieuwe .apk-release verschenen na een bepaald tijdstip" — voor projecten
 * zonder deploy-config die hun eindresultaat als GitHub-release publiceren (zie
 * [nl.vdzon.softwarefactory.pipeline.service.TelegramResultNotifyPoller], SF-1134). Leeft in
 * core.contracts zodat de pipeline-module (Modulith) 'm mag injecteren zonder een verboden
 * afhankelijkheid naar de dashboard-module; de GitHub-implementatie (hergebruikt
 * [nl.vdzon.softwarefactory.dashboard.services.GitHubReleaseClient], geen duplicatie) zit als
 * adapter in dashboard.services.
 */
fun interface ApkReleaseProbe {
    /** De meest recente `.apk`-asset van [repoUrl] die ná [after] is aangemaakt, of `null`. */
    fun newestApkReleaseAfter(repoUrl: String, projectKey: String, after: OffsetDateTime): ApkReleaseInfo?
}

data class ApkReleaseInfo(val downloadUrl: String, val createdAt: OffsetDateTime)
