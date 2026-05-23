package nl.vdzon.softwarefactory.docs

import nl.vdzon.softwarefactory.jira.AgentRole
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

const val FACTORY_DOCS_BOOTSTRAP_NOTICE: String =
    "Deze repo heeft nog geen `docs/factory/`-map. Er is dus nog geen extra info over deze codebase beschikbaar buiten wat in de Jira-story staat. De developer wordt geacht de map en de standaardbestanden aan te maken op basis van de skeleton-template (gemount op `/usr/local/share/factory/docs-skeleton/`) en aan te vullen met informatie uit deze story en de bestaande repo-structuur, als onderdeel van zijn PR."

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

fun loadFactoryDocs(role: AgentRole, repoRoot: Path = Path.of("/work/repo")): FactoryDocsContext =
    FactoryDocsLoader().load(role, repoRoot)

class FactoryDocsLoader {
    fun load(role: AgentRole, repoRoot: Path): FactoryDocsContext {
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
        )
    }
}
