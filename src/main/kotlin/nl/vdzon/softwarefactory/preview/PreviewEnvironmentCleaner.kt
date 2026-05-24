package nl.vdzon.softwarefactory.preview

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.git.ProcessRunner
import nl.vdzon.softwarefactory.runtime.SecretRedactor
import org.springframework.stereotype.Component
import java.nio.file.Path

interface PreviewEnvironmentCleaner {
    fun cleanup(namespace: String): Boolean
}

class PreviewCleanupException(message: String) : RuntimeException(message)

@Component
class OcPreviewEnvironmentCleaner(
    private val processRunner: ProcessRunner,
    private val factorySecrets: FactorySecrets,
) : PreviewEnvironmentCleaner {
    override fun cleanup(namespace: String): Boolean {
        val normalized = namespace.trim()
        if (normalized.isBlank()) {
            return false
        }
        val env = factorySecrets.kubeconfig
            ?.takeIf { it.isNotBlank() }
            ?.let { mapOf("KUBECONFIG" to localPath(it)) }
            ?: emptyMap()
        val result = processRunner.run(
            command = listOf("oc", "delete", "project", normalized, "--ignore-not-found=true"),
            env = env,
            timeoutSeconds = 120,
        )
        if (result.exitCode != 0) {
            throw PreviewCleanupException("Preview cleanup failed: ${SecretRedactor.redact(result.output).take(1000)}")
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
