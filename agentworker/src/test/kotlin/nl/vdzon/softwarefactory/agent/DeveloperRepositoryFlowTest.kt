package nl.vdzon.softwarefactory.agent

import nl.vdzon.softwarefactory.agentworker.flows.DeveloperRepositoryFlow
import nl.vdzon.softwarefactory.agentworker.flows.RepositoryCommitGuard
import nl.vdzon.softwarefactory.agentworker.flows.TargetRepositoryPreparer
import nl.vdzon.softwarefactory.agentworker.flows.TargetRepositorySession
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DeveloperRepositoryFlowTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `dummy developer flow writes local placeholder changes without git side effects`() {
        val flow = DeveloperRepositoryFlow(
            skeletonRoot = Path.of("/missing-skeleton-so-classpath-is-used"),
        )
        val session = TargetRepositorySession(
            repoRoot = tempDir,
            repoUrl = "git@github.com:robbertvdzon/sample-build-project.git",
            baseBranch = "main",
            branchPrefix = "ai/",
            branchName = "ai/KAN-42",
            deploymentConfig = DeploymentConfig(
                defaultBaseBranch = "main",
                branchPrefix = "ai/",
                previewUrlTemplate = "https://app-pr-{pr_num}.example.com",
                previewNamespaceTemplate = "app-pr-{pr_num}",
            ),
        )

        val result = flow.completeDummyDeveloperRun(session, "KAN-42", "Story body")

        assertEquals("ai/KAN-42", result.branchName)
        assertTrue(tempDir.resolve("docs/factory/README.md").toFile().exists())
        assertTrue(tempDir.resolve("docs/factory/.dummy-log").readText().contains("KAN-42"))
        assertTrue(tempDir.resolve("docs/stories/worklog/KAN-42-worklog.md").readText().contains("[x]: implement requested changes"))
    }

    @Test
    fun `target repository preparer uses the mounted repository and branch env`() {
        tempDir.resolve(".git").createDirectories()
        val session = TargetRepositoryPreparer().prepare(
            mapOf(
                "SF_REPO_URL" to "https://dev.azure.com/ing/product/_git/work-project",
                "SF_REPO_ROOT" to tempDir.toString(),
                "SF_BRANCH_NAME" to "ai/KAN-42",
            ),
            "KAN-42",
            AgentRole.DEVELOPER,
        )

        assertEquals(tempDir, session?.repoRoot)
        assertEquals("ai/KAN-42", session?.branchName)
        assertEquals("https://dev.azure.com/ing/product/_git/work-project", session?.repoUrl)
    }

    @Test
    fun `target repository preparer fails when repository is not mounted`() {
        val missingRepo = tempDir.resolve("missing")

        val exception = assertThrows<IllegalArgumentException> {
            TargetRepositoryPreparer().prepare(
                mapOf(
                    "SF_REPO_URL" to "git@example/repo.git",
                    "SF_REPO_ROOT" to missingRepo.toString(),
                ),
                "KAN-42",
                AgentRole.DEVELOPER,
            )
        }

        assertTrue(exception.message.orEmpty().contains("Target repository is not mounted"))
    }

    @Test
    fun `repository commit guard detects agent-created commits`() {
        runGit("init")
        runGit("config", "user.email", "test@example.invalid")
        runGit("config", "user.name", "Test User")
        tempDir.resolve("README.md").writeText("initial\n")
        runGit("add", ".")
        runGit("commit", "-m", "initial")
        val guard = RepositoryCommitGuard()
        val before = guard.captureHead(tempDir)

        tempDir.resolve("feature.txt").writeText("agent change\n")
        assertNull(guard.detectCommit(tempDir, before))
        runGit("add", ".")
        runGit("commit", "-m", "agent commit")

        val violation = guard.detectCommit(tempDir, before)

        assertNotNull(violation)
        assertTrue(violation.orEmpty().contains("Agent heeft zelf een lokale git commit gemaakt"))
    }

    private fun runGit(vararg args: String) {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(tempDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
}
