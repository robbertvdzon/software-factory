package nl.vdzon.softwarefactory.dashboard

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/** [EnableScheduling] is nodig voor de SSE-heartbeat (`BridgeApiController.sendHeartbeat`). */
@SpringBootApplication
@EnableScheduling
class DashboardBackendApplication

fun main(args: Array<String>) {
    runApplication<DashboardBackendApplication>(*args)
}
