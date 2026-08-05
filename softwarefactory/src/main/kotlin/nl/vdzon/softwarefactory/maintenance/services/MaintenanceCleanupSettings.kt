package nl.vdzon.softwarefactory.maintenance.services

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Config van [MaintenanceCleanupScheduler]. De twee knoppen zitten bewust in één bean i.p.v. los in
 * de constructor van de scheduler: dat hield die constructor onder de parametergrens van de
 * quality-ratchet, zonder de bestaande `sf.maintenance.*`-propertystijl los te laten.
 *
 * [retentionDays] geldt voor de historie in `maintenance_cleanup_runs` (zie migratie `V30`), niet
 * voor de releases/images zelf — die retentie staat per project in `projects.yaml`.
 *
 * [githubPageLimit] begrenst de paginatielus van de GitHub-clients (zie [GitHubPagination]): 20
 * pagina's van 100 = 2000 items per lijst. De defaults staan ook in Kotlin zelf zodat de clients
 * deze bean als optionele constructorparameter kunnen nemen zonder dat handgeschreven testfakes
 * hem moeten meegeven.
 */
@Component
data class MaintenanceCleanupSettings(
    @Value("\${sf.maintenance.dry-run:false}") val dryRun: Boolean = false,
    @Value("\${sf.maintenance.run-retention-days:90}") val retentionDays: Long = 90,
    @Value("\${sf.maintenance.github-page-limit:20}") val githubPageLimit: Int = 20,
)
