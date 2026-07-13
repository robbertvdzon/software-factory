package nl.vdzon.softwarefactory.nightly

import nl.vdzon.softwarefactory.nightly.models.*
import nl.vdzon.softwarefactory.nightly.types.*
import nl.vdzon.softwarefactory.nightly.services.*
import nl.vdzon.softwarefactory.nightly.repositories.*

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale

class NightlyDigestTest {

    private val runDate = LocalDate.of(2026, 6, 27)
    private val start = OffsetDateTime.parse("2026-06-27T00:00:00Z")
    private val asOf = OffsetDateTime.parse("2026-06-27T05:00:00Z")

    @Test
    fun `groups jobs per project with header links sections and totals`() {
        val jobs = listOf(
            NightlyDigestJob(
                project = "alpha", jobName = "lint", title = "Lint sweep", status = NightlyJobStatus.DONE,
                storyKey = "SF-100",
                storyLink = "https://dash/stories/SF-100",
                changeUrl = "https://github.com/o/r/commit/abc123",
                startedAt = OffsetDateTime.parse("2026-06-27T00:00:00Z"),
                endedAt = OffsetDateTime.parse("2026-06-27T00:42:00Z"), costUsd = 1.234,
                sections = listOf(
                    NightlySection("Wat", "Linter-config aangescherpt"),
                    NightlySection("Kwaliteit", "Ongebruikte imports verwijderd"),
                ),
            ),
            NightlyDigestJob(
                project = "beta", jobName = "deps", title = "Dependency bump", status = NightlyJobStatus.FAILED,
                storyKey = "SF-101", storyLink = "https://dash/stories/SF-101", changeUrl = null,
                startedAt = OffsetDateTime.parse("2026-06-27T01:00:00Z"),
                endedAt = OffsetDateTime.parse("2026-06-27T01:05:30Z"), costUsd = 0.5,
                note = "build faalde",
            ),
        )
        val text = NightlyDigest.build(runDate, start, asOf, jobs)

        assertTrue(text.contains("📦 alpha"), text)
        assertTrue(text.contains("📦 beta"), text)
        assertTrue(text.contains("SF-100 · Lint sweep — klaar"), text)  // feitelijke kopregel
        assertTrue(text.contains("https://github.com/o/r/commit/abc123"), text)
        assertTrue(text.contains("https://dash/stories/SF-100"), text)
        assertTrue(text.contains("📝 Wat: Linter-config aangescherpt"), text)  // AI-section
        assertTrue(text.contains("✨ Kwaliteit:"), text)
        assertTrue(text.contains("❌ SF-101 · Dependency bump — mislukt"), text)
        assertTrue(text.contains("⚠️ build faalde"), text)              // fout-toelichting
        assertTrue(text.contains("$1.23"), text)                        // job-kosten
        assertTrue(text.contains("42m"), text)                          // job-duur
        assertTrue(text.contains("1/2 geslaagd"), text)                 // 1 done van 2
        assertTrue(text.contains("$1.73"), text)                        // totale kosten (1.234 + 0.5)
    }

    @Test
    fun `cost formatting uses a dot decimal separator regardless of the default locale`() {
        val previousDefault = Locale.getDefault()
        try {
            // Een komma-locale zou zonder expliciete Locale.US "1,23" produceren i.p.v. "1.23".
            Locale.setDefault(Locale.GERMANY)
            val jobs = listOf(
                NightlyDigestJob(
                    project = "alpha", jobName = "lint", title = "Lint sweep", status = NightlyJobStatus.DONE,
                    storyKey = "SF-100", storyLink = null, changeUrl = null,
                    startedAt = start, endedAt = OffsetDateTime.parse("2026-06-27T00:42:00Z"), costUsd = 1.234,
                ),
            )
            val text = NightlyDigest.build(runDate, start, asOf, jobs)
            assertTrue(text.contains("$1.23"), text)
            assertTrue(!text.contains("1,23"), text)
        } finally {
            Locale.setDefault(previousDefault)
        }
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
                storyKey = "SF-200", storyLink = null, changeUrl = null,
                startedAt = OffsetDateTime.parse("2026-06-27T04:00:00Z"), endedAt = null, costUsd = 0.0,
            ),
        )
        val text = NightlyDigest.build(runDate, start, asOf, jobs)
        assertTrue(text.contains("Slow job"))
        assertTrue(text.contains("loopt nog"), text)
        assertTrue(text.contains("1u"), text) // 04:00 → asOf 05:00 = 1u
    }
}
