package nl.vdzon.softwarefactory.cli

import nl.vdzon.softwarefactory.config.PostgresConnectionSettings
import nl.vdzon.softwarefactory.config.SecretsEnvLoader
import nl.vdzon.softwarefactory.orchestrator.JdbcSystemStateRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.OffsetDateTime
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: usage()
    val repository = systemStateRepository()

    when (command) {
        "resume" -> {
            repository.resumeCredits()
            println("AI credits pause cleared.")
        }
        "pause" -> {
            val until = parseUntil(args.drop(1))
            repository.pauseCredits(until, "manual pause via factory credits CLI")
            println("AI credits paused until $until.")
        }
        else -> usage()
    }
}

private fun parseUntil(args: List<String>): OffsetDateTime {
    val untilIndex = args.indexOf("--until")
    if (untilIndex < 0 || untilIndex + 1 >= args.size) {
        usage()
    }
    return runCatching { OffsetDateTime.parse(args[untilIndex + 1]) }
        .getOrElse { fail("Invalid --until value, expected ISO offset date-time like 2026-05-23T20:00:00Z.") }
}

private fun systemStateRepository(): JdbcSystemStateRepository {
    val secrets = SecretsEnvLoader().load()
    val dataSource = DriverManagerDataSource().apply {
        val settings = PostgresConnectionSettings.from(secrets.factoryDatabaseUrl)
        setDriverClassName("org.postgresql.Driver")
        url = settings.jdbcUrl
        settings.username?.let { username = it }
        settings.password?.let { password = it }
    }
    return JdbcSystemStateRepository(JdbcTemplate(dataSource), secrets)
}

private fun usage(): Nothing {
    System.err.println(
        """
        Usage:
          ./factory credits resume
          ./factory credits pause --until 2026-05-23T20:00:00Z
        """.trimIndent(),
    )
    exitProcess(2)
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}
