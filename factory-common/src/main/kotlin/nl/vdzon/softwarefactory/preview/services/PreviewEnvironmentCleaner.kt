package nl.vdzon.softwarefactory.preview.services

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.support.SupportApi
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.file.Path

interface PreviewEnvironmentCleaner {
    fun cleanup(namespace: String): Boolean
}

class PreviewCleanupException(message: String) : RuntimeException(message)

@Service
class PreviewService(
    private val previewEnvironmentCleaner: PreviewEnvironmentCleaner,
) : PreviewApi {
    override fun render(template: String?, prNumber: Int?): String? =
        PreviewTemplateRenderer.render(template, prNumber)

    override fun cleanup(namespace: String): Boolean =
        previewEnvironmentCleaner.cleanup(namespace)
}

@Component
class OcPreviewEnvironmentCleaner(
    private val git: GitApi,
    private val factorySecrets: FactorySecrets,
) : PreviewEnvironmentCleaner {
    override fun cleanup(namespace: String): Boolean {
        val normalized = namespace.trim()
        if (normalized.isBlank()) {
            return false
        }
        // Apart, minimaal gescopeerd kubeconfig (alleen namespaces/projects get/list/delete) i.p.v.
        // het gedeelde read-only agent-kubeconfig — dat laatste heeft nergens delete-rechten, wat
        // eerder al tot verweesde pnf-pr-*-namespaces leidde (zie docs/cluster-inventory.md §8).
        val env = (factorySecrets.previewCleanupKubeconfig ?: factorySecrets.kubeconfig)
            ?.takeIf { it.isNotBlank() }
            ?.let { mapOf("KUBECONFIG" to localPath(it)) }
            ?: emptyMap()
        val result = git.runCommand(
            command = listOf("oc", "delete", "project", normalized, "--ignore-not-found=true"),
            env = env,
            timeoutSeconds = 120,
        )
        if (result.exitCode != 0) {
            throw PreviewCleanupException("Preview cleanup failed: ${SupportApi.default().redact(result.output).take(1000)}")
        }
        return true
    }

    private fun localPath(value: String): String {
        val trimmed = value.trim()
        val expanded = when {
            trimmed == "~" -> System.getProperty("user.home")
            trimmed.startsWith("~/") -> System.getProperty("user.home") + trimmed.removePrefix("~")
            else -> trimmed
        }
        return Path.of(expanded).toAbsolutePath().normalize().toString()
    }
}
