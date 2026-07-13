package nl.vdzon.softwarefactory.config.configurations

import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.config.services.SecretsEnvLoader
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.io.path.Path

@Configuration
class ProjectConfigurationWiring(
    private val configApi: ConfigApi,
) {
    /**
     * Leest de projectnaam→repo-config bij opstart in. Het bestand staat standaard naast de andere
     * config (`projects.yaml`, zusje van `secrets.env`/`properties.env`); `SF_PROJECTS_FILE`
     * overschrijft het pad. Tests die zelf een [ProjectConfiguration]-bean leveren, gaan voor.
     */
    @Bean
    @ConditionalOnMissingBean(ProjectConfiguration::class)
    fun projectConfiguration(): ProjectConfiguration {
        val path = configApi.resolvedValues()["SF_PROJECTS_FILE"]?.takeIf { it.isNotBlank() }?.let { Path(it) }
            ?: SecretsEnvLoader.defaultSecretsFile().resolveSibling("projects.yaml")
        return ProjectConfiguration.fromYaml(path)
    }
}
