package nl.vdzon.softwarefactory.runtime.commands

import nl.vdzon.softwarefactory.core.DeploymentStatusProbe
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Adapter voor de [DeploymentStatusProbe]-poort op basis van `kubectl get deployment`.
 * Hergebruikt [CommandRunner] (i.p.v. een losse ProcessBuilder) zodat al het externe
 * proces-gedrag (timeout, metrics) op één plek zit en tests de poort kunnen faken.
 */
@Component
class KubectlDeploymentStatusProbe(
    private val commandRunner: CommandRunner,
) : DeploymentStatusProbe {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun currentImage(namespace: String, deployment: String): String? {
        val result = runCatching {
            commandRunner.run(
                listOf(
                    "kubectl", "get", "deployment", deployment,
                    "-n", namespace,
                    "-o", "jsonpath={.spec.template.spec.containers[0].image}",
                ),
            )
        }.getOrElse { ex ->
            logger.warn("kubectl-fout voor deployment {}/{}: {}.", namespace, deployment, ex.message)
            return null
        }
        if (result.exitCode != 0) {
            logger.warn(
                "kubectl get deployment mislukt voor {}/{}: exitCode={}.",
                namespace,
                deployment,
                result.exitCode,
            )
            return null
        }
        return result.stdout.trim()
    }
}
