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
 */
@Component
data class MaintenanceCleanupSettings(
    @Value("\${sf.maintenance.dry-run:false}") val dryRun: Boolean,
    @Value("\${sf.maintenance.run-retention-days:90}") val retentionDays: Long,
)
