package nl.vdzon.softwarefactory.docs

import nl.vdzon.softwarefactory.docs.services.FactoryDocsLoader
import nl.vdzon.softwarefactory.youtrack.AgentRole
import java.nio.file.Path

/**
 * Public API of the docs module.
 *
 * The docs module owns factory documentation loading, deployment config parsing,
 * skeleton installation and story log updates inside target repositories.
 */
interface DocsApi {
    fun loadFactoryDocs(role: AgentRole, repoRoot: Path = Path.of("/work/repo")): FactoryDocsContext

    fun installSkeleton(targetRoot: Path, overwrite: Boolean = false, skeletonRoot: Path? = null): DocsInstallResult

    fun recordDeveloperRunStart(repoRoot: Path, issueTrackerKey: String, storyText: String): Path

    fun markStepDone(logFile: Path, step: String): Boolean

    fun appendDone(logFile: Path, message: String)

    companion object {
        fun default(): DocsApi = FactoryDocsLoader()
    }
}

const val FACTORY_DOCS_BOOTSTRAP_NOTICE: String =
    "Deze repo heeft nog geen `docs/factory/`-map. Er is dus nog geen extra info over deze codebase beschikbaar buiten wat in de issue staat. De developer wordt geacht de map en de standaardbestanden aan te maken op basis van de skeleton-template (gemount op `/usr/local/share/factory/docs-skeleton/`) en aan te vullen met informatie uit deze story en de bestaande repo-structuur, als onderdeel van zijn PR."

data class FactoryDocsContext(
    val repoRoot: Path,
    val docsRoot: Path,
    val hasFactoryDocs: Boolean,
    val indexMarkdown: String,
    val roleInstructions: String,
    val deploymentConfig: DeploymentConfig?,
) {
    fun promptMarkdown(): String =
        buildString {
            appendLine("## Target Repo Factory Docs")
            appendLine()
            if (!hasFactoryDocs) {
                appendLine(FACTORY_DOCS_BOOTSTRAP_NOTICE)
                return@buildString
            }

            append(indexMarkdown)
            appendLine()
            appendLine("Lees deze bestanden via je file-tools als je extra context nodig hebt.")
            appendLine()
            appendLine("## Rol-specifieke instructies")
            appendLine()
            if (roleInstructions.isBlank()) {
                appendLine("Geen rol-specifieke instructies gevonden.")
            } else {
                appendLine(roleInstructions.trimEnd())
            }
        }
}

data class DeploymentConfig(
    val defaultBaseBranch: String = "main",
    val branchPrefix: String = "ai/",
    val previewUrlTemplate: String? = null,
    val previewNamespaceTemplate: String? = null,
    val previewDbSecretRecipe: String? = null,
)

data class DocsInstallResult(
    val created: List<String>,
    val skipped: List<String>,
)
