package nl.vdzon.softwarefactory.dashboard.bridge

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean

/** Registreert [BridgeHub] op `/bridge` — het endpoint waarop de factory uitgaand verbindt. */
@Configuration
@EnableWebSocket
class BridgeWebSocketConfig(private val hub: BridgeHub) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(hub, "/bridge")
    }

    /**
     * Tomcats default (8KB) is te klein voor grote responses (bv. `dashboard.get`/`nightly.get`
     * met veel stories/agent-runs) — de verbinding viel dan weg met "message too big" (code 1009)
     * en de betreffende schermen bleven leeg.
     */
    @Bean
    fun createWebSocketContainer(): ServletServerContainerFactoryBean =
        ServletServerContainerFactoryBean().apply {
            setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BUFFER_BYTES)
        }

    companion object {
        const val MAX_TEXT_MESSAGE_BUFFER_BYTES = 2 * 1024 * 1024
    }
}
