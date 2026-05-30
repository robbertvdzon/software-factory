package nl.vdzon.softwarefactory.agentworker.flows

import nl.vdzon.softwarefactory.docs.DeploymentConfig
import nl.vdzon.softwarefactory.docs.DocsApi
import nl.vdzon.softwarefactory.git.services.LocalProcessRunner
import nl.vdzon.softwarefactory.git.services.ProcessRunner
import nl.vdzon.softwarefactory.youtrack.AgentRole
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

data class TargetRepositorySession(
    val repoRoot: Path,
    val repoUrl: String,
    val baseBranch: String,
    val branchPrefix: String,
    val branchName: String,
    val deploymentConfig: DeploymentConfig,
)

data class DeveloperRepositoryResult(
    val branchName: String,
)

class TargetRepositoryPreparer(
    private val docs: DocsApi = DocsApi.default(),
) {
    fun prepare(env: Map<String, String>, ticketKey: String, role: AgentRole): TargetRepositorySession? {
        val repoUrl = env["SF_REPO_URL"]?.takeIf { it.isNotBlank() } ?: return null
        val repoRoot = Path.of(env["SF_REPO_ROOT"] ?: "/work/repo")
        require(repoRoot.exists() && repoRoot.isDirectory()) {
            "Target repository is not mounted: $repoRoot"
        }
        val config = docs.loadFactoryDocs(role, repoRoot).deploymentConfig ?: DeploymentConfig()
        val branchName = env["SF_BRANCH_NAME"]?.takeIf { it.isNotBlank() } ?: config.branchPrefix + ticketKey

        return TargetRepositorySession(
            repoRoot = repoRoot,
            repoUrl = repoUrl,
            baseBranch = config.defaultBaseBranch,
            branchPrefix = config.branchPrefix,
            branchName = branchName,
            deploymentConfig = config,
        )
    }
}

class DeveloperRepositoryFlow(
    private val docs: DocsApi = DocsApi.default(),
    private val skeletonRoot: Path = Path.of("/usr/local/share/factory/docs-skeleton"),
) {
    fun completeDummyDeveloperRun(
        session: TargetRepositorySession,
        ticketKey: String,
        storyText: String,
    ): DeveloperRepositoryResult {
        bootstrapFactoryDocsIfMissing(session.repoRoot)

        val storyLog = docs.recordDeveloperRunStart(session.repoRoot, ticketKey, storyText)
        val dummyLog = session.repoRoot.resolve("docs").resolve("factory").resolve(".dummy-log")
        dummyLog.parent.createDirectories()
        val dummyLogEntry = "- ${OffsetDateTime.now()}: $ticketKey dummy developer update on ${session.branchName}\n"
        if (dummyLog.exists()) {
            dummyLog.appendText(dummyLogEntry)
        } else {
            dummyLog.writeText(dummyLogEntry)
        }
        docs.markStepDone(storyLog, "implement requested changes")
        docs.appendDone(
            storyLog,
            "Dummy developer-flow heeft een placeholder-wijziging gemaakt. De orchestrator of handmatige sync commit en pusht deze wijziging.",
        )

        return DeveloperRepositoryResult(session.branchName)
    }

    private fun bootstrapFactoryDocsIfMissing(repoRoot: Path) {
        if (repoRoot.resolve("docs").resolve("factory").exists()) {
            return
        }
        docs.installSkeleton(repoRoot, skeletonRoot = skeletonRoot.takeIf { it.exists() })
    }

}

class RepositoryCommitGuard(
    private val processRunner: ProcessRunner = LocalProcessRunner(),
) {
    fun captureHead(repoRoot: Path): String? {
        val result = processRunner.run(
            command = listOf("git", "rev-parse", "HEAD"),
            cwd = repoRoot,
            timeoutSeconds = 10,
        )
        if (result.exitCode != 0) {
            return null
        }
        return result.stdout.trim().takeIf { it.isNotBlank() }
    }

    fun detectCommit(repoRoot: Path, beforeHead: String?): String? {
        if (beforeHead.isNullOrBlank()) {
            return null
        }
        val afterHead = captureHead(repoRoot) ?: return null
        if (afterHead == beforeHead) {
            return null
        }
        return "Agent heeft zelf een lokale git commit gemaakt ($beforeHead -> $afterHead). " +
            "Dat is niet toegestaan: laat wijzigingen uncommitted staan zodat de orchestrator of handmatige sync commit en pusht."
    }
}
