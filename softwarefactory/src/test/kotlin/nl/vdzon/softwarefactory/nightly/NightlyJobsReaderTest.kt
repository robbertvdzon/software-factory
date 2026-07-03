package nl.vdzon.softwarefactory.nightly

import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Base64

class NightlyJobsReaderTest {

    private val repoUrl = "git@github.com:robbertvdzon/demo.git"
    private val slug = "robbertvdzon/demo"

    @Test
    fun `geldige job wordt volledig geparst`() {
        val git = FakeGitApi(
            responsesByPath = mapOf(
                ".factory/nightly" to listing("refactor"),
                ".factory/nightly/refactor/job.yaml" to contents(
                    """
                    title: Nachtelijke refactor
                    enabled: true
                    silent: false
                    aiSupplier: claude
                    aiModel: claude-opus-4-8
                    priority: 3
                    """.trimIndent(),
                ),
            ),
        )

        val result = NightlyJobsReader(git).readAll(listOf("demo" to repoUrl))

        assertTrue(result.errors.isEmpty(), "geen errors verwacht: ${result.errors}")
        val job = result.jobs.single()
        assertEquals("demo", job.project)
        assertEquals("refactor", job.name)
        assertEquals("Nachtelijke refactor", job.title)
        assertTrue(job.enabled)
        assertFalse(job.silent)
        assertEquals("claude", job.aiSupplier)
        assertEquals("claude-opus-4-8", job.aiModel)
        assertEquals("3", job.priority)
    }

    @Test
    fun `ontbrekende velden vallen terug op defaults`() {
        // Alleen een leeg document: title valt terug op de directorynaam, enabled/silent op hun default.
        val git = FakeGitApi(
            responsesByPath = mapOf(
                ".factory/nightly" to listing("cleanup"),
                ".factory/nightly/cleanup/job.yaml" to contents("{}"),
            ),
        )

        val job = NightlyJobsReader(git).readAll(listOf("demo" to repoUrl)).jobs.single()

        assertEquals("cleanup", job.title)
        assertTrue(job.enabled, "enabled hoort default true te zijn")
        assertTrue(job.silent, "silent hoort default true te zijn")
        assertNull(job.aiSupplier)
        assertNull(job.aiModel)
        assertNull(job.priority)
    }

    @Test
    fun `enabled false wordt geparst`() {
        val git = FakeGitApi(
            responsesByPath = mapOf(
                ".factory/nightly" to listing("disabled-job"),
                ".factory/nightly/disabled-job/job.yaml" to contents("enabled: false"),
            ),
        )

        val job = NightlyJobsReader(git).readAll(listOf("demo" to repoUrl)).jobs.single()

        assertFalse(job.enabled)
    }

    @Test
    fun `malformed yaml levert een error op en geen job`() {
        val git = FakeGitApi(
            responsesByPath = mapOf(
                ".factory/nightly" to listing("broken"),
                ".factory/nightly/broken/job.yaml" to contents("title: [ongesloten"),
            ),
        )

        val result = NightlyJobsReader(git).readAll(listOf("demo" to repoUrl))

        assertTrue(result.jobs.isEmpty(), "geen jobs verwacht: ${result.jobs}")
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("broken"), result.errors.single())
    }

    @Test
    fun `ontbrekende nightly-directory betekent gewoon geen jobs`() {
        // GitHub geeft een 404 op de contents-call; dat is geen fout maar 'dit project heeft geen jobs'.
        val git = FakeGitApi(
            responsesByPath = emptyMap(),
            notFoundPaths = setOf(".factory/nightly"),
        )

        val result = NightlyJobsReader(git).readAll(listOf("demo" to repoUrl))

        assertTrue(result.jobs.isEmpty())
        assertTrue(result.errors.isEmpty(), "404 mag geen error opleveren: ${result.errors}")
    }

    @Test
    fun `niet-herkende repo-url levert een error op`() {
        val git = FakeGitApi(responsesByPath = emptyMap())

        val result = NightlyJobsReader(git).readAll(listOf("demo" to "file:///ergens/lokaal"))

        assertTrue(result.jobs.isEmpty())
        assertTrue(result.errors.single().contains("niet herkend"), result.errors.single())
    }

    @Test
    fun `readJob levert job plus story-tekst`() {
        val git = FakeGitApi(
            responsesByPath = mapOf(
                ".factory/nightly/refactor/job.yaml" to contents("title: Refactor"),
                ".factory/nightly/refactor/story.md" to contents("# Story\nDoe de refactor."),
            ),
        )

        val detail = NightlyJobsReader(git).readJob(repoUrl, "demo", "refactor")

        assertEquals("Refactor", detail?.job?.title)
        assertEquals("# Story\nDoe de refactor.", detail?.story)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /** GitHub contents-listing van de nightly-map: één dir-entry per jobnaam. */
    private fun listing(vararg names: String): String =
        names.joinToString(prefix = "[", postfix = "]") { """{"type":"dir","name":"$it"}""" }

    /** GitHub contents-respons van één bestand (base64-`content`, zoals het echte endpoint). */
    private fun contents(text: String): String =
        """{"content":"${Base64.getEncoder().encodeToString(text.toByteArray())}"}"""

    /**
     * [GitApi]-fake voor de `gh api repos/<slug>/contents/<pad>`-aanroepen van de reader:
     * geeft per pad een canned JSON-respons of een 404 terug; al het andere is niet nodig.
     */
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
