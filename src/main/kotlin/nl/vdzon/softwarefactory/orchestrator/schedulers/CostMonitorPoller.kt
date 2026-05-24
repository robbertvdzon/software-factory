package nl.vdzon.softwarefactory.orchestrator.schedulers

import nl.vdzon.softwarefactory.orchestrator.models.OrchestratorSettings
import nl.vdzon.softwarefactory.orchestrator.services.CostMonitorService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CostMonitorPoller(
    private val costMonitorService: CostMonitorService,
    private val settings: OrchestratorSettings,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "#{@orchestratorSettings.costMonitorInterval.toMillis()}")
    fun poll() {
        if (!settings.pollingEnabled) {
            return
        }

        try {
            costMonitorService.checkAllActiveStories()
        } catch (exception: Exception) {
            logger.warn("Cost monitor poll failed.", exception)
        }
    }
}
