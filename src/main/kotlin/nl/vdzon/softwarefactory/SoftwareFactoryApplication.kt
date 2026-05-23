package nl.vdzon.softwarefactory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SoftwareFactoryApplication

fun main(args: Array<String>) {
    runApplication<SoftwareFactoryApplication>(*args)
}
