package nl.vdzon.softwarefactory.runtime.commands

import nl.vdzon.softwarefactory.config.FactorySecrets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 2026-07: alle robberts-assistent-deploys faalden op `kubectl ... exitCode=1` ("Unauthorized"),
 * terwijl ArgoCD zelf allang Synced+Healthy stond op de juiste revisie. Oorzaak: deze probe gebruikte
 * bare `kubectl` (ambient `~/.kube/config`, een persoonlijke `oc login`-sessie die kan verlopen)
 * i.p.v. het stabiele, voor dit doel geconfigureerde `SF_KUBECONFIG`. Deze tests pinnen vast dat élke
 * kubectl-aanroep `--kubeconfig <SF_KUBECONFIG>` meekrijgt wanneer dat geconfigureerd is, en dat het
 * ontbreken ervan geen regressie geeft (bare kubectl, zoals voorheen).
 */
class KubectlDeploymentStatusProbeTest {

    @Test
    fun `argoApplicationStatus geeft --kubeconfig mee wanneer SF_KUBECONFIG is geconfigureerd`() {
        val runner = FakeCommandRunner(CommandResult(0, "Synced|Healthy|Succeeded|abc123", ""))
        val probe = KubectlDeploymentStatusProbe(runner, secrets(kubeconfig = "/path/to/kubeconfig"))

        val status = probe.argoApplicationStatus("argocd", "robberts-assistent")

        assertEquals(listOf("kubectl", "--kubeconfig", "/path/to/kubeconfig"), runner.lastCommand?.take(3))
        assertEquals("Synced", status?.syncStatus)
        assertEquals("abc123", status?.revision)
    }

    @Test
    fun `argoApplicationStatus valt terug op bare kubectl zonder SF_KUBECONFIG`() {
        val runner = FakeCommandRunner(CommandResult(0, "Synced|Healthy|Succeeded|abc123", ""))
        val probe = KubectlDeploymentStatusProbe(runner, secrets(kubeconfig = null))

        probe.argoApplicationStatus("argocd", "robberts-assistent")

        assertEquals(listOf("kubectl", "get"), runner.lastCommand?.take(2))
        assertTrue(runner.lastCommand?.none { it == "--kubeconfig" } == true)
    }

    @Test
    fun `argoApplicationStatus geeft null en logt stderr bij een mislukte kubectl-aanroep`() {
        val runner = FakeCommandRunner(CommandResult(1, "", "error: You must be logged in to the server (Unauthorized)"))
        val probe = KubectlDeploymentStatusProbe(runner, secrets(kubeconfig = "/path/to/kubeconfig"))

        val status = probe.argoApplicationStatus("argocd", "robberts-assistent")

        assertNull(status)
    }

    @Test
    fun `currentImage geeft --kubeconfig mee wanneer geconfigureerd`() {
        val runner = FakeCommandRunner(CommandResult(0, "ghcr.io/example/app:sha-123", ""))
        val probe = KubectlDeploymentStatusProbe(runner, secrets(kubeconfig = "/path/to/kubeconfig"))

        val image = probe.currentImage("robberts-assistent", "robberts-assistent-frontend")

        assertEquals("ghcr.io/example/app:sha-123", image)
        assertEquals(listOf("kubectl", "--kubeconfig", "/path/to/kubeconfig"), runner.lastCommand?.take(3))
    }

    @Test
    fun `runningPod geeft --kubeconfig mee op beide onderliggende kubectl-aanroepen`() {
        val runner = FakeCommandRunner(
            listOf(
                CommandResult(0, """{"app":"demo"}""", ""),
                CommandResult(0, "2026-07-21T10:00:00Z|ghcr.io/example/app:sha-123", ""),
            ),
        )
        val probe = KubectlDeploymentStatusProbe(runner, secrets(kubeconfig = "/path/to/kubeconfig"))

        val pod = probe.runningPod("robberts-assistent", "robberts-assistent-frontend")

        assertEquals("ghcr.io/example/app:sha-123", pod?.image)
        assertTrue(runner.commands.all { it.take(3) == listOf("kubectl", "--kubeconfig", "/path/to/kubeconfig") })
    }

    private fun secrets(kubeconfig: String?): FactorySecrets = FactorySecrets(
        trackerProjects = listOf("SF"),
        githubToken = "github-token",
        factoryDatabaseUrl = "postgresql://example/db",
        factoryDatabaseSchema = "software_factory",
        kubeconfig = kubeconfig,
        aiCredentialsDir = "~/.claude",
        aiOauthToken = null,
        loadedFrom = "test",
    )

    private class FakeCommandRunner(private val results: List<CommandResult>) : CommandRunner {
        constructor(result: CommandResult) : this(listOf(result))

        val commands = mutableListOf<List<String>>()
        val lastCommand: List<String>? get() = commands.lastOrNull()

        override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
            commands += command
            return results.getOrElse(commands.size - 1) { results.last() }
        }
    }
}
