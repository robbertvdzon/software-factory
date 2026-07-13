package nl.vdzon.softwarefactory.runtime.commands

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.core.contracts.ArgoApplicationStatus
import nl.vdzon.softwarefactory.core.contracts.DeploymentPodInfo
import nl.vdzon.softwarefactory.core.contracts.DeploymentStatusProbe
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

    override fun argoApplicationStatus(namespace: String, application: String): ArgoApplicationStatus? {
        // Eén kubectl-call die de vier relevante velden als '|'-gescheiden regel teruggeeft, zodat
        // we geen aparte JSON-parser nodig hebben. Ontbrekende velden komen als lege segmenten terug.
        val jsonPath = "jsonpath={.status.sync.status}|{.status.health.status}|" +
            "{.status.operationState.phase}|{.status.sync.revision}"
        val result = runCatching {
            commandRunner.run(
                listOf(
                    "kubectl", "get", "application", application,
                    "-n", namespace,
                    "-o", jsonPath,
                ),
            )
        }.getOrElse { ex ->
            logger.warn("kubectl-fout voor ArgoCD-app {}/{}: {}.", namespace, application, ex.message)
            return null
        }
        if (result.exitCode != 0) {
            logger.warn(
                "kubectl get application mislukt voor {}/{}: exitCode={}.",
                namespace,
                application,
                result.exitCode,
            )
            return null
        }
        val parts = result.stdout.trim().split("|")
        return ArgoApplicationStatus(
            syncStatus = parts.getOrNull(0)?.trim().orEmpty(),
            healthStatus = parts.getOrNull(1)?.trim().orEmpty(),
            operationPhase = parts.getOrNull(2)?.trim().orEmpty(),
            revision = parts.getOrNull(3)?.trim().orEmpty(),
        )
    }

    override fun runningPod(namespace: String, deployment: String): DeploymentPodInfo? {
        // Twee stappen: (1) de deployment's label-selector opzoeken (pod-labels staan niet vast,
        // dus niet zomaar te raden), (2) daarmee de daadwerkelijk draaiende pod vinden. Eén pod
        // volstaat (alle deployments hier draaien single-replica); bij meerdere pakken we item 0.
        val matchLabelsJson = runKubectlText(
            listOf("kubectl", "get", "deployment", deployment, "-n", namespace, "-o", "jsonpath={.spec.selector.matchLabels}"),
            "deployment $namespace/$deployment (matchLabels)",
        ) ?: return null
        val selector = runCatching { podInfoMapper.readValue(matchLabelsJson, Map::class.java) }
            .getOrNull()
            ?.entries?.mapNotNull { (k, v) -> if (k is String && v != null) "$k=$v" else null }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",")
        if (selector.isNullOrEmpty()) {
            logger.warn("kubectl: geen labels gevonden op deployment {}/{}.", namespace, deployment)
            return null
        }
        val podLine = runKubectlText(
            listOf(
                "kubectl", "get", "pods", "-n", namespace, "-l", selector,
                "--field-selector=status.phase=Running",
                "-o", "jsonpath={.items[0].status.startTime}|{.items[0].spec.containers[0].image}",
            ),
            "pods in $namespace (selector=$selector)",
        ) ?: return null
        val parts = podLine.split("|")
        val image = parts.getOrNull(1)?.trim().orEmpty()
        if (image.isEmpty()) return null
        return DeploymentPodInfo(image = image, startedAt = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun runKubectlText(command: List<String>, context: String): String? {
        val result = runCatching { commandRunner.run(command) }.getOrElse { ex ->
            logger.warn("kubectl-fout voor {}: {}.", context, ex.message)
            return null
        }
        if (result.exitCode != 0) {
            logger.warn("kubectl mislukt voor {}: exitCode={}.", context, result.exitCode)
            return null
        }
        return result.stdout.trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private val podInfoMapper = ObjectMapper()
    }
}
