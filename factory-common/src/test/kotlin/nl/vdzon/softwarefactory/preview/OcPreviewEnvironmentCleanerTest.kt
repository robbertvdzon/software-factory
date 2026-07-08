package nl.vdzon.softwarefactory.preview

import nl.vdzon.softwarefactory.preview.services.*

import nl.vdzon.softwarefactory.preview.services.OcPreviewEnvironmentCleaner
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class OcPreviewEnvironmentCleanerTest {
    @Test
    fun `deletes preview project with kubeconfig env and ignore not found`() {
        val runner = FakeProcessRunner()
        val cleaner = OcPreviewEnvironmentCleaner(
            git = runner,
            factorySecrets = FactorySecrets(
                trackerProjects = listOf("KAN"),
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

    private class FakeProcessRunner : GitApi {
        val commands = mutableListOf<List<String>>()
        val envs = mutableListOf<Map<String, String>>()

        override fun runCommand(command: List<String>, cwd: Path?, env: Map<String, String>, timeoutSeconds: Long): GitProcessResult {
            commands += command
            envs += env
            return GitProcessResult(0, "", "")
        }

        override fun repositorySlug(repoUrl: String): String? = null
        override fun clone(repoUrl: String, targetDir: Path, githubToken: String?) = Unit
        override fun checkoutBase(repoRoot: Path, baseBranch: String, githubToken: String?) = Unit
        override fun checkoutStoryBranch(repoRoot: Path, branchName: String, baseBranch: String, createIfMissing: Boolean, githubToken: String?) = Unit
        override fun commitAll(repoRoot: Path, message: String, githubToken: String?): Boolean = true
        override fun push(repoRoot: Path, branchName: String, githubToken: String?) = Unit
        override fun remoteBranchExists(repoRoot: Path, branchName: String, githubToken: String?): Boolean = false
    }
}
