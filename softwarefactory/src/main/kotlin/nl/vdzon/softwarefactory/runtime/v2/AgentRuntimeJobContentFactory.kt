package nl.vdzon.softwarefactory.runtime.v2

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.contracts.AgentInputAttachment
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

@Component
class AgentRuntimeJobContentFactory(
    private val instructionFactory: AgentRuntimeInstructionFactory,
    private val inputUploads: AgentRuntimeInputUploadService,
) {
    fun input(idempotencyKey: String, request: AgentDispatchRequest): RuntimeJobInput {
        val fullInstruction = instructionFactory.instruction(request)
        val promptUpload = fullInstruction.takeIf { it.length > MAX_INLINE_INSTRUCTION_CHARS }?.let {
            AgentInputAttachment(
                logicalName = "full-prompt",
                originalFilename = "prompt.md",
                uploadFilename = "prompt.md",
                mimeType = "text/markdown",
                bytes = it.toByteArray(StandardCharsets.UTF_8),
                role = "PROMPT",
            )
        }
        val objects = inputUploads.upload(idempotencyKey, request.inputAttachments + listOfNotNull(promptUpload))
        val instruction = if (promptUpload == null) {
            fullInstruction
        } else {
            "Lees `/job/input/objects/full-prompt/content` volledig en voer die opdracht uit."
        }
        return RuntimeJobInput(instruction, objects)
    }

    fun output(role: AgentRole): RuntimeOutputContract = RuntimeOutputContract(
        resultSchema = instructionFactory.resultSchema(role),
        artifacts = when (role) {
            AgentRole.TESTER -> listOf(
                RuntimeArtifactDeclaration(
                    name = "screenshots.zip",
                    required = false,
                    mimeTypes = listOf("application/zip"),
                    maxBytes = 100L * 1024 * 1024,
                ),
            )
            else -> emptyList()
        },
    )

    private companion object {
        const val MAX_INLINE_INSTRUCTION_CHARS = 65_536
    }
}
