package nl.vdzon.softwarefactory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class SoftwareFactoryApplication

fun main(args: Array<String>) {
    runApplication<SoftwareFactoryApplication>(*args)
}
