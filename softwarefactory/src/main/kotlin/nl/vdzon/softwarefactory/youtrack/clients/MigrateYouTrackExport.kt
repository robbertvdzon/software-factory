package nl.vdzon.softwarefactory.youtrack.clients

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.config.PostgresConnectionSettings
import nl.vdzon.softwarefactory.core.TrackerIssue
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * Eenmalige, hand-gedraaide migratie: zet een YouTrack REST-export (JSON-array van issues, zoals
 * `robberts-infrastructure/backups/youtrack-<timestamp>/issues-export.json`) om naar rijen in de nieuwe
 * `issues`/`issue_comments`-tabellen (V15__tracker_issues.sql), zodat `PostgresTrackerClient` de
 * historische stories/subtaken kan lezen.
 *
 * Hergebruikt [YouTrackIssueMapper.mapIssue] LETTERLIJK i.p.v. een tweede JSON-parser te schrijven —
 * garandeert dezelfde veld-uitlees-regels als ooit tegen echte YouTrack-data getest. Staat daarom
 * bewust in dit package (niet een los `tools`-package): Spring Modulith beschouwt `youtrack.clients`
 * als intern aan de youtrack-module, en het interpreteren van YouTrack's export-vorm hoort ook
 * inhoudelijk bij die module. Gebruikt [ConfigApi] (de publieke config-module-API, zie daar) i.p.v.
 * rechtstreeks `SecretsEnvLoader` om dezelfde reden.
 *
 * Niet als Flyway-migratie (geneste JSON + comments is niks voor SQL-migratiebestanden) en niet
 * Spring-managed — gewoon een `main()` die je één keer met de hand draait:
 *
 *   mvn -pl softwarefactory -am compile exec:java \
 *     -Dexec.mainClass=nl.vdzon.softwarefactory.youtrack.clients.MigrateYouTrackExportKt \
 *     -Dexec.args="/pad/naar/issues-export.json"
 *
 * Idempotent (ON CONFLICT DO NOTHING op issue_key) — veilig opnieuw te draaien tijdens testen.
 *
 * Attachments zitten NIET in de JSON-export (alleen metadata, geen bytes) — historische
 * screenshots worden bewust niet gemigreerd (tijdelijke debug-artefacten, geen audit-kritische
 * data); nieuwe screenshots gaan vanaf de cutover gewoon naar `issue_attachments`.
 */
fun main(args: Array<String>) {
    val exportPath = args.firstOrNull()
        ?: run {
            System.err.println("Gebruik: MigrateYouTrackExport <pad-naar-issues-export.json>")
            exitProcess(1)
        }

    val secrets = ConfigApi.default().loadSecrets()
    val settings = PostgresConnectionSettings.from(secrets.factoryDatabaseUrl)
    val dataSource = HikariDataSource().apply {
        driverClassName = "org.postgresql.Driver"
        jdbcUrl = settings.jdbcUrl
        settings.username?.let { username = it }
        settings.password?.let { password = it }
        maximumPoolSize = 2
    }
    val jdbc = JdbcTemplate(dataSource)
    val schema = secrets.factoryDatabaseSchema

    try {
        // Zelfde schema-opbouw als de Spring Boot-app (DatabaseConfiguration): zorgt dat ook een
        // verse/scratch-database eerst alle migraties (incl. V15__tracker_issues.sql) krijgt,
        // zodat dit script niet van een al-gestarte app afhankelijk is.
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .placeholders(mapOf("schema" to schema))
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val objectMapper = jacksonObjectMapper()
        val root = objectMapper.readTree(Path.of(exportPath).readText())
        val issues = root.map { YouTrackIssueMapper.mapIssue(it) }
        println("[migrate] ${issues.size} issues gelezen uit $exportPath")

        // Pass 1: alle issues zonder parent_key inserten. ON CONFLICT DO NOTHING maakt dit
        // idempotent — een tweede run raakt bestaande rijen niet aan.
        var inserted = 0
        issues.forEach { issue ->
            val rows = jdbc.update(
                """
                INSERT INTO $schema.issues
                    (issue_key, project_key, summary, description, status,
                     repo, ai_supplier, auto_approve, ai_phase, ai_level, ai_max_developer_loopbacks,
                     ai_token_budget, ai_tokens_used, agent_started_at, paused, silent, error,
                     type, subtask_type, ai_model, ai_reasoning_effort, story_phase, subtask_phase)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (issue_key) DO NOTHING
                """.trimIndent(),
                issue.key,
                issue.projectKey,
                issue.summary,
                issue.description,
                issue.status,
                issue.fields.repo,
                issue.fields.aiSupplier,
                issue.fields.autoApprove,
                issue.fields.aiPhase,
                issue.fields.aiLevel,
                issue.fields.aiMaxDeveloperLoopbacks,
                issue.fields.aiTokenBudget,
                issue.fields.aiTokensUsed,
                issue.fields.agentStartedAt,
                issue.fields.paused,
                issue.fields.silent,
                issue.fields.error,
                issue.fields.type,
                issue.fields.subtaskType,
                issue.fields.aiModel,
                issue.fields.aiReasoningEffort,
                issue.fields.storyPhase,
                issue.fields.subtaskPhase,
            )
            inserted += rows
        }
        println("[migrate] $inserted nieuwe issues geïnsert (rest bestond al — idempotent)")

        // Pass 2: parent_key bijwerken — sidesteps de noodzaak om de JSON-array topologisch te
        // sorteren (parent vóór subtaak); dit werkt ongeacht de volgorde in de export.
        var parentsLinked = 0
        issues.forEach { issue ->
            issue.parentKey?.let { parentKey ->
                parentsLinked += jdbc.update(
                    "UPDATE $schema.issues SET parent_key = ? WHERE issue_key = ? AND parent_key IS NULL",
                    parentKey,
                    issue.key,
                )
            }
        }
        println("[migrate] $parentsLinked parent-links bijgewerkt")

        // Comments — alleen voor issues die in DEZE run daadwerkelijk zijn geïnsert (idempotent:
        // een tweede run zou anders dubbele comments toevoegen aan al gemigreerde issues).
        var commentsInserted = 0
        issues.forEach { issue ->
            val alreadyHadComments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM $schema.issue_comments WHERE issue_key = ?",
                Int::class.java,
                issue.key,
            ) ?: 0
            if (alreadyHadComments > 0) {
                return@forEach
            }
            issue.comments.forEach { comment ->
                jdbc.update(
                    """
                    INSERT INTO $schema.issue_comments
                        (issue_key, external_comment_id, author_account_id, author_display_name, body, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    issue.key,
                    comment.id,
                    comment.authorAccountId,
                    comment.authorDisplayName,
                    comment.body,
                    comment.created,
                )
                commentsInserted++
            }
        }
        println("[migrate] $commentsInserted comments geïnsert")

        // project_key_sequences seeden vanuit MAX(issue-nummer) per project, zodat nieuw
        // aangemaakte issues nooit botsen met de gemigreerde keys.
        val projectKeys = issues.map { it.projectKey }.toSet()
        projectKeys.forEach { projectKey ->
            val maxNumber = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(NULLIF(regexp_replace(issue_key, '^.*-', ''), '')::int), 0)
                FROM $schema.issues
                WHERE project_key = ?
                """.trimIndent(),
                Int::class.java,
                projectKey,
            ) ?: 0
            jdbc.update(
                """
                INSERT INTO $schema.project_key_sequences (project_key, next_number)
                VALUES (?, ?)
                ON CONFLICT (project_key) DO UPDATE SET next_number = GREATEST($schema.project_key_sequences.next_number, EXCLUDED.next_number)
                """.trimIndent(),
                projectKey,
                maxNumber + 1,
            )
        }
        println("[migrate] project_key_sequences geseed voor: ${projectKeys.joinToString(", ")}")

        // Validatie.
        val total = jdbc.queryForObject("SELECT COUNT(*) FROM $schema.issues", Int::class.java) ?: 0
        println("[migrate] klaar. Totaal aantal issues in $schema.issues: $total (export bevatte ${issues.size})")
        issues.take(3).forEach { validateRoundTrip(jdbc, schema, it) }
    } finally {
        dataSource.close()
    }
}

private fun validateRoundTrip(jdbc: JdbcTemplate, schema: String, expected: TrackerIssue) {
    val actualSummary = jdbc.queryForObject(
        "SELECT summary FROM $schema.issues WHERE issue_key = ?",
        String::class.java,
        expected.key,
    )
    val ok = actualSummary == expected.summary
    println("[migrate] steekproef ${expected.key}: ${if (ok) "OK" else "MISMATCH (verwacht '${expected.summary}', kreeg '$actualSummary')"}")
}
