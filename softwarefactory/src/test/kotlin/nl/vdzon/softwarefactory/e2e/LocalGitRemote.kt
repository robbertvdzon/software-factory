package nl.vdzon.softwarefactory.e2e

import java.nio.file.Files
import java.nio.file.Path

/**
 * Een lokale, file-based git-remote voor de end-to-end-test (e2e-plan §8): `git init --bare`
 * met één seed-commit op `main`, i.p.v. een echte GitHub-repo.
 *
 * De factory kloont/brancht/commit/pusht hier **echt** tegenaan — dus de git-laag draait mee
 * (hoge fideliteit, geen `FakeGitApi`). De GitHub-PR-stap valt vanzelf weg: een lokaal pad heeft
 * geen github-slug (`GitRepositoryUrl.parse` → `slug == null`), dus `ensurePullRequest` (de
 * `gh`-CLI) wordt niet aangeroepen.
 */
class LocalGitRemote {
    /** Het pad dat als `factory.repo` in de project-beschrijving komt te staan. */
    val path: Path = createSeededBareRepo()

    private fun createSeededBareRepo(): Path {
        val bare = Files.createTempDirectory("e2e-remote-").resolve("repo.git")
        git(bare.parent, "git", "-c", "init.defaultBranch=main", "init", "--bare", bare.toString())

        // Seed: clone leeg, één commit op main, terugduwen — zo bestaat origin/main voor de factory.
        val seed = Files.createTempDirectory("e2e-seed-")
        git(seed.parent, "git", "clone", bare.toString(), seed.toString())
        git(seed, "git", "checkout", "-b", "main")
        Files.writeString(seed.resolve("README.md"), "# e2e seed-repo\n")
        git(seed, "git", "add", "-A")
        git(seed, "git", "-c", "user.email=e2e@example.invalid", "-c", "user.name=E2E", "commit", "-m", "seed")
        git(seed, "git", "push", "-u", "origin", "main")
        return bare
    }

    private fun git(cwd: Path, vararg command: String) {
        val process = ProcessBuilder(*command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "Git-commando faalde (exit $exit): ${command.joinToString(" ")}\n$output" }
    }
}
