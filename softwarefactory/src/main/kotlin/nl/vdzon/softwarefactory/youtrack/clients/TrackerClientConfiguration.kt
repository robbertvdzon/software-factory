package nl.vdzon.softwarefactory.youtrack.clients

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.repositories.ProcessedCommentStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Kiest de actieve [YouTrackApi]-implementatie op basis van `FactorySecrets.trackerBackend`
 * ("youtrack" default, of "postgres"). Bewust een expliciete `@Bean`-factory i.p.v.
 * `@ConditionalOnProperty`: dit project leest configuratie via [FactorySecrets]/`SecretsEnvLoader`
 * (secrets.env + procesomgeving), niet via Spring's `Environment`/`application.properties` — een
 * `@ConditionalOnProperty` op de env-var-naam zou secrets.env-waarden domweg niet zien.
 *
 * Omkeerbare schakelaar voor de YouTrack-uitfasering: `SF_TRACKER_BACKEND=postgres` zet 'm om,
 * terugzetten naar (of weglaten, want dat is de default) `youtrack` schakelt zonder dataverlies
 * terug — YouTrack zelf wordt door deze switch niet aangeraakt.
 */
@Configuration
class TrackerClientConfiguration {
    @Bean
    fun issueTrackerClient(
        factorySecrets: FactorySecrets,
        projectRepoResolver: ProjectRepoResolver,
        processedCommentStore: ProcessedCommentStore,
        jdbcTemplate: JdbcTemplate,
    ): YouTrackApi =
        when (factorySecrets.trackerBackend) {
            "postgres" -> PostgresTrackerClient(jdbcTemplate, factorySecrets, processedCommentStore)
            else -> YouTrackClient(factorySecrets, projectRepoResolver)
        }
}
