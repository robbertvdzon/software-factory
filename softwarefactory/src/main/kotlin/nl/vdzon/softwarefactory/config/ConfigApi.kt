package nl.vdzon.softwarefactory.config

import nl.vdzon.softwarefactory.config.services.SecretsEnvLoader

/**
 * Public API of the config module.
 *
 * The config module owns environment resolution, secret loading, validation and
 * database/Flyway bootstrap configuration for the factory server.
 */
interface ConfigApi {
    fun resolvedValues(): Map<String, String>

    fun loadSecrets(): FactorySecrets {
        throw UnsupportedOperationException("Loading secrets is not supported by this ConfigApi.")
    }

    companion object {
        fun default(): ConfigApi = DefaultConfigApi()
    }
}

private class DefaultConfigApi : ConfigApi {
    private val loader = SecretsEnvLoader()

    override fun resolvedValues(): Map<String, String> =
        loader.resolvedValues()

    override fun loadSecrets(): FactorySecrets =
        loader.load()
}
