package nl.vdzon.softwarefactory.config

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

@Configuration
class FactorySecretsConfiguration {
    @Bean
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
