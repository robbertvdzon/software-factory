package nl.vdzon.softwarefactory.docs.services

import nl.vdzon.softwarefactory.docs.DocsApi
import nl.vdzon.softwarefactory.docs.FACTORY_DOCS_BOOTSTRAP_NOTICE
import nl.vdzon.softwarefactory.docs.FactoryDocsContext
import nl.vdzon.softwarefactory.youtrack.AgentRole
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

class FactoryDocsLoader : DocsApi {
    private val storyLogWriter = StoryLogWriter()

    override fun loadFactoryDocs(role: AgentRole, repoRoot: Path): FactoryDocsContext {
        val docsRoot = repoRoot.resolve("docs").resolve("factory")
        if (!docsRoot.exists() || !docsRoot.isDirectory()) {
            return FactoryDocsContext(
                repoRoot = repoRoot,
                docsRoot = docsRoot,
                hasFactoryDocs = false,
                indexMarkdown = FACTORY_DOCS_BOOTSTRAP_NOTICE,
                roleInstructions = "",
                deploymentConfig = null,
            )
        }

        val docs = existingDocs(docsRoot)
        val roleDoc = docsRoot.resolve("agents").resolve("${role.markerKeyPart}.md")
        val deploymentConfig = DeploymentConfigParser.parseFile(docsRoot.resolve("deployment.md"))

        return FactoryDocsContext(
            repoRoot = repoRoot,
            docsRoot = docsRoot,
            hasFactoryDocs = true,
            indexMarkdown = renderIndex(docs),
            roleInstructions = roleDoc.takeIf { it.exists() }?.readText().orEmpty(),
            deploymentConfig = deploymentConfig,
        )
    }

    override fun installSkeleton(targetRoot: Path, overwrite: Boolean, skeletonRoot: Path?) =
        DocsSkeletonInstaller(skeletonRoot).install(targetRoot, overwrite)

    override fun recordDeveloperRunStart(repoRoot: Path, issueTrackerKey: String, storyText: String): Path =
        storyLogWriter.recordDeveloperRunStart(repoRoot, issueTrackerKey, storyText)

    override fun ensureStoryWorklog(repoRoot: Path, issueTrackerKey: String, summary: String, description: String?): Path =
        storyLogWriter.ensureStoryWorklog(repoRoot, issueTrackerKey, summary, description)

    override fun writeFinalStory(
        repoRoot: Path,
        issueTrackerKey: String,
        summary: String,
        description: String?,
        finalSummary: String,
    ): Path =
        storyLogWriter.writeFinalStory(repoRoot, issueTrackerKey, summary, description, finalSummary)

    override fun markStepDone(logFile: Path, step: String): Boolean =
        storyLogWriter.markStepDone(logFile, step)

    override fun appendDone(logFile: Path, message: String) =
        storyLogWriter.appendDone(logFile, message)

    private fun existingDocs(docsRoot: Path): List<String> {
        val stream = Files.walk(docsRoot)
        return try {
            stream
                .filter { Files.isRegularFile(it) }
                .map { docsRoot.relativize(it).toString().replace(File.separatorChar, '/') }
                .sorted()
                .toList()
        } finally {
            stream.close()
        }
    }

    private fun renderIndex(docs: List<String>): String =
        buildString {
            appendLine("docs/factory/ - repo-documentatie voor de software factory.")
            docs.forEach { relativePath ->
                val description = knownDescriptions[relativePath]
                if (description == null) {
                    appendLine("  $relativePath")
                } else {
                    appendLine("  $relativePath - $description")
                }
            }
        }

    private companion object {
        val knownDescriptions = mapOf(
            "README.md" to "globale repo-context",
            "secrets-local.md" to "secrets voor lokaal draaien",
            "deployment.md" to "deploy-info en factory-config",
            "development.md" to "build/test-commando's en repo-structuur",
            "functional-spec.md" to "functionele specificatie",
            "technical-spec.md" to "technische stack en conventies",
            "agents/refiner.md" to "instructies voor de refiner",
            "agents/developer.md" to "instructies voor de developer",
            "agents/reviewer.md" to "instructies voor de reviewer",
            "agents/tester.md" to "instructies voor de tester",
            "agents/summarizer.md" to "instructies voor de summarizer",
        )
    }
}
