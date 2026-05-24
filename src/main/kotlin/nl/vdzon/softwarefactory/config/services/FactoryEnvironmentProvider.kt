package nl.vdzon.softwarefactory.config

import org.springframework.stereotype.Component

interface FactoryEnvironmentProvider {
    fun resolvedValues(): Map<String, String>
}

@Component
class SecretsFileFactoryEnvironmentProvider : FactoryEnvironmentProvider {
    override fun resolvedValues(): Map<String, String> =
        SecretsEnvLoader().resolvedValues()
}
