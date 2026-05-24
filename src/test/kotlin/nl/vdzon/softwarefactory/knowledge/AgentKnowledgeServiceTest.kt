package nl.vdzon.softwarefactory.knowledge

import nl.vdzon.softwarefactory.knowledge.services.*
import nl.vdzon.softwarefactory.knowledge.repositories.*

import nl.vdzon.softwarefactory.knowledge.repositories.AgentKnowledgeRepository
import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class AgentKnowledgeServiceTest {
    @Test
    fun `normalizes target repos and isolates tips by role`() {
        val repository = FakeAgentKnowledgeRepository()
        val service = AgentKnowledgeService(repository)

        service.upsert(
            AgentKnowledgeUpdateRequest(
                targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
                role = "tester",
                category = "login",
                key = "admin",
                content = "Gebruik preview login.",
                updatedByStory = "KAN-11",
            ),
        )
        service.upsert(
            AgentKnowledgeUpdateRequest(
                targetRepo = "https://github.com/robbertvdzon/sample-build-project",
                role = "developer",
                category = "build",
                key = "test",
                content = "Run mvn test.",
            ),
        )

        val testerTips = service.find("https://github.com/robbertvdzon/sample-build-project.git", "tester")
        val developerTips = service.find("git@github.com:robbertvdzon/sample-build-project.git", "developer")

        assertEquals("github.com/robbertvdzon/sample-build-project", testerTips.single().targetRepo)
        assertEquals("Gebruik preview login.", testerTips.single().content)
        assertEquals("Run mvn test.", developerTips.single().content)
    }

    @Test
    fun `upsert replaces existing content`() {
        val service = AgentKnowledgeService(FakeAgentKnowledgeRepository())
        val request = AgentKnowledgeUpdateRequest(
            targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
            role = "reviewer",
            category = "review",
            key = "style",
            content = "Let op nullability.",
        )

        service.upsert(request)
        service.upsert(request.copy(content = "Let op nullability en errors."))

        assertEquals(
            "Let op nullability en errors.",
            service.find("git@github.com:robbertvdzon/sample-build-project.git", "reviewer").single().content,
        )
    }

    @Test
    fun `rejects non agent roles and blank keys`() {
        val service = AgentKnowledgeService(FakeAgentKnowledgeRepository())

        assertThrows(IllegalArgumentException::class.java) {
            service.find("git@example/repo.git", "orchestrator")
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.upsert(
                AgentKnowledgeUpdateRequest(
                    targetRepo = "git@example/repo.git",
                    role = "tester",
                    category = "general",
                    key = " ",
                    content = "content",
                ),
            )
        }
    }

    private class FakeAgentKnowledgeRepository : AgentKnowledgeRepository {
        private val entries = linkedMapOf<Triple<String, String, Pair<String, String>>, AgentKnowledgeEntry>()

        override fun find(targetRepo: String, role: AgentRole): List<AgentKnowledgeEntry> =
            entries.values
                .filter { it.targetRepo == targetRepo && it.role == role.markerKeyPart }
                .sortedWith(compareBy({ it.category }, { it.key }))

        override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry {
            val entry = AgentKnowledgeEntry(
                targetRepo = request.targetRepo,
                role = request.role,
                category = request.category,
                key = request.key,
                content = request.content,
                updatedByStory = request.updatedByStory,
                updatedAt = OffsetDateTime.parse("2026-05-24T12:00:00Z"),
            )
            entries[Triple(request.targetRepo, request.role, request.category to request.key)] = entry
            return entry
        }
    }
}
