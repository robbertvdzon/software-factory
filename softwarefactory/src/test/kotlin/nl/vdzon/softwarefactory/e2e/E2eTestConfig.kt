package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.config.services.FactoryEnvironmentProvider
import nl.vdzon.softwarefactory.orchestrator.AgentRuntime
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.net.http.HttpClient

/**
 * Bootstrap voor de end-to-end integratietest (bouwstap 3 uit het e2e-plan).
 *
 * Vervangt de drie buitenranden van de productie-keten door deterministische dubbels,
 * terwijl de rest van de Spring-app (orchestrator-loop, completion-pad, web-laag) echt draait:
 *
 *  - **Config**: een `@Primary` [FactoryEnvironmentProvider] met een vaste waarden-map plus een
 *    gelijknamige `factorySecrets`-bean (overschrijft [FactorySecrets] uit de productie-config) die
 *    naar de Testcontainer-Postgres en de embedded mock-YouTrack wijst. Geen `secrets.env`/env nodig.
 *  - **AgentRuntime**: een `@Primary` [TestAgentRuntime] in plaats van de Docker-runtime.
 *  - **YouTrack HTTP**: een [FakeYouTrackServer] die over echte HTTP praat; de echte `YouTrackClient`
 *    krijgt diens `baseUrl` via de config-override.
 *
 * De Postgres-container en de mock-server zijn statics: één instantie voor de hele test-JVM, gestart
 * vóór de Spring-context de `factorySecrets`-bean opbouwt.
 */
@TestConfiguration
class E2eTestConfig {

    @Bean(destroyMethod = "close")
    fun fakeYouTrackServer(): FakeYouTrackServer = FAKE_YOUTRACK

    @Bean
    @Primary
    fun testAgentRuntime(): AgentRuntime = TEST_AGENT_RUNTIME

    /**
     * Mapt de logische projectnaam `sample` (gezet op de e2e-story's `Project`-veld) naar de lokale
     * git-remote, zodat de git-laag echt draait. Vervangt de productie-resolver die uit projects.yaml leest.
     */
    @Bean
    @Primary
    fun projectRepoResolver(): ProjectRepoResolver = ProjectRepoResolver(mapOf("sample" to LOCAL_REMOTE.path.toString()))

    /**
     * Een [TestRestTemplate] die redirects NIET volgt. De auto-geconfigureerde variant volgt (via de
     * classpath-client) de 303 van `POST /login` door naar een `produces=text/html` GET-endpoint en
     * geeft dan een 406 op de content-onderhandeling — bovendien gaat de login-cookie van de 303
     * verloren. Met `Redirect.NEVER` ziet de test de 303 + Set-Cookie zoals bedoeld.
     */
    @Bean
    @Primary
    fun testRestTemplate(): TestRestTemplate =
        TestRestTemplate().apply {
            restTemplate.requestFactory = JdkClientHttpRequestFactory(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
            )
        }

    /**
     * Overschrijft (gelijke bean-naam `factorySecrets`) de productie-bean uit
     * `FactorySecretsConfiguration`. Wijst de datasource naar de Testcontainer-Postgres en YouTrack
     * naar de embedded mock-server. Vereist `spring.main.allow-bean-definition-overriding=true`.
     */
    @Bean(name = ["factorySecrets"])
    @Primary
    fun factorySecrets(): FactorySecrets {
        val pg = POSTGRES
        return FactorySecrets(
            youTrackBaseUrl = FAKE_YOUTRACK.baseUrl,
            youTrackToken = "test-token",
            youTrackProjects = emptyList(),
            githubToken = "test-github-token",
            // postgresql:// (geen jdbc:) zodat PostgresConnectionSettings user/pass uit de URL haalt.
            factoryDatabaseUrl = "postgresql://${pg.username}:${pg.password}@${pg.host}:${pg.firstMappedPort}/${pg.databaseName}",
            factoryDatabaseSchema = "public",
            kubeconfig = null,
            aiCredentialsDir = null,
            aiOauthToken = null,
            codexCredentialsDir = null,
            loadedFrom = "E2eTestConfig",
            autoSyncAfterAgent = false,
        )
    }

    @Bean
    @Primary
    fun factoryEnvironmentProvider(): FactoryEnvironmentProvider =
        object : FactoryEnvironmentProvider {
            override fun resolvedValues(): Map<String, String> = TEST_CONFIG_VALUES
            override fun loadSecrets(): FactorySecrets = factorySecrets()
        }

    companion object {
        /** Eén scripted agent-runtime, gedeeld zodat de test de dispatch-volgorde kan asserten. */
        val TEST_AGENT_RUNTIME = TestAgentRuntime()

        /** Lokale file-based git-remote i.p.v. GitHub: de factory kloont/pusht hier echt tegenaan (§8). */
        val LOCAL_REMOTE = LocalGitRemote()

        /**
         * Eén embedded mock-YouTrack voor de hele test-JVM; de test kan diens state direct manipuleren.
         * De project-beschrijving wijst `factory.repo` naar de lokale remote, zodat de git-laag echt
         * draait en de GitHub-PR-stap vanzelf wegvalt (lokaal pad → geen slug).
         */
        val FAKE_YOUTRACK = FakeYouTrackServer(
            FakeYouTrackState(projectDescription = "factory.repo=${LOCAL_REMOTE.path}"),
        )

        /** Eén Testcontainer-Postgres voor de hele test-JVM. */
        @JvmStatic
        val POSTGRES: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").apply { start() }

        private val TEST_CONFIG_VALUES: Map<String, String> = mapOf(
            "SF_YOUTRACK_BASE_URL" to FAKE_YOUTRACK.baseUrl,
            "SF_AI_SUPPLIER" to "mock",
            "SF_DASHBOARD_USERNAME" to "admin",
            "SF_DASHBOARD_PASSWORD" to "admin",
            "SF_POLL_INTERVAL_MS" to "100",
            "SF_POLL_INTERVAL_IDLE_MS" to "100",
        )
    }
}
