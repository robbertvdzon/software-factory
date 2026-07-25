package nl.vdzon.softwarefactory.maintenance.services

/**
 * Bepaalt welke package-versions opgeruimd mogen worden. Een version blijft staan zodra één van
 * de volgende geldt: (a) 'ie zit in de nieuwste [keep] op [PackageVersionInfo.createdAt]
 * (rolling window), (b) een tag matcht [alwaysKeepTags] (default incl. "main", de mutable
 * fallback-tag van build-images.yml), of (c) een tag matcht [protectedTags] (huidige productie-
 * manifest-sha's + open-PR-head-sha's, zie [GitHubProtectedShaSource] — anders krijgt een lopende
 * preview-PR een ImagePullBackOff zodra z'n image wordt opgeruimd). De rest gaat weg.
 */
object PackageVersionRetentionPlanner {
    fun versionsToDelete(
        versions: List<PackageVersionInfo>,
        keep: Int,
        protectedTags: Set<String>,
        alwaysKeepTags: Set<String>,
    ): List<PackageVersionInfo> {
        val recentIds = versions
            .sortedByDescending { it.createdAt.orEmpty() }
            .take(keep)
            .map { it.id }
            .toSet()
        return versions.filter { version ->
            version.id !in recentIds && version.tags.none { it in alwaysKeepTags || it in protectedTags }
        }
    }
}
