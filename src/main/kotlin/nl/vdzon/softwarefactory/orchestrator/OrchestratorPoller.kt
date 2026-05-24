package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.orchestrator.services.OrchestratorService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OrchestratorPoller(
    private val orchestratorService: OrchestratorService,
    private val settings: OrchestratorSettings,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "#{@orchestratorSettings.pollInterval.toMillis()}")
    fun poll() {
        if (!settings.pollingEnabled) {
            return
        }

        try {
            val result = orchestratorService.pollOnce()
            logger.info("Orchestrator poll processed {} AI issue(s).", result.issueResults.size)
        } catch (exception: Exception) {
            logger.warn("Orchestrator poll failed.", exception)
        }
    }
}
