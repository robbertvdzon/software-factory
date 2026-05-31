package nl.vdzon.softwarefactory.orchestrator.schedulers

import nl.vdzon.softwarefactory.orchestrator.models.OrchestratorSettings
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
        try {
            logger.info("Start poll")
            val startTime = System.currentTimeMillis()
            val result = orchestratorService.pollOnce()
            val endTime = System.currentTimeMillis()
            logger.info("Orchestrator poll processed {} AI issue(s) in {} msec.", result.issueResults.size, endTime-startTime)
        } catch (exception: Exception) {
            logger.warn("Orchestrator poll failed.", exception)
        }
    }
}
