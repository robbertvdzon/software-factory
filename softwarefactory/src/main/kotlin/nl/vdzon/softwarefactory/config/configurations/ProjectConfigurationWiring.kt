package nl.vdzon.softwarefactory.config.configurations

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.config.ProjectCatalog
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.config.services.ProjectCatalogRepository
import nl.vdzon.softwarefactory.config.services.ReloadableProjectCatalog
import nl.vdzon.softwarefactory.config.services.SecretsEnvLoader
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.io.path.Path

@Configuration
class ProjectConfigurationWiring(
    private val configApi: ConfigApi,
) {
    /**
     * De projectcatalogus komt uit de database (zie [ReloadableProjectCatalog]). Een nog lege
     * database wordt eenmalig gevuld uit het vroegere `projects.yaml`, standaard naast de andere
     * config; `SF_PROJECTS_FILE` overschrijft dat pad. Tests die zelf een [ProjectConfiguration]-bean
     * leveren, blijven die als (primaire) poort gebruiken; de catalogus start dan met dezelfde inhoud.
     */
    @Bean
    fun projectCatalog(repository: ProjectCatalogRepository, seed: ObjectProvider<ProjectConfiguration>): ProjectCatalog {
        val importFile = configApi.resolvedValues()["SF_PROJECTS_FILE"]?.takeIf { it.isNotBlank() }?.let { Path(it) }
            ?: SecretsEnvLoader.defaultSecretsFile().resolveSibling("projects.yaml")
        return ReloadableProjectCatalog(repository, importFile, seed.ifAvailable)
    }
}
