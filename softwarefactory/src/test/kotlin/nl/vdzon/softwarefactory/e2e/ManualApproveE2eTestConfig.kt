package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Variant op [E2eTestConfig] die de handmatige goedkeur-poort (SF-192) **aan** zet voor het
 * `sample`-project. [E2eTestConfig] zet de poort bewust uit (de meeste e2e-tests sturen de hele
 * auto-keten tot merge zonder menselijke gate); deze config is er specifiek om de poort zélf
 * end-to-end te dekken ([ManualApproveGateE2eTest]).
 *
 * Hergebruikt alle overige buitenrand-dubbels van [E2eTestConfig] (Postgres-tracker-teststate,
 * scripted runtime, Testcontainer-Postgres) en overschrijft enkel de [ProjectRepoResolver] met
 * `manualApprove = true`.
 */
@TestConfiguration
class ManualApproveE2eTestConfig : E2eTestConfig() {

    @Bean
    @Primary
    override fun projectRepoResolver(): ProjectRepoResolver = ProjectRepoResolver(
        mapOf("sample" to LOCAL_REMOTE.path.toString()),
        manualApproveFlags = mapOf("sample" to true),
        requiredChecks = mapOf("sample" to setOf("E2E verification")),
    )
}
