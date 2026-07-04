package nl.vdzon.softwarefactory.dashboard.bridge

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/** Registreert [BridgeHub] op `/bridge` — het endpoint waarop de factory uitgaand verbindt. */
@Configuration
@EnableWebSocket
class BridgeWebSocketConfig(private val hub: BridgeHub) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(hub, "/bridge")
    }
}
