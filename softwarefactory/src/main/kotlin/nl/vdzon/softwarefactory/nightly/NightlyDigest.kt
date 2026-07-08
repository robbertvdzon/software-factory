package nl.vdzon.softwarefactory.nightly

import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale

/** Verwijzing naar een afgeronde nachtelijke story waarvan we de wijzigingen willen samenvatten. */
data class NightlyChangeRef(val storyKey: String, val project: String, val title: String)

/** Eén inhoudelijke regel onder een job (door de AI geschreven): label (Wat/Security/…) + tekst. */
data class NightlySection(val label: String, val text: String)

/**
 * Per-job verrijking voor de digest: klikbare links + een AI-samenvatting (sections) van de
 * wijzigingen. Wordt door de [NightlyGateway] geleverd; leeg wanneer er geen AI/links beschikbaar zijn.
 */
data class NightlyJobChanges(
    val storyLink: String? = null,
    /** Link naar de wijziging (merge-commit bij voorkeur, anders de PR). */
    val changeUrl: String? = null,
    val sections: List<NightlySection> = emptyList(),
)

/** Eén regel in de digest: een gedraaide (of nog lopende) nachtelijke job met kosten/tijd/uitkomst. */
data class NightlyDigestJob(
    val project: String,
    val jobName: String,
    val title: String,
    val status: String,
    val storyKey: String?,
    val storyLink: String?,
    /** Link naar de wijziging (merge-commit bij voorkeur, anders de PR). */
    val changeUrl: String?,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val costUsd: Double,
    /** AI-samenvatting van wat er veranderde; leeg => alleen de feitelijke kopregel. */
    val sections: List<NightlySection> = emptyList(),
    /** Korte toelichting bij een mislukte job (fouttekst), anders null. */
    val note: String? = null,
)

/**
 * Bouwt de platte-tekst-digest (Telegram + UI) van een nachtelijke run, gegroepeerd per project. Puur
 * en deterministisch: krijgt alle gegevens mee (incl. `asOf` voor nog-lopende duur) zodat er geen klok
 * nodig is. Per job: een feitelijke kopregel (story, titel, status, duur, kosten), klikbare links naar
 * de wijziging en het dashboard, en — wanneer beschikbaar — een AI-samenvatting van wát er veranderde.
 *
 * Telegram stuurt platte tekst en linkt URL's vanzelf, dus we renderen volledige URL's (geen Markdown).
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
            projectJobs.sortedBy { it.jobName }.forEachIndexed { index, job ->
                if (index > 0) lines += ""
                lines += headerLine(job, asOf)
                linkLine(job)?.let { lines += it }
                job.sections.forEach { section ->
                    lines += "${sectionIcon(section.label)} ${section.label}: ${section.text}"
                }
                job.note?.takeIf { it.isNotBlank() }?.let { lines += "⚠️ $it" }
            }
        }

        val total = jobs.size
        val done = jobs.count { it.status == NightlyJobStatus.DONE }
        val totalCost = jobs.sumOf { it.costUsd }
        val lastEnded = jobs.mapNotNull { it.endedAt }.maxOrNull()
        val totalDuration = formatDuration(runStartedAt, lastEnded ?: asOf.takeIf { runStartedAt != null })
        lines += ""
        lines += "——————"
        lines += "Σ Totaal — $total job(s) · $done/$total geslaagd · $totalDuration · ${formatCost(totalCost)}"
        return lines.joinToString("\n")
    }

    /** De feitelijke kopregel: `✅ SF-428 · Titel — klaar · 42m · $1.20`. */
    private fun headerLine(job: NightlyDigestJob, asOf: OffsetDateTime): String {
        val duration = formatDuration(job.startedAt, job.endedAt ?: asOf.takeIf { job.startedAt != null })
        val cost = formatCost(job.costUsd)
        val keyPart = job.storyKey?.let { "$it · " } ?: ""
        return "${icon(job.status)} $keyPart${job.title} — ${statusWord(job.status)} · $duration · $cost"
    }

    /** `🔗 <change-url>  ·  <story-link>`, of null wanneer er geen enkele link is. */
    private fun linkLine(job: NightlyDigestJob): String? {
        val urls = listOfNotNull(job.changeUrl, job.storyLink).filter { it.isNotBlank() }
        return if (urls.isEmpty()) null else "🔗 " + urls.joinToString("  ·  ")
    }

    private fun icon(status: String): String = when (status) {
        NightlyJobStatus.DONE -> "✅"
        NightlyJobStatus.FAILED -> "❌"
        NightlyJobStatus.RUNNING -> "⏳"
        else -> "•"
    }

    private fun statusWord(status: String): String = when (status) {
        NightlyJobStatus.DONE -> "klaar"
        NightlyJobStatus.FAILED -> "mislukt"
        NightlyJobStatus.RUNNING -> "loopt nog"
        NightlyJobStatus.PENDING -> "wacht"
        else -> status
    }

    private fun sectionIcon(label: String): String = when (label.trim().lowercase()) {
        "wat" -> "📝"
        "security", "beveiliging" -> "🔒"
        "kwaliteit", "quality" -> "✨"
        "docs", "documentatie" -> "📚"
        else -> "•"
    }

    private fun formatCost(usd: Double): String = "$" + String.format(Locale.US, "%.2f", usd)

    /** Duur als `1u 23m` / `12m 05s` / `—` wanneer (nog) geen begin bekend is. */
    private fun formatDuration(from: OffsetDateTime?, to: OffsetDateTime?): String {
        if (from == null || to == null) return "—"
        val seconds = Duration.between(from, to).seconds.coerceAtLeast(0)
        val h = seconds / SECONDS_PER_HOUR
        val m = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val s = seconds % SECONDS_PER_MINUTE
        return when {
            h > 0 -> "${h}u ${m}m"
            m > 0 -> "${m}m ${s.toString().padStart(2, '0')}s"
            else -> "${s}s"
        }
    }

    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_MINUTE = 60L
}
