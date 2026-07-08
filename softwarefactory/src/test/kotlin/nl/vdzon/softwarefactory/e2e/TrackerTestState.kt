package nl.vdzon.softwarefactory.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.youtrack.clients.PostgresTrackerClient
import nl.vdzon.softwarefactory.youtrack.repositories.JdbcProcessedCommentStore
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JDBC-backed vervanger van de oude `FakeYouTrackState`: schrijft/leest rechtstreeks in de
 * Postgres-tracker-tabellen die [PostgresTrackerClient] ook in productie gebruikt (`SF_TRACKER_BACKEND=
 * postgres`), zodat de e2e-suite het echte productiepad test i.p.v. een YouTrack-REST-mock.
 *
 * Delegeert waar mogelijk naar een eigen [PostgresTrackerClient]-instantie (zelfde `jdbcTemplate`/schema)
 * om kolommapping/coercion niet te dupliceren — precies de reden voor deze herbouw. Alleen test-only
 * operaties zonder 1-op-1 productie-equivalent (reset, expliciete test-keys, parent-links, write-historie
 * voor [fieldValueCount]) zijn bespoke SQL.
 */
class TrackerTestState(
    postgres: PostgreSQLContainer<*>,
    val projectKey: String = "SP",
    private val schema: String = "public",
) {
    private val dataSource = HikariDataSource().apply {
        driverClassName = "org.postgresql.Driver"
        jdbcUrl = postgres.jdbcUrl
        username = postgres.username
        password = postgres.password
        maximumPoolSize = 2
        poolName = "e2e-tracker-test-state"
    }
    private val jdbc = JdbcTemplate(dataSource)
    private val secrets = FactorySecrets(
        youTrackBaseUrl = "unused",
        youTrackToken = "unused",
        youTrackProjects = emptyList(),
        githubToken = "unused",
        factoryDatabaseUrl = postgres.jdbcUrl,
        factoryDatabaseSchema = schema,
        kubeconfig = null,
        aiCredentialsDir = null,
        aiOauthToken = null,
        loadedFrom = "e2e-test",
        trackerBackend = "postgres",
        trackerAttachmentsDir = Files.createTempDirectory("e2e-tracker-attachments").toString(),
    )
    private val client = PostgresTrackerClient(jdbc, secrets, JdbcProcessedCommentStore(jdbc, secrets))

    /**
     * Wist alle issues/comments/attachments/write-historie en (idempotent, éénmalig per JVM) de
     * trigger die de write-historie bijhoudt. Geen aparte container per test — sneller — maar wel
     * volledige isolatie tussen tests.
     */
    @Synchronized
    fun reset() {
        ensureHistoryTrigger()
        jdbc.execute(
            "TRUNCATE $schema.issues, $schema.issue_comments, $schema.issue_attachments, " +
                "$schema.test_issue_field_history CASCADE",
        )
        jdbc.update("DELETE FROM $schema.project_key_sequences")
    }

    fun issue(key: String): TrackerIssue? = runCatching { client.getIssue(key) }.getOrNull()

    fun allIssues(): List<TrackerIssue> =
        jdbc.query("SELECT issue_key FROM $schema.issues ORDER BY id") { rs, _ -> rs.getString("issue_key") }
            .mapNotNull { issue(it) }

    fun childrenOf(parentKey: String): List<TrackerIssue> = client.subtasksOf(parentKey)

    fun parentKeyOf(childKey: String): String? = client.parentStoryKey(childKey)

    /** Test-only: forceert een parent-child-koppeling zonder een volle `createSubtask`-aanroep. */
    fun linkParent(parentKey: String, childKey: String) {
        jdbc.update("UPDATE $schema.issues SET parent_key = ? WHERE issue_key = ?", parentKey, childKey)
    }

    /** Test-only variant van `createStory`: mag (anders dan productie) een expliciete key meekrijgen. */
    fun createIssue(summary: String, description: String? = null, key: String? = null): TrackerIssue {
        val resolvedKey = key ?: nextKey()
        if (key != null) ensureCounterPast(key)
        jdbc.update(
            "INSERT INTO $schema.issues (issue_key, project_key, summary, description, type) " +
                "VALUES (?, ?, ?, ?, 'User Story')",
            resolvedKey, projectKey, summary, description,
        )
        return issue(resolvedKey)!!
    }

    fun addComment(issueKey: String, text: String, authorLogin: String = "robbert", authorFullName: String = "Robbert") {
        client.postComment(issueKey, text)
    }

    /** Zet een enum-achtig veld via de mens-vriendelijke YouTrack-veldnaam (test-gemak, zoals vroeger). */
    fun setEnumField(issueKey: String, fieldName: String, value: String) {
        client.updateIssueFields(issueKey, TrackerFieldUpdate.of(fieldFor(fieldName) to value))
    }

    fun setRawField(issueKey: String, fieldName: String, value: JsonNode?) {
        val field = fieldFor(fieldName)
        val raw: Any? = when {
            value == null || value.isNull -> null
            value.isInt -> value.asInt()
            value.isLong -> value.asLong()
            value.isBoolean -> value.asBoolean()
            else -> value.asText()
        }
        client.updateIssueFields(issueKey, TrackerFieldUpdate.of(field to raw))
    }

    fun setTextField(issueKey: String, fieldName: String, value: String) {
        client.updateIssueFields(issueKey, TrackerFieldUpdate.of(fieldFor(fieldName) to value))
    }

    /** Hoe vaak [field] op [issueKey] de waarde [value] geschreven kreeg — zie [ensureHistoryTrigger]. */
    fun fieldValueCount(issueKey: String, field: String, value: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM $schema.test_issue_field_history " +
                "WHERE issue_key = ? AND field_name = ? AND field_value = ?",
            Int::class.java,
            issueKey, field, value,
        ) ?: 0

    private fun fieldFor(fieldName: String): TrackerField = when (fieldName) {
        "Repo" -> TrackerField.REPO
        "AI-supplier" -> TrackerField.AI_SUPPLIER
        "Auto-approve" -> TrackerField.AUTO_APPROVE
        "Story Phase" -> TrackerField.STORY_PHASE
        "Subtask Phase" -> TrackerField.SUBTASK_PHASE
        "Subtask Type" -> TrackerField.SUBTASK_TYPE
        "AI Model" -> TrackerField.AI_MODEL
        "AI Reasoning Effort" -> TrackerField.AI_REASONING_EFFORT
        "AI Max Developer Loopbacks" -> TrackerField.AI_MAX_DEVELOPER_LOOPBACKS
        "AI Token Budget" -> TrackerField.AI_TOKEN_BUDGET
        "AI Tokens Used" -> TrackerField.AI_TOKENS_USED
        "Paused" -> TrackerField.PAUSED
        "Silent" -> TrackerField.SILENT
        "Error" -> TrackerField.ERROR
        else -> error("Onbekend testveld: '$fieldName' (geen TrackerField-mapping in TrackerTestState.fieldFor)")
    }

    private fun nextKey(): String {
        jdbc.update(
            "INSERT INTO $schema.project_key_sequences (project_key, next_number) VALUES (?, 1) " +
                "ON CONFLICT (project_key) DO NOTHING",
            projectKey,
        )
        val used = jdbc.queryForObject(
            "UPDATE $schema.project_key_sequences SET next_number = next_number + 1 " +
                "WHERE project_key = ? RETURNING next_number - 1",
            Int::class.java,
            projectKey,
        )!!
        return "$projectKey-$used"
    }

    /** Zorgt dat een later auto-gegenereerde key nooit botst met een test-opgegeven expliciete [key]. */
    private fun ensureCounterPast(key: String) {
        val n = key.substringAfterLast('-').toIntOrNull() ?: return
        jdbc.update(
            "INSERT INTO $schema.project_key_sequences (project_key, next_number) VALUES (?, ?) " +
                "ON CONFLICT (project_key) DO UPDATE SET next_number = " +
                "GREATEST($schema.project_key_sequences.next_number, EXCLUDED.next_number)",
            projectKey, n + 1,
        )
    }

    /**
     * Idempotente, éénmalige (JVM-brede) DDL-opzet van de write-historie voor [fieldValueCount]: een
     * trigger die elke niet-lege `story_phase`/`subtask_phase`-overgang op `issues` wegschrijft — ook
     * die van de ECHTE orchestrator-code (niet alleen test-setup), want de database zelf observeert.
     * Nodig omdat auto-approve-ketens een fase soms binnen één poll-venster laten voorbijflitsen; puur
     * op de huidige waarde pollen zou die overgang missen (zie `AwaitDsl.awaitPhaseReached`).
     *
     * Draait pas de EERSTE keer dat [reset] wordt aangeroepen (dus ná Spring/Flyway, die de
     * `issues`-tabel pas bij context-opstart aanmaken) — nooit bij class-load-tijd. Draait ook maar
     * ÉÉN keer per JVM (niet elke test): een `DROP TRIGGER`/`CREATE TRIGGER` op elke test zou de
     * trigger héél even weghalen terwijl de orchestrator-poller in dezelfde gedeelde JVM continu tegen
     * dezelfde Postgres draait.
     */
    private fun ensureHistoryTrigger() {
        if (!historyTriggerReady.compareAndSet(false, true)) return
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS $schema.test_issue_field_history (
              id BIGSERIAL PRIMARY KEY,
              issue_key TEXT NOT NULL,
              field_name TEXT NOT NULL,
              field_value TEXT NOT NULL,
              recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_test_issue_field_history_lookup " +
                "ON $schema.test_issue_field_history(issue_key, field_name, field_value)",
        )
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION $schema.test_record_issue_field_history() RETURNS trigger AS $$
            BEGIN
              IF NEW.story_phase IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.story_phase IS DISTINCT FROM OLD.story_phase) THEN
                INSERT INTO $schema.test_issue_field_history(issue_key, field_name, field_value)
                VALUES (NEW.issue_key, 'Story Phase', NEW.story_phase);
              END IF;
              IF NEW.subtask_phase IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.subtask_phase IS DISTINCT FROM OLD.subtask_phase) THEN
                INSERT INTO $schema.test_issue_field_history(issue_key, field_name, field_value)
                VALUES (NEW.issue_key, 'Subtask Phase', NEW.subtask_phase);
              END IF;
              RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute("DROP TRIGGER IF EXISTS test_issue_field_history_trg ON $schema.issues")
        jdbc.execute(
            "CREATE TRIGGER test_issue_field_history_trg " +
                "AFTER INSERT OR UPDATE OF story_phase, subtask_phase ON $schema.issues " +
                "FOR EACH ROW EXECUTE FUNCTION $schema.test_record_issue_field_history()",
        )
    }

    private companion object {
        /** JVM-breed: de trigger-DDL draait maar één keer, ongeacht hoeveel testklassen/-methodes er zijn. */
        val historyTriggerReady = AtomicBoolean(false)
    }
}
