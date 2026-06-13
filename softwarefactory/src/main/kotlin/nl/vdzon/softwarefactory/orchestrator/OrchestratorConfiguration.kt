package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.core.OrchestratorSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class OrchestratorConfiguration {
    @Bean
    fun orchestratorSettings(factoryEnvironmentProvider: ConfigApi): OrchestratorSettings =
        OrchestratorSettings.fromEnvironment(factoryEnvironmentProvider.resolvedValues())

    @Bean
    fun factoryClock(): Clock =
        Clock.systemUTC()
}
