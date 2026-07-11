package nl.vdzon.softwarefactory.runtime.workspaces

import nl.vdzon.softwarefactory.core.AgentRunRepository
import nl.vdzon.softwarefactory.core.ActiveWorkspaceSource
import nl.vdzon.softwarefactory.core.StoryRunRepository
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class RunRepositoryActiveWorkspaceSource(
    private val storyRuns: StoryRunRepository,
    private val agentRuns: AgentRunRepository,
) : ActiveWorkspaceSource {
    override fun activePaths(): Set<Path> =
        (storyRuns.activeRuns().mapNotNull { it.workspacePath } +
            agentRuns.activeRuns().mapNotNull { it.workspacePath })
            .filter { it.isNotBlank() }
            .mapTo(linkedSetOf(), Path::of)
}
