package nl.vdzon.softwarefactory.orchestrator.schedulers

import nl.vdzon.softwarefactory.core.OrchestratorSettings
import nl.vdzon.softwarefactory.core.CostMonitor
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CostMonitorPoller(
    private val costMonitorService: CostMonitor,
    private val settings: OrchestratorSettings,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "#{@orchestratorSettings.costMonitorInterval.toMillis()}")
    fun poll() {
        try {
            costMonitorService.checkAllActiveStories()
        } catch (exception: Exception) {
            logger.warn("Cost monitor poll failed.", exception)
        }
    }
}
