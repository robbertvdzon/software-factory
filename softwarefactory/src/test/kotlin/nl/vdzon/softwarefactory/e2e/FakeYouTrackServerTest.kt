package nl.vdzon.softwarefactory.e2e

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.youtrack.SubtaskSpec
import nl.vdzon.softwarefactory.youtrack.SubtaskType
import nl.vdzon.softwarefactory.youtrack.TrackerField
import nl.vdzon.softwarefactory.youtrack.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.clients.YouTrackClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.http.HttpClient

/**
 * Drijft de echte [YouTrackClient] tegen de stateful [FakeYouTrackServer]. Dit
 * dekt het volledige HTTP-pad (serialisatie + state) dat de end-to-end test
 * (SF-1) nodig heeft: schema-bootstrap, work-issue-discovery, field-updates,
 * subtask-aanmaak/-links en de processed-comment-marker.
 */
class FakeYouTrackServerTest {

    private fun client(server: FakeYouTrackServer): YouTrackClient =
        YouTrackClient(
            factorySecrets = FactorySecrets(
                youTrackBaseUrl = server.baseUrl,
                youTrackToken = "youtrack-token",
                youTrackProjects = emptyList(),
                githubToken = "github-token",
                factoryDatabaseUrl = "postgresql://example/db",
                factoryDatabaseSchema = "software_factory",
                kubeconfig = null,
                aiCredentialsDir = null,
                aiOauthToken = null,
                loadedFrom = "test",
            ),
            projectRepoResolver = ProjectRepoResolver(mapOf("sample" to "git@example/sample.git")),
            objectMapper = jacksonObjectMapper(),
            httpClient = HttpClient.newHttpClient(),
        )

    @Test
    fun `bootstraps the seeded project schema without creating fields`() {
        FakeYouTrackServer().use { server ->
            val projects = client(server).ensureConfiguredProjects()

            assertEquals(listOf("SP"), projects.map { it.key })
            // Volledig geseed schema -> geen create-calls nodig.
            assertFalse(server.requests.any { it.method == "POST" && it.path == "/api/admin/customFieldSettings/customFields" })
            assertFalse(server.requests.any { it.method == "POST" && it.path.endsWith("/bundle/values") })
        }
    }

    @Test
    fun `finds work issues by project and reflects field updates statefully`() {
        FakeYouTrackServer().use { server ->
            val client = client(server)
            // Direct in de state: een story zoals de test 'm zou aanmaken.
            server.state.createIssue("Build the first app", "Description here", key = "SP-1")

            // Geen labels meer: kandidaten worden bepaald door het project + een gezette AI-supplier.
            // Zonder supplier -> nog geen werk.
            assertTrue(client.findWorkIssues().isEmpty())

            // Supplier gezet -> zichtbaar als werk (de fase-gate zit in de orchestrator, niet hier).
            server.state.setEnumField("SP-1", "AI-supplier", "claude")
            val work = client.findWorkIssues()
            assertEquals(listOf("SP-1"), work.map { it.key })
            assertEquals("claude", work.single().fields.aiSupplier)

            // Field-update gaat over HTTP en is daarna leesbaar via getIssue.
            client.updateIssueFields(
                "SP-1",
                TrackerFieldUpdate.of(
                    TrackerField.STORY_PHASE to "refined",
                    TrackerField.PAUSED to true,
                ),
            )
            val reread = client.getIssue("SP-1")
            assertEquals("refined", reread.fields.storyPhase)
            assertTrue(reread.fields.paused)

            // Supplier op `none` -> valt weer buiten de werk-selectie.
            server.state.setEnumField("SP-1", "AI-supplier", "none")
            assertTrue(client.findWorkIssues().isEmpty())
        }
    }

    @Test
    fun `creates a subtask and links it to its parent`() {
        FakeYouTrackServer().use { server ->
            val client = client(server)
            server.state.createIssue("Parent story", key = "SP-1")

            val subtask = client.createSubtask(
                parentKey = "SP-1",
                spec = SubtaskSpec(type = SubtaskType.DEVELOPMENT, title = "Implement feature", description = "do it"),
                supplier = "claude",
            )

            assertEquals("Implement feature", subtask.summary)
            assertEquals(listOf(subtask.key), client.subtasksOf("SP-1").map { it.key })
            assertEquals("SP-1", client.parentStoryKey(subtask.key))
            assertTrue(client.existingSubtaskTitles("SP-1").contains("Implement feature"))
        }
    }

    @Test
    fun `createStory makes a User Story with repo, supplier and start phase`() {
        FakeYouTrackServer().use { server ->
            val client = client(server)

            val story = client.createStory(
                projectKey = "SP",
                title = "Nieuwe story",
                description = "Doe iets",
                repo = "sample",
                aiSupplier = "claude",
                start = true,
            )

            assertEquals("Nieuwe story", story.summary)
            assertEquals(nl.vdzon.softwarefactory.youtrack.IssueType.STORY, story.issueType)
            assertEquals("sample", story.fields.repo)
            assertEquals("claude", story.fields.aiSupplier)
            assertEquals("start", story.fields.storyPhase)
        }
    }

    @Test
    fun `posts a comment and toggles the processed marker`() {
        FakeYouTrackServer().use { server ->
            val client = client(server)
            server.state.createIssue("Story", key = "SP-1")

            val comment = client.postAgentComment("SP-1", AgentRole.DEVELOPER, "done")
            assertEquals("[DEVELOPER] done", comment.body)

            assertFalse(client.hasProcessedCommentMarker("SP-1", comment.id, AgentRole.DEVELOPER))
            assertTrue(client.markCommentProcessed("SP-1", comment.id, AgentRole.DEVELOPER))
            assertTrue(client.hasProcessedCommentMarker("SP-1", comment.id, AgentRole.DEVELOPER))
        }
    }
}
