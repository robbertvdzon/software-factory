package nl.vdzon.softwarefactory.config.services

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.springframework.stereotype.Component

interface FactoryEnvironmentProvider : ConfigApi

@Component
class SecretsFileFactoryEnvironmentProvider : FactoryEnvironmentProvider {
    override fun resolvedValues(): Map<String, String> =
        SecretsEnvLoader().resolvedValues()

    override fun loadSecrets(): FactorySecrets =
        SecretsEnvLoader().load()
}
