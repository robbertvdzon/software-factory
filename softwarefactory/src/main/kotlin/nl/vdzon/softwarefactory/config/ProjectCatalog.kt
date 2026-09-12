package nl.vdzon.softwarefactory.config

import java.time.OffsetDateTime

/**
 * De projectcatalogus zoals de factory die nu gebruikt: dezelfde YAML-vorm als `projects.yaml`,
 * maar opgeslagen in de database en zonder herstart te vervangen via het dashboard. Alle
 * project-poorten ([ProjectRepositoryCatalog], [ProjectMergePolicy], ...) lezen live uit de
 * actuele catalogus; [save] valideert het document vóór het bewaren en maakt het direct actief.
 */
interface ProjectCatalog : ProjectAssistantSettings, ProjectMergePolicy, ProjectDashboardSettings, ProjectReleaseCleanupSettings {
    /** Het opgeslagen YAML-document, of null zolang er nog niets is bewaard of geïmporteerd. */
    fun yaml(): String?
    fun updatedAt(): OffsetDateTime?
    fun updatedBy(): String?

    /** Valideert [yaml], bewaart het en maakt het meteen actief. Ongeldig => [IllegalArgumentException]. */
    fun save(yaml: String, updatedBy: String): ProjectConfiguration
}
