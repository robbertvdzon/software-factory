package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.DeployTarget
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.config.services.FactoryEnvironmentProvider
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
import nl.vdzon.softwarefactory.core.contracts.DeploymentStatusProbe
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.telegram.clients.TelegramClient
import nl.vdzon.softwarefactory.telegram.models.TelegramUpdate
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Bootstrap voor de end-to-end integratietest (bouwstap 3 uit het e2e-plan).
 *
 * Vervangt de buitenranden van de productie-keten door deterministische dubbels, terwijl de rest van
 * de Spring-app (orchestrator-loop, completion-pad, web-laag) echt draait:
 *
 *  - **Config**: een `@Primary` [FactoryEnvironmentProvider] met een vaste waarden-map plus een
 *    gelijknamige `factorySecrets`-bean (overschrijft [FactorySecrets] uit de productie-config) die
 *    naar de Testcontainer-Postgres wijst — de e2e-suite test zo het échte `PostgresTrackerClient`-pad.
 *    Geen `secrets.env`/env nodig.
 *  - **AgentRuntime**: een `@Primary` [TestAgentRuntime] in plaats van de Docker-runtime.
 *  - **Tracker-teststate**: [TrackerTestState] praat rechtstreeks (JDBC) met dezelfde Postgres-tabellen
 *    als de echte `PostgresTrackerClient`-bean die Spring automatisch bouwt — geen aparte Spring-wiring
 *    nodig voor de productie-kant.
 *
 * De Postgres-container en de tracker-teststate zijn statics: één instantie voor de hele test-JVM,
 * gestart vóór de Spring-context de `factorySecrets`-bean opbouwt.
 */
@TestConfiguration
class E2eTestConfig {

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
     * Test-only [TelegramClient]-dubbel (SF-1454): legt verstuurde berichten in-memory vast i.p.v.
     * echte HTTP-calls te doen. `enabled=true` en een vaste `defaultChatId` zodat
     * `TelegramNotificationService` (die op de orchestrator-poll-cadans draait, zie
     * `OrchestratorPoller`) daadwerkelijk berichten "verstuurt" tijdens de e2e-run. Subclass-patroon
     * exact zoals `TelegramNotificationServiceTest.RecordingTelegramClient`.
     */
    @Bean
    @Primary
    fun telegramClient(): TelegramClient = RECORDING_TELEGRAM_CLIENT

    /**
     * Mapt de logische projectnamen (gezet op het `Repo`-veld van de e2e-story) naar de lokale
     * git-remote, zodat de git-laag echt draait. Vervangt de productie-resolver die uit projects.yaml leest.
     *
     * `sample` is het default-project van alle bestaande e2e-tests en blijft bewust zónder
     * deploy-doelen (de DEPLOY-subtaak volgt daar de `Skip`-route). `sample-deploy` (SF-1971) wijst
     * naar dezelfde remote, maar heeft wél twee `openshift-watch`-doelen met elkaar uitsluitende
     * `matchPaths` — zie [DEPLOY_TARGET_MATCHED]/[DEPLOY_TARGET_UNMATCHED] — zodat
     * [DeployTargetsE2eTest] kan bewijzen dat alleen het doel meedoet dat de story-diff
     * ([FakeGitHubApi.CHANGED_FILES]) écht raakt.
     *
     * Beide namen krijgen `requiredChecks`: `ProjectAwarePullRequestMergeService` roept in zijn
     * `init` `requireCompleteMergePolicies()` aan, dus een projectnaam zonder policy laat de hele
     * Spring-context (en daarmee elke e2e-test) omvallen.
     */
    @Bean
    @Primary
    fun projectRepoResolver(): ProjectConfiguration = ProjectConfiguration(
        mapOf(
            "sample" to LOCAL_REMOTE.path.toString(),
            DEPLOY_PROJECT to LOCAL_REMOTE.path.toString(),
        ),
        requiredChecks = mapOf(
            "sample" to setOf("E2E verification"),
            DEPLOY_PROJECT to setOf("E2E verification"),
        ),
        deployTargets = mapOf(DEPLOY_PROJECT to listOf(DEPLOY_TARGET_MATCHED, DEPLOY_TARGET_UNMATCHED)),
    )

    /**
     * Vervangt de `kubectl`-adapter (`KubectlDeploymentStatusProbe`): de e2e-run start nooit een
     * extern proces. Alleen het deployment van [DEPLOY_TARGET_MATCHED] rapporteert een niet-lege
     * image (= "live" voor `openshiftWatchReady`); dat van [DEPLOY_TARGET_UNMATCHED] geeft `null`
     * (= status niet opvraagbaar, dus nooit klaar). Een deploy die tóch op dat tweede doel wacht,
     * bereikt `deploy-approved` dus nooit — precies het faalsignaal dat [DeployTargetsE2eTest] nodig
     * heeft. `argoApplicationStatus`/`runningPod` blijven op hun default `null`: de doelen hebben
     * bewust geen ArgoCD-config, zodat de image-heuristiek geldt.
     */
    @Bean
    @Primary
    fun deploymentStatusProbe(): DeploymentStatusProbe = DeploymentStatusProbe { namespace, deployment ->
        if (namespace == DEPLOY_NAMESPACE && deployment == DEPLOY_MATCHED_DEPLOYMENT) DEPLOY_MATCHED_IMAGE else null
    }

    /**
     * Overschrijft (gelijke bean-naam `factorySecrets`) de productie-bean uit
     * `FactorySecretsConfiguration`. Wijst de datasource naar de Testcontainer-Postgres, zodat
     * `TrackerClientConfiguration` een échte `PostgresTrackerClient` bouwt. Vereist
     * `spring.main.allow-bean-definition-overriding=true`.
     */
    @Bean(name = ["factorySecrets"])
    @Primary
    fun factorySecrets(): FactorySecrets {
        val pg = POSTGRES
        return FactorySecrets(
            // Zonder dit gooit PostgresTrackerClient.ensureConfiguredProjects() bij een lege issues-
            // tabel (fris gestart, nog geen story) — de dashboard-endpoints roepen die aan.
            trackerProjects = listOf(TRACKER_STATE.projectKey),
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
        /** Projectnaam (story-veld `Repo`) met deploy-doelen; zie [projectRepoResolver] (SF-1971). */
        const val DEPLOY_PROJECT = "sample-deploy"

        /** Namespace van beide deploy-doelen van [DEPLOY_PROJECT]; ze verschillen in deployment-naam. */
        private const val DEPLOY_NAMESPACE = "sample-deploy-e2e"

        /** Deployment-naam van [DEPLOY_TARGET_MATCHED] resp. [DEPLOY_TARGET_UNMATCHED]. */
        private const val DEPLOY_MATCHED_DEPLOYMENT = "sample-deploy-backend"
        private const val DEPLOY_UNMATCHED_DEPLOYMENT = "sample-deploy-frontend"

        /** De image die de test-[DeploymentStatusProbe] voor [DEPLOY_TARGET_MATCHED] rapporteert. */
        const val DEPLOY_MATCHED_IMAGE = "registry.invalid/$DEPLOY_MATCHED_DEPLOYMENT:e2e"

        /**
         * Ruim (30 min): in het faalscenario moet de await-timeout van de test het signaal zijn,
         * niet een `deploy-failed` door een krappe deploy-timeout op trage CI.
         */
        private const val DEPLOY_TIMEOUT_MINUTES = 30

        /**
         * Deploy-doel dat de gefakete story-diff ([FakeGitHubApi.CHANGED_FILES]) wél raakt. Bewust
         * zonder `argocdApp`/`argocdNamespace`: dan geldt de image-heuristiek van
         * `openshiftWatchReady` en speelt de SHA-verificatie geen rol.
         */
        val DEPLOY_TARGET_MATCHED = DeployTarget(
            name = DEPLOY_MATCHED_DEPLOYMENT,
            matchPaths = listOf("backend/"),
            config = DeployConfig.OpenshiftWatch(
                namespace = DEPLOY_NAMESPACE,
                deployment = DEPLOY_MATCHED_DEPLOYMENT,
                timeoutMinutes = DEPLOY_TIMEOUT_MINUTES,
            ),
        )

        /**
         * Deploy-doel dat diezelfde story-diff níet raakt; de test-probe geeft er nooit een image
         * voor terug. Doet dit doel tóch mee (fail-open op een onbepaalbare diff), dan blijft de
         * DEPLOY-subtaak eeuwig in `deploying` hangen.
         */
        val DEPLOY_TARGET_UNMATCHED = DeployTarget(
            name = DEPLOY_UNMATCHED_DEPLOYMENT,
            matchPaths = listOf("frontend/"),
            config = DeployConfig.OpenshiftWatch(
                namespace = DEPLOY_NAMESPACE,
                deployment = DEPLOY_UNMATCHED_DEPLOYMENT,
                timeoutMinutes = DEPLOY_TIMEOUT_MINUTES,
            ),
        )

        /** Eén scripted agent-runtime, gedeeld zodat de test de dispatch-volgorde kan asserten. */
        val TEST_AGENT_RUNTIME = TestAgentRuntime()

        /** Lokale file-based git-remote i.p.v. GitHub: de factory kloont/pusht hier echt tegenaan (§8). */
        val LOCAL_REMOTE = LocalGitRemote()

        /** Fake GitHub-API: PR-nummers + echte lokale squash-merge op [LOCAL_REMOTE]. */
        val FAKE_GITHUB = FakeGitHubApi(LOCAL_REMOTE)

        /** Eén test-Telegram-client, gedeeld zodat de test verstuurde berichten kan asserten. */
        val RECORDING_TELEGRAM_CLIENT = RecordingTelegramClient()

        /** Eén Testcontainer-Postgres voor de hele test-JVM. */
        @JvmStatic
        val POSTGRES: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").apply { start() }

        /**
         * JDBC-backed tracker-teststate voor de hele test-JVM; de test kan 'm direct manipuleren
         * (story aanmaken, veld zetten) en de orchestrator schrijft ernaartoe via de échte
         * `PostgresTrackerClient`-bean (zelfde Postgres-tabellen, zie [TrackerTestState]).
         */
        val TRACKER_STATE = TrackerTestState(POSTGRES)

        private val TEST_CONFIG_VALUES: Map<String, String> = mapOf(
            "SF_AI_SUPPLIER" to "mock",
            "SF_POLL_INTERVAL_MS" to "100",
            // Dispatch-tel-flake (bv. "developer 3x i.p.v. 2x" in PipelineFlowsE2eTest): de
            // completion zet `endedAt` (DB) meteen bij binnenkomst, maar schrijft de nieuwe fase
            // pas ná de repo-sync (echte git-commit/push in deze harness) naar de tracker. De
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

/**
 * Test-only [TelegramClient]-dubbel (SF-1454), subclass-patroon exact zoals
 * `TelegramNotificationServiceTest.RecordingTelegramClient`: `enabled=true`, vaste `defaultChatId`,
 * `sendMessage` legt de tekst vast en geeft een oplopend message-id terug. Gedeelde static
 * (zie [E2eTestConfig.RECORDING_TELEGRAM_CLIENT]); [reset] wordt door
 * [E2eTestBase.resetSharedState] aangeroepen zodat elke test met een lege berichtenlijst begint.
 *
 * SF-1615: ook [getUpdates] en [sendPhoto] zijn overschreven. Zonder een eigen `getUpdates` viel de
 * `TelegramPoller`-thread terug op de productie-implementatie, die zonder bot-token meteen een lege
 * lijst teruggeeft — de poller-loop spinde dan zonder pauze door en vuurde onafgebroken
 * `telegram_state`-queries op de Testcontainers-Postgres af.
 */
class RecordingTelegramClient : TelegramClient(TEST_SECRETS) {
    val messages: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Verstuurde foto's (SF-1615), uitleesbaar vanuit tests; geleegd door [reset]. */
    val photos: MutableList<SentPhoto> = java.util.Collections.synchronizedList(mutableListOf())

    private val counter = AtomicLong(0)

    /** Altijd leeg: de e2e-suite voert geen inkomende Telegram-updates op (zie [getUpdates]). */
    private val incoming = LinkedBlockingQueue<TelegramUpdate>()

    override val enabled: Boolean get() = true
    override val defaultChatId: String get() = "e2e-chat-default"

    override fun sendMessage(text: String, replyToMessageId: Long?, chatId: String?): Long {
        messages += text
        return counter.incrementAndGet()
    }

    /**
     * Blokkeert kort (zoals een echte long-poll) i.p.v. meteen leeg terug te geven, zodat de
     * `TelegramPoller`-thread niet spint. Een `InterruptedException` propageert bewust door:
     * `TelegramPoller.loop` breekt daarop af — precies het gewenste `@PreDestroy`-shutdownpad.
     */
    override fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TelegramUpdate> {
        incoming.poll(POLL_BLOCK_MILLIS, TimeUnit.MILLISECONDS)
        return emptyList()
    }

    /** Legt de "verstuurde" foto in-memory vast i.p.v. een multipart-upload te doen. */
    override fun sendPhoto(chatId: String, file: Path, caption: String?): Boolean {
        photos += SentPhoto(chatId = chatId, fileName = file.fileName.toString(), caption = caption)
        return true
    }

    fun reset() {
        messages.clear()
        photos.clear()
        counter.set(0)
        incoming.clear()
    }

    /** Eén vastgelegde [sendPhoto]-aanroep. */
    data class SentPhoto(val chatId: String, val fileName: String, val caption: String?)

    private companion object {
        /**
         * Lang genoeg om het spinnen weg te nemen, kort genoeg om test-shutdown niet te vertragen
         * (richtwaarde uit SF-1615: 100–500 ms).
         */
        const val POLL_BLOCK_MILLIS = 200L

        val TEST_SECRETS = FactorySecrets(
            trackerProjects = emptyList(),
            githubToken = "test-github-token",
            factoryDatabaseUrl = "jdbc:postgresql://localhost/e2e-telegram-test",
            factoryDatabaseSchema = "public",
            kubeconfig = null,
            aiCredentialsDir = null,
            aiOauthToken = null,
            loadedFrom = "E2eTestConfig.RecordingTelegramClient",
        )
    }
}
