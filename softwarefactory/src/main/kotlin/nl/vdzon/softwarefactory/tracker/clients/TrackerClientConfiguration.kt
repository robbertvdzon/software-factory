package nl.vdzon.softwarefactory.tracker.clients

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import nl.vdzon.softwarefactory.tracker.repositories.ProcessedCommentStore
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/**
 * De actieve [TrackerCapabilities]-implementatie: [PostgresTrackerClient] tegen de factory's eigen
 * Postgres-tabellen (`V15__tracker_issues.sql`). Bewust een expliciete `@Bean`-factory (niet
 * `@Component` op `PostgresTrackerClient` zelf) zodat de indirectie via [TrackerCapabilities] behouden blijft
 * voor test-fakes en toekomstige alternatieve implementaties.
 */
@Configuration
class TrackerClientConfiguration {
    @Bean
    fun issueTrackerClient(
        factorySecrets: FactorySecrets,
        processedCommentStore: ProcessedCommentStore,
        jdbcTemplate: JdbcTemplate,
        eventPublisher: ApplicationEventPublisher,
    ): TrackerCapabilities = PostgresTrackerClient(jdbcTemplate, factorySecrets, processedCommentStore, eventPublisher)
}
