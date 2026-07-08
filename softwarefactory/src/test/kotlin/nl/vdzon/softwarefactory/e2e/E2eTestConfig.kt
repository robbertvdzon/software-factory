package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.config.services.FactoryEnvironmentProvider
import nl.vdzon.softwarefactory.core.AgentRuntime
import nl.vdzon.softwarefactory.github.GitHubApi
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.containers.PostgreSQLContainer

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
     * Vervangt de `gh`-CLI ([nl.vdzon.softwarefactory.github.clients.GitHubCliClient]): deelt
     * PR-nummers uit en voert `mergePullRequest` uit als échte lokale squash-merge op de
     * [LocalGitRemote], zodat de merge/deploy-keten e2e kan draaien (zie [FakeGitHubApi]).
     */
    @Bean
    @Primary
    fun gitHubApi(): GitHubApi = FAKE_GITHUB

    /**
     * Mapt de logische projectnaam `sample` (gezet op de e2e-story's `Project`-veld) naar de lokale
     * git-remote, zodat de git-laag echt draait. Vervangt de productie-resolver die uit projects.yaml leest.
     */
    @Bean
    @Primary
    fun projectRepoResolver(): ProjectRepoResolver = ProjectRepoResolver(
        mapOf("sample" to LOCAL_REMOTE.path.toString()),
        // De handmatige goedkeur-poort (SF-192) staat in de e2e-keten uit: deze tests sturen de
        // volledige auto-keten tot merge zonder menselijke gate. De poort wordt apart unit-getest.
        manualApproveFlags = mapOf("sample" to false),
    )

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

        /** Fake GitHub-API: PR-nummers + echte lokale squash-merge op [LOCAL_REMOTE]. */
        val FAKE_GITHUB = FakeGitHubApi(LOCAL_REMOTE)

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
            "SF_POLL_INTERVAL_MS" to "100",
            "SF_POLL_INTERVAL_IDLE_MS" to "100",
            // Dispatch-tel-flake (bv. "developer 3x i.p.v. 2x" in PipelineFlowsE2eTest): de
            // completion zet `endedAt` (DB) meteen bij binnenkomst, maar schrijft de nieuwe fase
            // pas ná de repo-sync (echte git-commit/push in deze harness) naar YouTrack. De
            // "awaiting-completion-settle"-guard in SubtaskExecutionCoordinator overbrugt dat gat,
            // maar meet z'n grace vanaf `endedAt` — de completion-START, niet de zichtbare
            // fase-write. Op een zwaar belaste machine (volledige mvn-run, meerdere forks +
            // testcontainers) kan de git-sync de productie-default van 60s incidenteel
            // overschrijden; de recovery ziet de fase dan nog als "actief" en dispatcht de rol
            // een extra keer → tel-asserts flaken. De scripted agents hangen nooit (het
            // result-bestand staat er altijd direct), dus crash-recovery is in deze e2e-tests
            // niet nodig: een ruime settle-grace neemt de hele race-klasse weg zonder
            // productie-gedrag te maskeren.
            "SF_ACTIVE_PHASE_RECOVERY_DELAY_MS" to "600000",
        )
    }
}
