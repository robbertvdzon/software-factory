package nl.vdzon.softwarefactory.tracker.clients

import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.FactoryStateChangedEvent
import nl.vdzon.softwarefactory.core.SubtaskSpec
import nl.vdzon.softwarefactory.core.SubtaskType
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerApiException
import nl.vdzon.softwarefactory.tracker.repositories.JdbcProcessedCommentStore
import org.flywaydb.core.Flyway
import org.springframework.context.ApplicationEventPublisher
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path

/**
 * Round-trip-tests voor [PostgresTrackerClient] tegen een echte Postgres (Testcontainers), zelfde
 * patroon als [nl.vdzon.softwarefactory.nightly.NightlyRepositoriesTest]: Flyway bouwt het schema
 * via de echte migratie `V15__tracker_issues.sql`, geen mocks.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresTrackerClientTest {

    private val schema = "software_factory"
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var client: PostgresTrackerClient
    private val publishedEvents = mutableListOf<FactoryStateChangedEvent>()

    @TempDir
    lateinit var attachmentsDir: Path

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        dataSource = HikariDataSource().apply {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 2
        }
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .placeholders(mapOf("schema" to schema))
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcTemplate(dataSource)
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
        postgres.stop()
    }

    @BeforeEach
    fun resetTables() {
        // Elke test start met een lege tabel-set (geen aparte container per test — sneller — maar
        // wel isolatie tussen tests).
        jdbc.update("DELETE FROM $schema.issue_comments")
        jdbc.update("DELETE FROM $schema.issue_attachments")
        jdbc.update("DELETE FROM $schema.issues")
        jdbc.update("DELETE FROM $schema.project_key_sequences")
        jdbc.update("DELETE FROM $schema.processed_comments")

        val secrets = FactorySecrets(
            trackerProjects = emptyList(),
            githubToken = "github-token",
            factoryDatabaseUrl = postgres.jdbcUrl,
            factoryDatabaseSchema = schema,
            kubeconfig = null,
            aiCredentialsDir = null,
            aiOauthToken = null,
            loadedFrom = "test",
            trackerAttachmentsDir = attachmentsDir.toString(),
        )
        publishedEvents.clear()
        val recordingEventPublisher = ApplicationEventPublisher { event ->
            if (event is FactoryStateChangedEvent) publishedEvents += event
        }
        client = PostgresTrackerClient(jdbc, secrets, JdbcProcessedCommentStore(jdbc, secrets), recordingEventPublisher)
    }

    @Test
    fun `createStory persists fields and is readable via getIssue`() {
        val story = client.createStory(
            projectKey = "SF",
            title = "Nieuwe story",
            description = "beschrijving",
            repo = "softwarefactory",
            aiSupplier = "claude",
            aiModel = "claude-opus",
            start = true,
            silent = true,
        )
        assertEquals("SF-1", story.key)
        assertEquals("Nieuwe story", story.summary)
        assertEquals("claude", story.fields.aiSupplier)
        assertEquals("start", story.fields.storyPhase)
        assertTrue(story.fields.silent)

        val reloaded = client.getIssue("SF-1")
        assertEquals(story, reloaded)
    }

    @Test
    fun `createStory exposes created_at and updated_at, and updated_at advances on field updates`() {
        // SF-818 — de tijdstempels worden meegeleverd op de fields, zodat het stories-overzicht per
        // regel een tijdstip kan tonen (aanmaak- of afrondmoment).
        val story = client.createStory(projectKey = "SF", title = "Story")
        val createdAt = story.fields.createdAt
        val updatedAt = story.fields.updatedAt
        assertNotNull(createdAt)
        assertNotNull(updatedAt)
        assertFalse(updatedAt!!.isBefore(createdAt))

        // Een status-overgang (bv. afronden) zet updated_at op now(); created_at blijft gelijk.
        client.transitionIssue(story.key, "Done")
        val reloaded = client.getIssue(story.key)
        assertEquals(createdAt, reloaded.fields.createdAt)
        assertFalse(reloaded.fields.updatedAt!!.isBefore(updatedAt))
    }

    @Test
    fun `every write publishes a FactoryStateChangedEvent, failures never break the write`() {
        val story = client.createStory(projectKey = "SF", title = "Story")
        assertEquals(1, publishedEvents.size)

        val subtask = client.createSubtask(story.key, SubtaskSpec(type = SubtaskType.DEVELOPMENT, title = "Implementeer"))
        client.updateIssueFields(story.key, TrackerFieldUpdate.of(TrackerField.STORY_PHASE to "implement"))
        client.updateIssueSummary(story.key, "Nieuwe titel")
        client.updateIssueDescription(story.key, "Nieuwe beschrijving")
        client.transitionIssue(story.key, "Done")
        client.postComment(story.key, "Opmerking")

        assertEquals(7, publishedEvents.size)
        assertTrue(publishedEvents.all { it.origin.startsWith("tracker-write:") })

        // updateIssueFields is a no-op (early return) voor een lege update — geen extra event.
        client.updateIssueFields(subtask.key, TrackerFieldUpdate.of())
        assertEquals(7, publishedEvents.size)
    }

    @Test
    fun `transitionIssue is a no-op when the status is already the target status`() {
        // SF-904: voorkomt dat een reeds afgeronde subtask/story zichzelf blijft "opwekken" doordat
        // updated_at (en dus de recent-lijst van findAiIssues) telkens opnieuw bumpt.
        val story = client.createStory(projectKey = "SF", title = "Story")
        client.transitionIssue(story.key, "Done")
        val afterFirstTransition = client.getIssue(story.key)
        val eventsAfterFirstTransition = publishedEvents.size

        client.transitionIssue(story.key, "Done")
        val afterNoopTransition = client.getIssue(story.key)

        assertEquals(afterFirstTransition.fields.updatedAt, afterNoopTransition.fields.updatedAt)
        assertEquals(eventsAfterFirstTransition, publishedEvents.size)

        // Bij een echte statuswijziging blijft het gedrag ongewijzigd: write + updated_at-bump + event.
        client.transitionIssue(story.key, "In Progress")
        val afterRealTransition = client.getIssue(story.key)
        assertEquals("In Progress", afterRealTransition.status)
        assertFalse(afterRealTransition.fields.updatedAt!!.isBefore(afterNoopTransition.fields.updatedAt))
        assertTrue(afterRealTransition.fields.updatedAt!!.isAfter(afterNoopTransition.fields.updatedAt))
        assertEquals(eventsAfterFirstTransition + 1, publishedEvents.size)
    }

    @Test
    fun `updateIssueFields is a no-op when all given field values already match the current row`() {
        val story = client.createStory(projectKey = "SF", title = "Story")
        client.updateIssueFields(
            story.key,
            TrackerFieldUpdate.of(TrackerField.STORY_PHASE to "implement", TrackerField.PAUSED to true),
        )
        val afterFirstUpdate = client.getIssue(story.key)
        val eventsAfterFirstUpdate = publishedEvents.size

        // Volledige no-op: beide velden zijn al gelijk aan de huidige rij-waarden.
        client.updateIssueFields(
            story.key,
            TrackerFieldUpdate.of(TrackerField.STORY_PHASE to "implement", TrackerField.PAUSED to true),
        )
        val afterNoopUpdate = client.getIssue(story.key)
        assertEquals(afterFirstUpdate.fields.updatedAt, afterNoopUpdate.fields.updatedAt)
        assertEquals(eventsAfterFirstUpdate, publishedEvents.size)

        // Eén van de velden wijzigt daadwerkelijk → normale update, inclusief het ongewijzigde veld.
        client.updateIssueFields(
            story.key,
            TrackerFieldUpdate.of(TrackerField.STORY_PHASE to "implement", TrackerField.PAUSED to false),
        )
        val afterRealUpdate = client.getIssue(story.key)
        assertEquals("implement", afterRealUpdate.fields.storyPhase)
        assertFalse(afterRealUpdate.fields.paused)
        assertTrue(afterRealUpdate.fields.updatedAt!!.isAfter(afterNoopUpdate.fields.updatedAt))
        assertEquals(eventsAfterFirstUpdate + 1, publishedEvents.size)
    }

    @Test
    fun `getIssue throws for unknown key`() {
        assertThrows(TrackerApiException::class.java) { client.getIssue("SF-999") }
    }

    @Test
    fun `createSubtask links to parent and appears in subtasksOf and existingSubtaskTitles`() {
        val story = client.createStory(projectKey = "SF", title = "Parent story")
        val sub1 = client.createSubtask(story.key, SubtaskSpec(type = SubtaskType.DEVELOPMENT, title = "Implementeer feature"), supplier = "claude")
        val sub2 = client.createSubtask(story.key, SubtaskSpec(type = SubtaskType.REVIEW, title = "Review de wijzigingen"))

        assertEquals(listOf(sub1.key, sub2.key), client.subtasksOf(story.key).map { it.key })
        assertEquals(setOf("Implementeer feature", "Review de wijzigingen"), client.existingSubtaskTitles(story.key))
        assertEquals(story.key, client.parentStoryKey(sub1.key))
        assertNull(client.parentStoryKey(story.key))
        assertEquals("Task", sub1.fields.type)
        assertEquals("development", sub1.fields.subtaskType)
    }

    @Test
    fun `updateIssueFields is visible on next getIssue`() {
        val story = client.createStory(projectKey = "SF", title = "Story")
        client.updateIssueFields(
            story.key,
            TrackerFieldUpdate.of(
                TrackerField.STORY_PHASE to "implement",
                TrackerField.PAUSED to true,
                TrackerField.AI_LEVEL to 3,
            ),
        )
        val reloaded = client.getIssue(story.key)
        assertEquals("implement", reloaded.fields.storyPhase)
        assertTrue(reloaded.fields.paused)
        assertEquals(3, reloaded.fields.aiLevel)
    }

    @Test
    fun `postComment and postAgentComment round-trip, deleteAgentComments only removes agent-prefixed`() {
        val story = client.createStory(projectKey = "SF", title = "Story")
        val human = client.postComment(story.key, "Menselijke opmerking")
        client.postAgentComment(story.key, AgentRole.DEVELOPER, "status update")

        val reloaded = client.getIssue(story.key)
        assertEquals(2, reloaded.comments.size)
        assertTrue(reloaded.comments.any { it.body == "Menselijke opmerking" && !it.isAgentComment })
        assertTrue(reloaded.comments.any { it.body.startsWith("[DEVELOPER]") && it.isAgentComment })

        val deleted = client.deleteAgentComments(story.key)
        assertEquals(1, deleted)
        val afterDelete = client.getIssue(story.key)
        assertEquals(listOf(human.id), afterDelete.comments.map { it.id })
    }

    @Test
    fun `attachments round-trip through local disk`() {
        val story = client.createStory(projectKey = "SF", title = "Story")
        val bytes = byteArrayOf(1, 2, 3, 4)
        val attachment = client.uploadIssueAttachment(story.key, "screenshot.png", "image/png", bytes)

        val listed = client.listIssueAttachments(story.key)
        assertEquals(listOf(attachment.id), listed.map { it.id })

        val downloaded = client.downloadAttachmentBytes(attachment)
        assertTrue(bytes.contentEquals(downloaded))

        client.deleteIssueAttachment(story.key, attachment.id)
        assertTrue(client.listIssueAttachments(story.key).isEmpty())
        assertNull(client.downloadAttachmentBytes(attachment))
    }

    @Test
    fun `hasProcessedCommentMarker and markCommentProcessed delegate to the existing processed_comments table`() {
        val story = client.createStory(projectKey = "SF", title = "Story")
        val comment = client.postComment(story.key, "comment")

        assertFalse(client.hasProcessedCommentMarker(story.key, comment.id, AgentRole.DEVELOPER))
        assertTrue(client.markCommentProcessed(story.key, comment.id, AgentRole.DEVELOPER))
        assertTrue(client.hasProcessedCommentMarker(story.key, comment.id, AgentRole.DEVELOPER))

        // Zelfde rij die ook door JdbcProcessedCommentStore gebruikt wordt — geen nieuwe tabel.
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM $schema.processed_comments WHERE story_key = ? AND comment_id = ?",
            Int::class.java,
            story.key,
            comment.id,
        )
        assertEquals(1, count)
    }

    @Test
    fun `deleteIssue removes comments and attachments via cascade`() {
        val story = client.createStory(projectKey = "SF", title = "Story")
        client.postComment(story.key, "comment")
        client.uploadIssueAttachment(story.key, "shot.png", "image/png", byteArrayOf(9))

        client.deleteIssue(story.key)

        assertThrows(TrackerApiException::class.java) { client.getIssue(story.key) }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM $schema.issue_comments WHERE issue_key = ?", Int::class.java, story.key))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM $schema.issue_attachments WHERE issue_key = ?", Int::class.java, story.key))
    }

    @Test
    fun `findAiIssues filters on non-blank ai-supplier and sorts by updated_at desc`() {
        client.createStory(projectKey = "SF", title = "Zonder supplier")
        val withSupplier = client.createStory(projectKey = "SF", title = "Met supplier", aiSupplier = "claude")
        client.createStory(projectKey = "SF", title = "Supplier none", aiSupplier = "none")

        val work = client.findAiIssues(maxResults = 50)
        assertEquals(listOf(withSupplier.key), work.map { it.key })
    }

    @Test
    fun `findAiIssues always includes a non-terminal waiting subtask even outside the top-N updated_at window`() {
        // SF-862: een subtaak die op een mens wacht (bv. manual-approve-needed) mag nooit uit de
        // pollset vallen, ook niet als er meer dan maxResults recenter bijgewerkte issues zijn.
        val story = client.createStory(projectKey = "SF", title = "Story", aiSupplier = "claude")
        val waitingSubtask = client.createSubtask(
            story.key,
            SubtaskSpec(type = SubtaskType.MANUAL_APPROVE, title = "Wacht op mens"),
            supplier = "claude",
        )
        client.updateIssueFields(
            waitingSubtask.key,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to "manual-approve-needed"),
        )
        // Zet updated_at ver in het verleden, ver buiten de top-N-window.
        jdbc.update(
            "UPDATE $schema.issues SET updated_at = now() - interval '1 year' WHERE issue_key = ?",
            waitingSubtask.key,
        )

        // Genereer meer dan maxResults recentere issues zonder wachtende fase, die anders de
        // wachtende subtaak uit de top-N zouden verdringen.
        repeat(5) { i -> client.createStory(projectKey = "SF", title = "Ruis $i", aiSupplier = "claude") }

        val work = client.findAiIssues(maxResults = 2)

        assertTrue(
            work.any { it.key == waitingSubtask.key },
            "wachtende subtaak (${waitingSubtask.key}) moet altijd meegenomen worden, ook buiten de top-N: ${work.map { it.key }}",
        )
    }

    @Test
    fun `findAiIssues does not include a terminal subtask that falls outside the top-N updated_at window`() {
        // Contrast-test: het normale (terminale) gedrag verandert niet — alleen de niet-terminale
        // wacht-op-mens-subset krijgt een uitzondering op de LIMIT.
        val story = client.createStory(projectKey = "SF", title = "Story", aiSupplier = "claude")
        val doneSubtask = client.createSubtask(
            story.key,
            SubtaskSpec(type = SubtaskType.REVIEW, title = "Al afgerond"),
            supplier = "claude",
        )
        client.updateIssueFields(
            doneSubtask.key,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to "review-approved"),
        )
        jdbc.update(
            "UPDATE $schema.issues SET updated_at = now() - interval '1 year' WHERE issue_key = ?",
            doneSubtask.key,
        )

        repeat(5) { i -> client.createStory(projectKey = "SF", title = "Ruis $i", aiSupplier = "claude") }

        val work = client.findAiIssues(maxResults = 2)

        assertFalse(work.any { it.key == doneSubtask.key })
        assertEquals(2, work.size)
    }

    @Test
    fun `findAiIssues keeps a finished story that still has a non-terminal subtask`() {
        // Een niet-afgeronde subtaak (bv. wacht-op-mens) blijft bereikbaar, ook als de parent-story
        // zelf al een afgeronde status heeft — de union-tak met niet-terminale subtask_phase (SF-862).
        // (SF-918's done-filter op de top-N-tak is teruggedraaid: advanceSubtaskChain zet de
        // story-status momenteel te vroeg op "Done", waardoor die filter vrijwel alle stories
        // wegfilterde — zie het reverterende commit.)
        val story = client.createStory(projectKey = "SF", title = "Story", aiSupplier = "claude")
        client.transitionIssue(story.key, "Done")
        val activeSubtask = client.createSubtask(
            story.key,
            SubtaskSpec(type = SubtaskType.MANUAL_APPROVE, title = "Wacht op mens"),
            supplier = "claude",
        )
        client.updateIssueFields(
            activeSubtask.key,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to "manual-approve-needed"),
        )

        val work = client.findAiIssues(maxResults = 50)

        assertTrue(work.any { it.key == activeSubtask.key })
    }

    @Test
    fun `an approve command on a stale waiting subtask is processed at the next poll despite the LIMIT`() {
        // SF-862 acceptatiecriterium: een geldig @factory:command:approve-commentaar op een
        // niet-terminale, wachtende gate leidt aantoonbaar tot verwerking, ongeacht updated_at-rangorde.
        val story = client.createStory(projectKey = "SF", title = "Story", aiSupplier = "claude")
        val waitingSubtask = client.createSubtask(
            story.key,
            SubtaskSpec(type = SubtaskType.MANUAL_APPROVE, title = "Wacht op mens"),
            supplier = "claude",
        )
        client.updateIssueFields(
            waitingSubtask.key,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to "manual-approve-needed"),
        )
        jdbc.update(
            "UPDATE $schema.issues SET updated_at = now() - interval '1 year' WHERE issue_key = ?",
            waitingSubtask.key,
        )
        repeat(60) { i -> client.createStory(projectKey = "SF", title = "Ruis $i", aiSupplier = "claude") }

        // postComment() bumpt updated_at bewust niet — de fix moet ook zonder die bump werken.
        client.postComment(waitingSubtask.key, "@factory:command:approve")

        val work = client.findAiIssues(maxResults = 50)
        val processed = work.firstOrNull { it.key == waitingSubtask.key }

        assertNotNull(processed, "wachtende subtaak moet ondanks 60 recentere issues in de pollset zitten")
        assertTrue(
            processed!!.comments.any { it.body == "@factory:command:approve" },
            "het approve-commentaar moet zichtbaar zijn zodat ManualCommandService het bij deze poll kan verwerken",
        )
    }

    @Test
    fun `sequential key generation never collides across stories and subtasks`() {
        val story = client.createStory(projectKey = "SF", title = "Story")
        val subtask = client.createSubtask(story.key, SubtaskSpec(type = SubtaskType.TEST, title = "Test"))
        val story2 = client.createStory(projectKey = "SF", title = "Story 2")
        assertEquals(setOf("SF-1", "SF-2", "SF-3"), setOf(story.key, subtask.key, story2.key))
    }
}
