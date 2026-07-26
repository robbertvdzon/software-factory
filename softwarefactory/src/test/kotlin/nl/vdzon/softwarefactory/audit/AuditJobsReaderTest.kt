package nl.vdzon.softwarefactory.audit

import nl.vdzon.softwarefactory.audit.services.AuditJobConfigException
import nl.vdzon.softwarefactory.audit.services.AuditJobsReader
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Base64

class AuditJobsReaderTest {

    private val repoUrl = "git@github.com:robbertvdzon/demo.git"

    @Test
    fun `geldige audit wordt volledig geparst`() {
        val git = FakeGitApi(
            responsesByPath = mapOf(
                ".factory/nightly" to listing("quality"),
                ".factory/nightly/quality/job.yaml" to contents(
                    """
                    title: Code-kwaliteit-audit
                    enabled: true
                    aiSupplier: claude
                    aiModel: claude-opus-4-8
                    priority: 3
                    """.trimIndent(),
                ),
            ),
        )

        val result = AuditJobsReader(git).readAll(listOf("demo" to repoUrl))

        assertTrue(result.errors.isEmpty(), "geen errors verwacht: ${result.errors}")
        val job = result.jobs.single()
        assertEquals("demo", job.project)
        assertEquals("quality", job.name)
        assertEquals("Code-kwaliteit-audit", job.title)
        assertTrue(job.enabled)
        assertEquals("claude", job.aiSupplier)
        assertEquals("claude-opus-4-8", job.aiModel)
        assertEquals("3", job.priority)
    }

    @Test
    fun `ontbrekende velden vallen terug op defaults`() {
        val git = FakeGitApi(
            responsesByPath = mapOf(
                ".factory/nightly" to listing("cleanup"),
                ".factory/nightly/cleanup/job.yaml" to contents("{}"),
            ),
        )

        val job = AuditJobsReader(git).readAll(listOf("demo" to repoUrl)).jobs.single()

        assertEquals("cleanup", job.title)
        assertTrue(job.enabled, "enabled hoort default true te zijn")
        assertNull(job.aiSupplier)
        assertNull(job.aiModel)
        assertNull(job.priority)
    }

    @Test
    fun `enabled false wordt geparst`() {
        val git = FakeGitApi(
            responsesByPath = mapOf(
                ".factory/nightly" to listing("disabled-audit"),
                ".factory/nightly/disabled-audit/job.yaml" to contents("enabled: false"),
            ),
        )

        val job = AuditJobsReader(git).readAll(listOf("demo" to repoUrl)).jobs.single()

        assertFalse(job.enabled)
    }

    @Test
    fun `malformed yaml levert een error op en geen audit`() {
        val git = FakeGitApi(
            responsesByPath = mapOf(
                ".factory/nightly" to listing("broken"),
                ".factory/nightly/broken/job.yaml" to contents("title: [ongesloten"),
            ),
        )

        val result = AuditJobsReader(git).readAll(listOf("demo" to repoUrl))

        assertTrue(result.jobs.isEmpty(), "geen jobs verwacht: ${result.jobs}")
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("broken"), result.errors.single())
    }

    @Test
    fun `ontbrekende nightly-directory betekent gewoon geen audits`() {
        val git = FakeGitApi(responsesByPath = emptyMap(), notFoundPaths = setOf(".factory/nightly"))

        val result = AuditJobsReader(git).readAll(listOf("demo" to repoUrl))

        assertTrue(result.jobs.isEmpty())
        assertTrue(result.errors.isEmpty(), "404 mag geen error opleveren: ${result.errors}")
    }

    @Test
    fun `niet-herkende repo-url levert een error op`() {
        val git = FakeGitApi(responsesByPath = emptyMap())

        val result = AuditJobsReader(git).readAll(listOf("demo" to "file:///ergens/lokaal"))

        assertTrue(result.jobs.isEmpty())
        assertTrue(result.errors.single().contains("niet herkend"), result.errors.single())
    }

    @Test
    fun `readJob levert job plus prompt-tekst`() {
        val git = FakeGitApi(
            responsesByPath = mapOf(
                ".factory/nightly/quality/job.yaml" to contents("title: Code-kwaliteit"),
                ".factory/nightly/quality/prompt.md" to contents("# Audit\nBeoordeel de code."),
            ),
        )

        val detail = AuditJobsReader(git).readJob(repoUrl, "demo", "quality")

        assertEquals("Code-kwaliteit", detail?.job?.title)
        assertEquals("# Audit\nBeoordeel de code.", detail?.prompt)
    }

    @Test
    fun `readJob gooit als prompt-md ontbreekt`() {
        val git = FakeGitApi(
            responsesByPath = mapOf(
                ".factory/nightly/quality/job.yaml" to contents("title: Code-kwaliteit"),
            ),
            notFoundPaths = setOf(".factory/nightly/quality/prompt.md"),
        )

        val error = assertThrows(AuditJobConfigException::class.java) {
            AuditJobsReader(git).readJob(repoUrl, "demo", "quality")
        }
        assertTrue(error.message!!.contains("prompt.md ontbreekt"), error.message)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private fun listing(vararg names: String): String =
        names.joinToString(prefix = "[", postfix = "]") { """{"type":"dir","name":"$it"}""" }

    private fun contents(text: String): String =
        """{"content":"${Base64.getEncoder().encodeToString(text.toByteArray())}"}"""

    private class FakeGitApi(
        private val responsesByPath: Map<String, String>,
        private val notFoundPaths: Set<String> = emptySet(),
    ) : GitApi {
        override fun repositorySlug(repoUrl: String): String? =
            Regex("github\\.com[:/](.+?)(\\.git)?$").find(repoUrl)?.groupValues?.get(1)

        override fun runCommand(
            command: List<String>,
            cwd: Path?,
            env: Map<String, String>,
            timeoutSeconds: Long,
        ): GitProcessResult {
            val path = command.last().substringAfter("/contents/")
            if (path in notFoundPaths) {
                return GitProcessResult(exitCode = 1, stdout = "", stderr = "HTTP 404: Not Found")
            }
            val body = responsesByPath[path]
                ?: return GitProcessResult(exitCode = 1, stdout = "", stderr = "HTTP 404: Not Found")
            return GitProcessResult(exitCode = 0, stdout = body, stderr = "")
        }

        override fun clone(repoUrl: String, targetDir: Path, githubToken: String?) = error("niet gebruikt")
        override fun checkoutBase(repoRoot: Path, baseBranch: String, githubToken: String?) = error("niet gebruikt")
        override fun checkoutStoryBranch(
            repoRoot: Path,
            branchName: String,
            baseBranch: String,
            createIfMissing: Boolean,
            githubToken: String?,
        ) = error("niet gebruikt")
        override fun commitAll(repoRoot: Path, message: String, githubToken: String?): Boolean = error("niet gebruikt")
        override fun push(repoRoot: Path, branchName: String, githubToken: String?) = error("niet gebruikt")
        override fun remoteBranchExists(repoRoot: Path, branchName: String, githubToken: String?): Boolean =
            error("niet gebruikt")
    }
}
