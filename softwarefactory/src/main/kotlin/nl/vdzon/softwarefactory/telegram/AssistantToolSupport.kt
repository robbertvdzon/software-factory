package nl.vdzon.softwarefactory.telegram

import org.springframework.boot.web.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component
import java.util.UUID

/** Onthoudt de poort waarop de ingebouwde webserver draait, zodat het assistent-script localhost kan bereiken. */
@Component
class WebServerPortHolder : ApplicationListener<WebServerInitializedEvent> {
    @Volatile
    var port: Int = 8080

    override fun onApplicationEvent(event: WebServerInitializedEvent) {
        port = event.webServer.port
    }
}

/**
 * Gedeeld geheim tussen het interne assistent-endpoint en het `sf-youtrack`-script. Bij elke start
 * een nieuwe random waarde; alleen processen die 'm via de env meekrijgen (de claude-subprocess)
 * kunnen het endpoint aanroepen.
 */
@Component
class AssistantToolToken {
    val value: String = UUID.randomUUID().toString()
}
