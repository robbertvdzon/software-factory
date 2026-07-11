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

    /**
     * Doet wat GitHub bij een squash-merge doet, maar dan lokaal en echt: cloont de bare remote,
     * squash-merget `origin/[branchName]` in `main` en pusht het resultaat terug. Zo kan de
     * e2e-harness verifiëren dat de merge-subtaak de story-branch daadwerkelijk op main brengt.
     * Gooit bij een merge-conflict of ontbrekende branch (git exit != 0) — de aanroeper (de fake
     * GitHub-API) vertaalt dat naar een [nl.vdzon.softwarefactory.github.GitHubClientException].
     */
    fun squashMergeIntoMain(branchName: String, message: String) {
        val work = Files.createTempDirectory("e2e-merge-")
        try {
            git(work.parent, "git", "clone", path.toString(), work.toString())
            git(work, "git", "merge", "--squash", "origin/$branchName")
            // --allow-empty: een branch zonder inhoudelijke diff mag de merge niet laten klappen.
            git(
                work,
                "git", "-c", "user.email=e2e@example.invalid", "-c", "user.name=E2E",
                "commit", "--allow-empty", "-m", message,
            )
            git(work, "git", "push", "origin", "main")
        } finally {
            // Best-effort opruimen; een achtergebleven temp-clone mag de test niet laten falen.
            runCatching {
                Files.walk(work).use { walk ->
                    walk.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    /** Commit-onderwerpen van `main` (nieuwste eerst), rechtstreeks uit de bare repo. */
    fun mainCommitSubjects(): List<String> =
        git(path, "git", "log", "--format=%s", "main").lines().filter { it.isNotBlank() }

    /** Alle bestandspaden in de `main`-tree van de bare repo. */
    fun mainFiles(): List<String> =
        git(path, "git", "ls-tree", "-r", "--name-only", "main").lines().filter { it.isNotBlank() }

    private fun createSeededBareRepo(): Path {
        val bare = Files.createTempDirectory("e2e-remote-").resolve("repo.git")
        git(bare.parent, "git", "-c", "init.defaultBranch=main", "init", "--bare", bare.toString())

        // Seed: clone leeg, één commit op main, terugduwen — zo bestaat origin/main voor de factory.
        val seed = Files.createTempDirectory("e2e-seed-")
        git(seed.parent, "git", "clone", bare.toString(), seed.toString())
        git(seed, "git", "checkout", "-b", "main")
        Files.writeString(seed.resolve("README.md"), "# e2e seed-repo\n")
        Files.createDirectories(seed.resolve(".factory"))
        Files.writeString(
            seed.resolve(".factory/verification.yaml"),
            """
            version: 1
            commands:
              - id: e2e-verification
                argv: [git, diff, --check, HEAD]
                workingDirectory: .
                timeoutSeconds: 60
            """.trimIndent() + "\n",
        )
        git(seed, "git", "add", "-A")
        git(seed, "git", "-c", "user.email=e2e@example.invalid", "-c", "user.name=E2E", "commit", "-m", "seed")
        git(seed, "git", "push", "-u", "origin", "main")
        return bare
    }

    private fun git(cwd: Path, vararg command: String): String {
        val process = ProcessBuilder(*command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "Git-commando faalde (exit $exit): ${command.joinToString(" ")}\n$output" }
        return output
    }
}
