package nl.vdzon.softwarefactory.preview

import nl.vdzon.softwarefactory.preview.services.*

import nl.vdzon.softwarefactory.preview.services.OcPreviewEnvironmentCleaner
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.git.services.ProcessResult
import nl.vdzon.softwarefactory.git.services.ProcessRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class OcPreviewEnvironmentCleanerTest {
    @Test
    fun `deletes preview project with kubeconfig env and ignore not found`() {
        val runner = FakeProcessRunner()
        val cleaner = OcPreviewEnvironmentCleaner(
            processRunner = runner,
            factorySecrets = FactorySecrets(
                youTrackBaseUrl = "https://youtrack.example",
                youTrackToken = "youtrack-token",
                youTrackProjects = listOf("KAN"),
                githubToken = "github-token",
                factoryDatabaseUrl = "postgresql://example/db",
                factoryDatabaseSchema = "software_factory",
                kubeconfig = "~/.kube/config",
                aiCredentialsDir = null,
                aiOauthToken = null,
                loadedFrom = "test",
            ),
        )

        assertTrue(cleaner.cleanup(" app-pr-12 "))

        assertEquals(listOf("oc", "delete", "project", "app-pr-12", "--ignore-not-found=true"), runner.commands.single())
        assertEquals(
            Path.of(System.getProperty("user.home"), ".kube", "config").toString(),
            runner.envs.single().getValue("KUBECONFIG"),
        )
    }

    private class FakeProcessRunner : ProcessRunner {
        val commands = mutableListOf<List<String>>()
        val envs = mutableListOf<Map<String, String>>()

        override fun run(command: List<String>, cwd: Path?, env: Map<String, String>, timeoutSeconds: Long): ProcessResult {
            commands += command
            envs += env
            return ProcessResult(0, "", "")
        }
    }
}
