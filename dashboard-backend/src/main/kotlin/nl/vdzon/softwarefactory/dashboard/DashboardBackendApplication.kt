package nl.vdzon.softwarefactory.dashboard

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DashboardBackendApplication

fun main(args: Array<String>) {
    runApplication<DashboardBackendApplication>(*args)
}
