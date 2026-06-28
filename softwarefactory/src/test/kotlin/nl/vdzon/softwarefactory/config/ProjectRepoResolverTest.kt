package nl.vdzon.softwarefactory.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class ProjectRepoResolverTest {

    @Test
    fun `matches case-insensitively and trims whitespace`() {
        val resolver = ProjectRepoResolver(mapOf("Personal-Feed" to "git@example/pf.git"))

        assertEquals("git@example/pf.git", resolver.repoFor("personal-feed"))
        assertEquals("git@example/pf.git", resolver.repoFor("  PERSONAL-FEED  "))
        assertEquals("git@example/pf.git", resolver.repoFor("Personal-Feed"))
    }

    @Test
    fun `resolve uses the config repo for a known name`() {
        val resolver = ProjectRepoResolver(mapOf("personal-feed" to "git@example/pf.git"))

        assertEquals("git@example/pf.git", resolver.resolve("personal-feed"))
        assertEquals("git@example/pf.git", resolver.resolve("  PERSONAL-FEED "))
    }

    @Test
    fun `resolve treats an unknown value as a literal repo url`() {
        val resolver = ProjectRepoResolver(mapOf("pf" to "git@example/pf.git"))

        assertEquals("git@github.com:robbert/direct.git", resolver.resolve("git@github.com:robbert/direct.git"))
        assertEquals("https://host/x.git", resolver.resolve("  https://host/x.git  "))
    }

    @Test
    fun `resolve returns null for blank input`() {
        val resolver = ProjectRepoResolver(mapOf("pf" to "git@example/pf.git"))

        assertNull(resolver.resolve(null))
        assertNull(resolver.resolve("   "))
    }

    @Test
    fun `returns null for blank or unknown names`() {
        val resolver = ProjectRepoResolver(mapOf("pf" to "git@example/pf.git"))

        assertNull(resolver.repoFor(null))
        assertNull(resolver.repoFor(""))
        assertNull(resolver.repoFor("   "))
        assertNull(resolver.repoFor("unknown"))
    }

    @Test
    fun `parses a yaml file with multiple projects`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: personal-feed
                repo: git@github.com:robbert/personal-feed.git
              - name: SoftwareFactory
                repo: https://github.com/robbert/softwarefactory.git
            """.trimIndent(),
        )

        val resolver = ProjectRepoResolver.fromYaml(file)

        assertEquals("git@github.com:robbert/personal-feed.git", resolver.repoFor("personal-feed"))
        assertEquals("https://github.com/robbert/softwarefactory.git", resolver.repoFor("softwarefactory"))
        assertEquals(setOf("personal-feed", "softwarefactory"), resolver.configuredNames())
        // projectNames behoudt de originele schrijfwijze (voor de YouTrack enum-keuzes).
        assertEquals(listOf("personal-feed", "SoftwareFactory"), resolver.projectNames())
    }

    @Test
    fun `missing file yields an empty resolver`(@TempDir dir: Path) {
        val resolver = ProjectRepoResolver.fromYaml(dir.resolve("does-not-exist.yaml"))

        assertNull(resolver.repoFor("anything"))
        assertEquals(emptySet<String>(), resolver.configuredNames())
    }

    @Test
    fun `malformed file yields an empty resolver instead of crashing`(@TempDir dir: Path) {
        val file = dir.resolve("bad.yaml")
        Files.writeString(file, "this is: not the expected shape")

        val resolver = ProjectRepoResolver.fromYaml(file)

        assertNull(resolver.repoFor("anything"))
    }

    @Test
    fun `entries missing name or repo are skipped`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - name: ok
                repo: git@example/ok.git
              - name: no-repo
              - repo: git@example/no-name.git
            """.trimIndent(),
        )

        val resolver = ProjectRepoResolver.fromYaml(file)

        assertEquals("git@example/ok.git", resolver.repoFor("ok"))
        assertNull(resolver.repoFor("no-repo"))
        assertEquals(setOf("ok"), resolver.configuredNames())
    }

    @Test
    fun `a non-object project entry yields an empty resolver instead of crashing`(@TempDir dir: Path) {
        val file = dir.resolve("projects.yaml")
        file.writeText(
            """
            projects:
              - just-a-string
            """.trimIndent(),
        )

        val resolver = ProjectRepoResolver.fromYaml(file)

        assertNull(resolver.repoFor("anything"))
        assertEquals(emptySet<String>(), resolver.configuredNames())
    }
}
