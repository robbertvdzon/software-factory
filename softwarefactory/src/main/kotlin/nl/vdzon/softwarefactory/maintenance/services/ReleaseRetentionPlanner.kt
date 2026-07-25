package nl.vdzon.softwarefactory.maintenance.services

import nl.vdzon.softwarefactory.config.ReleasePrefixRule

/**
 * Bepaalt welke releases opgeruimd mogen worden: per [ReleasePrefixRule] worden de nieuwste
 * [ReleasePrefixRule.keep] releases van die tag-prefix (op [ReleaseInfo.publishedAt] aflopend,
 * ISO-8601-strings sorteren lexicaal correct) bewaard, de rest van die groep gaat weg. Een release
 * die geen enkele geconfigureerde prefix matcht blijft altijd staan (geen regel = geen mening).
 */
object ReleaseRetentionPlanner {
    fun releasesToDelete(releases: List<ReleaseInfo>, rules: List<ReleasePrefixRule>): List<ReleaseInfo> =
        rules.flatMap { rule ->
            releases.filter { it.tagName.startsWith(rule.prefix) }
                .sortedByDescending { it.publishedAt.orEmpty() }
                .drop(rule.keep)
        }
}
