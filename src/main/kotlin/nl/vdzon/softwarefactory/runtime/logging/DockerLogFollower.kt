package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.runtime.AgentEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream
import kotlin.concurrent.thread

interface DockerLogFollower {
    fun follow(containerName: String, agentRunId: Long)
}

@Component
class ProcessBuilderDockerLogFollower(
    private val agentEventRepository: AgentEventRepository,
) : DockerLogFollower {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun follow(containerName: String, agentRunId: Long) {
        thread(name = "docker-log-$containerName", isDaemon = true) {
            runCatching {
                val process = ProcessBuilder("docker", "logs", "-f", "--timestamps", containerName).start()
                val stdout = stream(process.inputStream, agentRunId, containerName, "docker-stdout")
                val stderr = stream(process.errorStream, agentRunId, containerName, "docker-stderr")
                process.waitFor()
                stdout.join(1000)
                stderr.join(1000)
            }.onFailure { exception ->
                logger.warn("Docker log capture failed for {}", containerName, exception)
            }
        }
    }

    private fun stream(
        input: InputStream,
        agentRunId: Long,
        containerName: String,
        kind: String,
    ): Thread =
        thread(name = "$kind-$containerName", isDaemon = true) {
            input.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    agentEventRepository.append(
                        agentRunId,
                        kind,
                        mapOf(
                            "containerName" to containerName,
                            "line" to line,
                        ),
                    )
                }
            }
        }
}
