package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.runtime.workspaces.AgentWorkspaceFactory
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.readText

class AgentWorkspaceTipsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `tips in de prompt zijn gecapt op de recentste entries met een weglaat-voetnoot`() {
        val basis = OffsetDateTime.parse("2026-07-01T10:00:00+02:00")
        val entries = (1..AgentWorkspaceFactory.MAX_TIPS_IN_PROMPT + 5).map { index ->
            AgentKnowledgeEntry(
                targetRepo = "github.com/robbertvdzon/demo",
                role = "developer",
                category = "build",
                key = "tip-$index",
                content = "inhoud van tip $index",
                updatedByStory = null,
                // Hogere index = recenter bijgewerkt.
                updatedAt = basis.plusMinutes(index.toLong()),
            )
        }
        val factory = AgentWorkspaceFactory(knowledgeApi = FakeKnowledgeApi(entries))

        val workspace = factory.create(
            AgentDispatchRequest(
                storyKey = "KAN-1",
                targetRepo = "github.com/robbertvdzon/demo",
                storyRunId = 1,
                role = AgentRole.DEVELOPER,
                phase = "developing",
                workspacePath = tempDir.resolve("ws").toString(),
            ),
            sfEnvironment = emptyMap(),
        )

        val tips = workspace.tipsFile.readText()
        // De 5 oudste tips (1..5) vallen buiten de cap; de recentste blijven staan.
        assertTrue(tips.contains("tip-${AgentWorkspaceFactory.MAX_TIPS_IN_PROMPT + 5}"), "recentste tip hoort erin")
        assertTrue(tips.contains("tip-6"), "tip net binnen de cap hoort erin")
        assertFalse(tips.contains("tip-5\n") || tips.contains("## build / tip-5"), "oudste tips horen eruit")
        assertTrue(tips.contains("5 oudere tips weggelaten"), "voetnoot benoemt het aantal weggelaten tips")
    }

    private class FakeKnowledgeApi(private val entries: List<AgentKnowledgeEntry>) : KnowledgeApi {
        override fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> = entries

        override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry =
            throw UnsupportedOperationException("niet nodig in deze test")
    }
}
