package nl.vdzon.softwarefactory.config.configurations

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.services.SecretsEnvLoader
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

@Configuration
class FactorySecretsConfiguration {
    // Treedt terug zodra een test (bv. E2eTestConfig) zelf een FactorySecrets-bean levert. In
    // productie bestaat die niet, dus daar wordt 'ie gewoon uit secrets.env/env-vars geladen.
    @Bean
    @ConditionalOnMissingBean(FactorySecrets::class)
    fun factorySecrets(): FactorySecrets = SecretsEnvLoader().load()
}

@Component
class FactorySecretsStartupLogger(
    private val factorySecrets: FactorySecrets,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        logger.info("Loaded factory configuration: {}", factorySecrets.redactedSummary())
    }
}
