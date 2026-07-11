package nl.vdzon.softwarefactory.verification

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckoutIdentityResolverTest {
    @TempDir
    lateinit var repo: Path

    @Test
    fun `worktree tree includes dirty content without mutating real index`() {
        git("init")
        git("config", "user.email", "test@example.invalid")
        git("config", "user.name", "Test")
        repo.resolve("tracked.txt").writeText("before\n")
        git("add", "tracked.txt")
        git("commit", "-m", "fixture")
        val clean = assertNotNull(CheckoutIdentityResolver().resolve(repo))

        repo.resolve("tracked.txt").writeText("after\n")
        repo.resolve("untracked.txt").writeText("included\n")
        val dirty = assertNotNull(CheckoutIdentityResolver().resolve(repo))

        assertEquals(clean.headSha, dirty.headSha)
        assertNotEquals(clean.treeSha, dirty.treeSha)
        assertTrue(git("status", "--porcelain").contains("tracked.txt"))
        assertTrue(git("status", "--porcelain").contains("untracked.txt"))
        assertEquals("", git("diff", "--cached", "--name-only"))
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args)).directory(repo.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { output }
        return output
    }
}
