package nl.vdzon.softwarefactory.nightly

import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

/** Eén regel in de digest: een gedraaide (of nog lopende) nachtelijke job met kosten/tijd/uitkomst. */
data class NightlyDigestJob(
    val project: String,
    val jobName: String,
    val title: String,
    val status: String,
    val storyKey: String?,
    val link: String?,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val costUsd: Double,
)

/**
 * Bouwt de platte-tekst-digest (Telegram + UI) van een nachtelijke run, gegroepeerd per project. Puur
 * en deterministisch: krijgt alle gegevens mee (incl. `asOf` voor nog-lopende duur) zodat er geen klok
 * nodig is. Per job: project+naam, duur, kosten ($), uitkomst en story-link; plus totale duur en kosten.
 */
object NightlyDigest {

    fun build(
        runDate: LocalDate,
        runStartedAt: OffsetDateTime?,
        asOf: OffsetDateTime,
        jobs: List<NightlyDigestJob>,
    ): String {
        val lines = mutableListOf<String>()
        lines += "🌙 Nightly digest — $runDate"

        if (jobs.isEmpty()) {
            lines += ""
            lines += "Geen nachtelijke jobs gedraaid."
            return lines.joinToString("\n")
        }

        jobs.groupBy { it.project }.toSortedMap().forEach { (project, projectJobs) ->
            lines += ""
            lines += "📦 $project"
            projectJobs.sortedBy { it.jobName }.forEach { job ->
                val duration = formatDuration(job.startedAt, job.endedAt ?: asOf.takeIf { job.startedAt != null })
                val cost = formatCost(job.costUsd)
                val parts = listOf(
                    "${icon(job.status)} ${job.title}",
                    "(${job.jobName})",
                    "· $duration",
                    "· $cost",
                )
                lines += "  ${parts.joinToString(" ")}"
                job.link?.let { lines += "    $it" }
            }
        }

        val totalCost = jobs.sumOf { it.costUsd }
        val lastEnded = jobs.mapNotNull { it.endedAt }.maxOrNull()
        val totalDuration = formatDuration(runStartedAt, lastEnded ?: asOf.takeIf { runStartedAt != null })
        lines += ""
        lines += "Σ Totaal: $totalDuration · ${formatCost(totalCost)}"
        return lines.joinToString("\n")
    }

    private fun icon(status: String): String = when (status) {
        NightlyJobStatus.DONE -> "✅"
        NightlyJobStatus.FAILED -> "❌"
        NightlyJobStatus.RUNNING -> "⏳"
        else -> "•"
    }

    private fun formatCost(usd: Double): String = "$" + String.format("%.2f", usd)

    /** Duur als `1u 23m` / `12m 05s` / `—` wanneer (nog) geen begin bekend is. */
    private fun formatDuration(from: OffsetDateTime?, to: OffsetDateTime?): String {
        if (from == null || to == null) return "—"
        val seconds = Duration.between(from, to).seconds.coerceAtLeast(0)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}u ${m}m"
            m > 0 -> "${m}m ${s.toString().padStart(2, '0')}s"
            else -> "${s}s"
        }
    }
}
