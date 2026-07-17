package nl.vdzon.softwarefactory.runtime.docker

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.runtime.commands.CommandRunner
import nl.vdzon.softwarefactory.runtime.logging.DockerLogFollower
import nl.vdzon.softwarefactory.runtime.workspaces.AgentWorkspace
import nl.vdzon.softwarefactory.runtime.workspaces.AgentWorkspaceFactory
import nl.vdzon.softwarefactory.support.SupportApi
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchResult
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.deleteIfExists

data class DockerRuntimeSettings(
    val addHostGateway: Boolean,
    val logCaptureEnabled: Boolean,
    val dockerSocketGroupId: Int = 0,
    // null = geen persistente build-caches mounten (o.a. in unit-tests); default() zet
    // work/build-caches zodat agent-runs Maven/pub-dependencies niet elke run opnieuw downloaden.
    val buildCachesRoot: Path? = null,
) {
    companion object {
        fun default(): DockerRuntimeSettings =
            DockerRuntimeSettings(
                addHostGateway = isLinux(),
                logCaptureEnabled = true,
                dockerSocketGroupId = detectDockerSocketGroupId(),
                buildCachesRoot = AgentWorkspaceFactory.projectRoot().resolve("work").resolve("build-caches"),
            )

        private fun isLinux(): Boolean =
            System.getProperty("os.name", "").contains("linux", ignoreCase = true)

        // De gid waarmee de agent-container bij de gemounte docker.sock mag. Op macOS is de
        // host-stat misleidend (die toont robbertvdzon:staff): de bind-mount pakt de socket
        // van de Docker Desktop-VM, en die is binnen de container root:root 0660 → gid 0.
        // Alleen op Linux zegt de host-stat iets over wat de container echt ziet (daar is
        // het doorgaans de `docker`-groep).
        private fun detectDockerSocketGroupId(): Int {
            if (!isLinux()) {
                return 0
            }
            return runCatching {
                Files.getAttribute(Path.of("/var/run/docker.sock"), "unix:gid") as Int
            }.getOrDefault(0)
        }
    }
}

@Component
class DockerAgentRuntime(
    private val factorySecrets: FactorySecrets,
    private val factoryEnvironmentProvider: ConfigApi,
    private val commandRunner: CommandRunner,
    private val workspaceFactory: AgentWorkspaceFactory,
    private val dockerRuntimeSettings: DockerRuntimeSettings,
    private val dockerLogFollower: DockerLogFollower,
) : AgentRuntime {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
        val workspace = workspaceFactory.create(request, factoryEnvironmentProvider.resolvedValues())
        val containerName = containerName(request)
        val transientCopilotEnvFile = transientCopilotTokenEnvFile(request)
        // Codex krijgt een geïsoleerde codex-home met een KOPIE van de credentials,
        // niet de live ~/.codex. Anders vechten host- en container-processen om de
        // gedeelde SQLite-state/sessie-locks en hangt codex. De home leeft in de
        // workspace, dus mee met de (detached) container — niet in finally opruimen.
        val codexHome = codexHomeForRun(request, workspace)
        try {
            val command = dockerRunCommand(request, workspace, containerName, transientCopilotEnvFile, codexHome)
            // Redact + truncatie: het commando en de container-output kunnen secrets (tokens in env-vars) bevatten.
            val redact = SupportApi.default()
            logger.info("Starting container: {}", redact.redact(command.joinToString(" ")))
            val result = commandRunner.run(command)
            logger.info(
                "Container run result: exitCode={}, stdout={}, stderr={}",
                result.exitCode,
                redact.redact(result.stdout).take(2000),
                redact.redact(result.stderr).take(2000),
            )
            if (result.exitCode != 0) {
                error(
                    "docker run failed: ${redact.redact(result.stderr.ifBlank { result.stdout }).take(500)}",
                )
            }
        } finally {
            transientCopilotEnvFile?.deleteIfExists()
        }
        return AgentDispatchResult(
            containerName = containerName,
            startedAt = OffsetDateTime.now(),
            workspacePath = workspace.path.toAbsolutePath().normalize().toString(),
        )
    }

    override fun captureLogs(containerName: String, agentRunId: Long) {
        if (dockerRuntimeSettings.logCaptureEnabled) {
            dockerLogFollower.follow(containerName, agentRunId)
        }
    }

    override fun isContainerRunning(containerName: String): Boolean {
        val result = commandRunner.run(
            listOf("docker", "inspect", "--format", "{{.State.Running}}", containerName),
            timeoutSeconds = 30,
        )
        return result.exitCode == 0 && result.stdout.trim().equals("true", ignoreCase = true)
    }

    override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean =
        dockerPsNames(
            "label=app=factory-agent",
            "label=story-key=$storyKey",
            "label=role=${role.markerKeyPart}",
        ).isNotEmpty()

    override fun isAnyAgentRunningForStory(storyKey: String): Boolean =
        dockerPsNames(
            "label=app=factory-agent",
            "label=story-key=$storyKey",
        ).isNotEmpty()

    override fun runningCount(role: AgentRole?): Int {
        val filters = mutableListOf("label=app=factory-agent")
        if (role != null) {
            filters += "label=role=${role.markerKeyPart}"
        }
        return dockerPsNames(*filters.toTypedArray()).size
    }

    override fun killForStory(storyKey: String): Int {
        val containers = dockerPsNames(
            "label=app=factory-agent",
            "label=story-key=$storyKey",
        )
        containers.forEach { containerName ->
            val result = commandRunner.run(listOf("docker", "kill", containerName), timeoutSeconds = 30)
            if (result.exitCode != 0) {
                error(
                    "docker kill failed for $containerName: ${SupportApi.default().redact(result.stderr.ifBlank { result.stdout }).take(500)}",
                )
            }
        }
        return containers.size
    }

    fun dockerRunCommand(
        request: AgentDispatchRequest,
        workspace: AgentWorkspace,
        containerName: String,
        transientCopilotEnvFile: Path? = null,
        codexHome: Path? = null,
    ): List<String> {
        val command = mutableListOf(
            "docker",
            "run",
            "-d",
            "--rm",
            "--name",
            containerName,
        )
        if (dockerRuntimeSettings.addHostGateway) {
            command += listOf("--add-host", "host.docker.internal:host-gateway")
        }
        request.labels.forEach { (key, value) ->
            command += listOf("--label", "$key=$value")
        }
        command += listOf("-v", "${workspace.path.toAbsolutePath()}:/work")
        command += listOf("--env-file", workspace.envFile.toAbsolutePath().toString())
        transientCopilotEnvFile?.let {
            command += listOf("--env-file", it.toAbsolutePath().toString())
        }
        command += listOf("-e", "SF_TICKET_KEY=${request.storyKey}")
        command += listOf("-e", "SF_AGENT_TYPE=${request.role.markerKeyPart}")
        command += listOf("-e", "SF_REPO_URL=${request.targetRepo}")
        command += listOf("-e", "SF_REPO_ROOT=/work/repo")
        command += listOf("-e", "SF_CONTAINER_NAME=$containerName")
        command += listOf("-e", "SF_AGENT_RESULT_FILE=/work/agent-result.json")
        command += listOf("-e", "SF_AGENT_TIPS_FILE=/work/agent-tips.md")
        command += optionalRequestEnv(request)
        command += aiCredentialMounts(request, codexHome)
        command += roleScopedOptions(request.role)
        command += buildCacheMounts(request)
        command += AGENT_IMAGE
        return command
    }

    /**
     * Persistente per-repo build-caches (Maven `.m2`, Dart/Flutter pub-cache): zonder deze mounts
     * downloadt elke agent-run alle dependencies opnieuw, want de cache leeft anders in de
     * wegwerp-container. Gedeeld per repo over stories heen; gelijktijdige agents op dezelfde repo
     * delen de cache — het normale CI-runner-model (zelden een conflict, en een corrupte cache is
     * gewoon weggooibaar: map verwijderen en de volgende run vult 'm opnieuw).
     */
    private fun buildCacheMounts(request: AgentDispatchRequest): List<String> {
        val root = dockerRuntimeSettings.buildCachesRoot
        if (root == null || request.role !in DOCKER_SOCKET_ROLES) {
            return emptyList()
        }
        val slug = request.targetRepo.lowercase()
            .removeSuffix(".git")
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .takeLast(80)
        val repoCaches = root.resolve(slug)
        return listOf("m2" to "/home/runner/.m2", "pub-cache" to "/home/runner/.pub-cache")
            .flatMap { (dir, target) ->
                val host = repoCaches.resolve(dir)
                runCatching { Files.createDirectories(host) }
                listOf("-v", "${host.toAbsolutePath()}:$target")
            }
    }

    private fun optionalRequestEnv(request: AgentDispatchRequest): List<String> = buildList {
        fun env(name: String, value: Any?) {
            value?.let { addAll(listOf("-e", "$name=$it")) }
        }
        env("SF_AI_SUPPLIER", request.aiSupplier)
        env("SF_AI_LEVEL", request.aiLevel)
        env("SF_AI_MODEL", request.aiModel)
        env("SF_AI_EFFORT", request.aiEffort)
        env("SF_AGENT_MODE", request.agentMode)
        env("SF_BRANCH_NAME", request.branchName)
        env("SF_BASE_BRANCH", request.baseBranch)
        env("SF_BRANCH_PREFIX", request.branchPrefix)
        env("SF_PR_NUMBER", request.prNumber)
        env("SF_PREVIEW_URL", request.previewUrl)
        env("SF_PREVIEW_NAMESPACE", request.previewNamespace)
        env("SF_PREVIEW_DB_URL", request.previewDbUrl)
        env("SF_DEVELOPER_LOOPBACK_REASON", request.developerLoopbackReason)
    }

    private fun aiCredentialMounts(request: AgentDispatchRequest, codexHome: Path?): List<String> {
        val supplier = request.aiSupplier?.trim()?.lowercase().orEmpty()
        return when {
            // Codex gebruikt de ChatGPT-abonnement-login. We mounten een geïsoleerde per-run
            // codex-home (kopie van auth.json/config.toml), niet de live ~/.codex — zie codexHomeForRun().
            supplier == "openai" || supplier == "codex" ->
                codexHome?.let { listOf("-v", "${it.toAbsolutePath()}:/home/runner/.codex") }.orEmpty()
            // Copilot authenticeert altijd via een token (COPILOT_GITHUB_TOKEN),
            // niet via een gemounte credentials-dir. Dus hier geen mount.
            supplier == "copilot" || supplier == "github" -> emptyList()
            factorySecrets.aiOauthToken.isNullOrBlank() ->
                factorySecrets.aiCredentialsDir?.takeIf { it.isNotBlank() }
                    ?.let { listOf("-v", "${localPath(it)}:/home/runner/.claude") }.orEmpty()
            else -> emptyList()
        }
    }

    private fun roleScopedOptions(role: AgentRole): List<String> = buildList {
        if (role in EXTENDED_SECRET_ROLES) {
            factorySecrets.kubeconfig?.takeIf { it.isNotBlank() }?.let {
                addAll(listOf("-v", "${localPath(it)}:/home/runner/.kube/config:ro"))
            }
        }
        if (role in DOCKER_SOCKET_ROLES) {
            // Testcontainers-builds (bv. newsfeedbackend's e2e-tests met een echte Postgres)
            // hebben een Docker-daemon nodig. Docker-outside-of-Docker: containers die de
            // agent start worden siblings op de host-daemon. De agent blijft non-root
            // (`runner`); socket-toegang komt van lidmaatschap van de socket-groep
            // (--group-add), dus zonder chmod op de host-socket. Socket-toegang is feitelijk
            // root op de daemon, vandaar alleen voor de rollen die echt bouwen/testen.
            addAll(listOf("-v", "$DOCKER_SOCKET:$DOCKER_SOCKET"))
            addAll(listOf("--group-add", dockerRuntimeSettings.dockerSocketGroupId.toString()))
            // Binnen een container kiest Testcontainers zelf de bridge-gateway (172.17.0.1)
            // als adres voor gepubliceerde sibling-poorten. Op Docker Desktop is dat
            // onbetrouwbaar (Ryuk's poort bleek daar onbereikbaar terwijl host.docker.internal
            // wél werkte), dus dwingen we het adres af. Op Linux bestaat host.docker.internal
            // ook, via de --add-host host-gateway hierboven (addHostGateway).
            addAll(listOf("-e", "TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal"))
        }
    }

    /**
     * Maakt een geïsoleerde codex-home in de workspace met een KOPIE van de
     * credentials (auth.json + config.toml) uit SF_CODEX_CREDENTIALS_DIR. Zo
     * gebruikt Codex het abonnement maar deelt hij geen state/locks met de live
     * ~/.codex van de host (wat tot vastlopen leidt). Leeft mee met de workspace.
     */
    private fun codexHomeForRun(request: AgentDispatchRequest, workspace: AgentWorkspace): Path? {
        val supplier = request.aiSupplier?.trim()?.lowercase().orEmpty()
        if (supplier != "openai" && supplier != "codex") {
            return null
        }
        val sourceDir = factorySecrets.codexCredentialsDir?.takeIf { it.isNotBlank() } ?: return null
        val source = Path.of(localPath(sourceDir))
        if (!Files.isDirectory(source)) {
            return null
        }
        val home = workspace.path.resolve(".codex-home")
        Files.createDirectories(home)
        listOf("auth.json", "config.toml").forEach { name ->
            val src = source.resolve(name)
            if (Files.isRegularFile(src)) {
                Files.copy(src, home.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return home
    }

    private fun transientCopilotTokenEnvFile(request: AgentDispatchRequest): Path? {
        val supplier = request.aiSupplier?.trim()?.lowercase().orEmpty()
        if (supplier != "copilot" && supplier != "github") {
            return null
        }
        val resolvedEnvironment = factoryEnvironmentProvider.resolvedValues()
        resolvedEnvironment.explicitCopilotToken()?.let { return copilotTokenEnvFile(it) }

        val result = commandRunner.run(listOf("gh", "auth", "token"), timeoutSeconds = 10)
        if (result.exitCode != 0) {
            return null
        }
        val token = result.stdout.trim().takeIf { it.isNotBlank() } ?: return null
        return copilotTokenEnvFile(token)
    }

    private fun copilotTokenEnvFile(token: String): Path =
        Files.createTempFile("software-factory-copilot-", ".env").also { path ->
            Files.writeString(path, "COPILOT_GITHUB_TOKEN=$token\n")
            runCatching {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            }
        }

    private fun Map<String, String>.explicitCopilotToken(): String? =
        listOf("SF_COPILOT_TOKEN", "COPILOT_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN")
            .firstNotNullOfOrNull { key -> this[key]?.takeIf { it.isNotBlank() } }

    private fun dockerPsNames(vararg filters: String): List<String> {
        val command = mutableListOf("docker", "ps", "--format", "{{.Names}}")
        filters.forEach { command += listOf("--filter", it) }
        val result = commandRunner.run(command)
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
    }


    private fun containerName(request: AgentDispatchRequest): String =
        "factory-${request.storyKey.lowercase()}-${request.role.markerKeyPart}-${DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(OffsetDateTime.now())}"
            .replace(Regex("[^a-z0-9_.-]"), "-")

    private fun localPath(value: String): String {
        val trimmed = value.trim()
        val expanded = when {
            trimmed == "~" -> System.getProperty("user.home")
            trimmed.startsWith("~/") -> System.getProperty("user.home") + trimmed.removePrefix("~")
            else -> trimmed
        }
        return Path.of(expanded).toAbsolutePath().normalize().toString()
    }

    companion object {
        // Eén gedeelde agent-image voor alle rollen (zie Dockerfile.agent).
        private const val AGENT_IMAGE = "agent:local"

        // Rollen die extra secrets/mounts krijgen (kubeconfig → cluster-toegang).
        // De refiner mag onderzoek doen, de tester moet deployen/testen.
        private val EXTENDED_SECRET_ROLES = setOf(AgentRole.TESTER, AgentRole.REFINER)

        // Rollen die de target-repo bouwen/testen en daarvoor een Docker-daemon nodig
        // hebben (Testcontainers). Bewust niet alle rollen: de socket geeft feitelijk
        // root-toegang op de daemon.
        private val DOCKER_SOCKET_ROLES = setOf(AgentRole.DEVELOPER, AgentRole.REVIEWER, AgentRole.TESTER)

        private const val DOCKER_SOCKET = "/var/run/docker.sock"
    }
}

@Configuration
class DockerRuntimeConfiguration {
    @Bean
    fun dockerRuntimeSettings(): DockerRuntimeSettings =
        DockerRuntimeSettings.default()
}
