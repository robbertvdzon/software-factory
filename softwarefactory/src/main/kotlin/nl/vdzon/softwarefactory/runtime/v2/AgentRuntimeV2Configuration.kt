package nl.vdzon.softwarefactory.runtime.v2

import nl.vdzon.softwarefactory.config.ConfigApi
import org.springframework.boot.web.client.RestClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import java.time.Duration

data class AgentRuntimeV2Settings(
    val baseUrl: String,
    val token: String?,
    val pollInterval: Duration,
    val maxRepairAttempts: Int,
    val testAccessKeys: Map<String, String> = emptyMap(),
)

@Configuration
class AgentRuntimeV2Configuration {
    @Bean
    fun agentRuntimeV2Settings(config: ConfigApi): AgentRuntimeV2Settings {
        val values = config.resolvedValues()
        return AgentRuntimeV2Settings(
            baseUrl = values["SF_AGENT_RUNTIME_URL"]?.trim()?.removeSuffix("/")
                ?: "https://agent-runtime.vdzonsoftware.nl",
            token = values["SF_AGENT_RUNTIME_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() },
            pollInterval = Duration.ofMillis(
                values["SF_AGENT_RUNTIME_POLL_MS"]?.toLongOrNull()?.takeIf { it >= 250 } ?: 2_000,
            ),
            testAccessKeys = TestAccessPolicy.parse(values["SF_TEST_ACCESS_KEYS"].orEmpty()),
            maxRepairAttempts = values["SF_AGENT_RUNTIME_MAX_REPAIR_ATTEMPTS"]
                ?.toIntOrNull()?.coerceIn(0, 5) ?: 3,
        )
    }

    @Bean
    fun agentRuntimeV2RestClient(
        builder: RestClient.Builder,
        settings: AgentRuntimeV2Settings,
        customizers: List<RestClientCustomizer>,
    ): RestClient {
        customizers.forEach { it.customize(builder) }
        builder.baseUrl(settings.baseUrl)
        settings.token?.let { builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        return builder.build()
    }

    @Bean
    fun agentRuntimeV2HttpClient(restClient: RestClient): AgentRuntimeV2HttpClient =
        AgentRuntimeV2HttpClient(restClient)

    @Bean
    fun agentRuntimeV2UploadClient(restClient: RestClient): AgentRuntimeV2UploadClient =
        AgentRuntimeV2UploadClient(restClient)
}
