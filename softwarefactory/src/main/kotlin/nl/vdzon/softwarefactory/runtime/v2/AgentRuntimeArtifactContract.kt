package nl.vdzon.softwarefactory.runtime.v2

import nl.vdzon.softwarefactory.core.AgentRole

object AgentRuntimeArtifactContract {
    const val TESTER_SCREENSHOTS = "screenshots"
    const val TESTER_SCREENSHOTS_MAX_BYTES = 100L * 1024 * 1024

    fun declarations(role: AgentRole): List<RuntimeArtifactDeclaration> = when (role) {
        AgentRole.TESTER -> listOf(
            RuntimeArtifactDeclaration(
                name = TESTER_SCREENSHOTS,
                required = false,
                mimeTypes = listOf("application/zip"),
                maxBytes = TESTER_SCREENSHOTS_MAX_BYTES,
            ),
        )
        else -> emptyList()
    }
}
