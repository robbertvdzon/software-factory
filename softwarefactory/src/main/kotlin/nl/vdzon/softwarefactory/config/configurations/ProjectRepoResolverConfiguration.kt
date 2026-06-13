package nl.vdzon.softwarefactory.config.configurations

import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.config.services.SecretsEnvLoader
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.io.path.Path

@Configuration
class ProjectRepoResolverConfiguration {
    /**
     * Leest de projectnaam→repo-config bij opstart in. Het bestand staat standaard naast de andere
     * config (`projects.yaml`, zusje van `secrets.env`/`properties.env`); `SF_PROJECTS_FILE`
     * overschrijft het pad. Tests die zelf een [ProjectRepoResolver]-bean leveren, gaan voor.
     */
    @Bean
    @ConditionalOnMissingBean(ProjectRepoResolver::class)
    fun projectRepoResolver(): ProjectRepoResolver {
        val path = System.getenv("SF_PROJECTS_FILE")?.takeIf { it.isNotBlank() }?.let { Path(it) }
            ?: SecretsEnvLoader.defaultSecretsFile().resolveSibling("projects.yaml")
        return ProjectRepoResolver.fromYaml(path)
    }
}
