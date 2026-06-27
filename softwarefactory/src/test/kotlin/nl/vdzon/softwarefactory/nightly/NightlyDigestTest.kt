package nl.vdzon.softwarefactory.nightly

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class NightlyDigestTest {

    private val runDate = LocalDate.of(2026, 6, 27)
    private val start = OffsetDateTime.parse("2026-06-27T00:00:00Z")
    private val asOf = OffsetDateTime.parse("2026-06-27T05:00:00Z")

    @Test
    fun `groups jobs per project with duration cost outcome and link plus totals`() {
        val jobs = listOf(
            NightlyDigestJob(
                project = "alpha", jobName = "lint", title = "Lint sweep", status = NightlyJobStatus.DONE,
                storyKey = "SF-100", link = "https://dash/stories/SF-100",
                startedAt = OffsetDateTime.parse("2026-06-27T00:00:00Z"),
                endedAt = OffsetDateTime.parse("2026-06-27T00:42:00Z"), costUsd = 1.234,
            ),
            NightlyDigestJob(
                project = "beta", jobName = "deps", title = "Dependency bump", status = NightlyJobStatus.FAILED,
                storyKey = "SF-101", link = "https://dash/stories/SF-101",
                startedAt = OffsetDateTime.parse("2026-06-27T01:00:00Z"),
                endedAt = OffsetDateTime.parse("2026-06-27T01:05:30Z"), costUsd = 0.5,
            ),
        )
        val text = NightlyDigest.build(runDate, start, asOf, jobs)

        assertTrue(text.contains("alpha"), text)
        assertTrue(text.contains("beta"), text)
        assertTrue(text.contains("Lint sweep"))
        assertTrue(text.contains("(lint)"))
        assertTrue(text.contains("https://dash/stories/SF-100"))
        assertTrue(text.contains("$1.23"), text)        // job-kosten
        assertTrue(text.contains("42m"))                 // job-duur
        assertTrue(text.contains("$1.73"), text)         // totale kosten (1.234 + 0.5)
        assertTrue(text.contains("Totaal"))
    }

    @Test
    fun `empty run produces a short no-jobs digest`() {
        val text = NightlyDigest.build(runDate, start, asOf, emptyList())
        assertTrue(text.contains("Geen nachtelijke jobs"), text)
    }

    @Test
    fun `still-running job uses asOf for its duration instead of crashing on a null end`() {
        val jobs = listOf(
            NightlyDigestJob(
                project = "alpha", jobName = "slow", title = "Slow job", status = NightlyJobStatus.RUNNING,
                storyKey = "SF-200", link = null,
                startedAt = OffsetDateTime.parse("2026-06-27T04:00:00Z"), endedAt = null, costUsd = 0.0,
            ),
        )
        val text = NightlyDigest.build(runDate, start, asOf, jobs)
        assertTrue(text.contains("Slow job"))
        assertTrue(text.contains("1u"), text) // 04:00 → asOf 05:00 = 1u
    }
}
