package nl.vdzon.softwarefactory.web.views.shared

import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.TrackerIssue

/*
 * Kleine, stateless HTML-bouwstenen die door meerdere pagina's gebruikt worden.
 * Top-level internal functies: geen klasse nodig, want er is geen state of Clock.
 */

internal fun section(title: String, body: () -> String): String =
    """<section><h2 class="section-title">${title.e()}</h2>${body()}</section>"""

internal fun metricGrid(vararg metrics: Pair<String, String>): String =
    """<div class="metric-grid">${metrics.joinToString("") { """<div><span>${it.first.e()}</span><strong>${it.second.e()}</strong></div>""" }}</div>"""

internal fun alerts(errors: List<String>): String =
    errors.joinToString("") { """<p class="alert bad">${it.e()}</p>""" }

internal fun empty(message: String): String =
    """<div class="empty">${message.e()}</div>"""

internal fun badge(value: String, forcedKind: String? = null): String {
    val kind = forcedKind ?: when {
        value.contains("success", ignoreCase = true) || value.contains("merged", ignoreCase = true) || value.contains("pass", ignoreCase = true) -> "ok"
        value.contains("fail", ignoreCase = true) || value.contains("error", ignoreCase = true) || value.contains("vast", ignoreCase = true) -> "bad"
        value.contains("pause", ignoreCase = true) || value.contains("stuck", ignoreCase = true) -> "warn"
        else -> "info"
    }
    return """<span class="badge $kind">${value.e()}</span>"""
}

internal fun typeBadge(issue: TrackerIssue): String =
    when (issue.issueType) {
        IssueType.STORY -> badge("Story", "info")
        IssueType.SUBTASK -> badge("Subtask${issue.fields.subtaskType?.let { ": $it" } ?: ""}", "warn")
    }

internal fun typeTag(issue: TrackerIssue): String =
    when (issue.issueType) {
        IssueType.STORY -> """<span class="tag">Story</span>"""
        IssueType.SUBTASK -> """<span class="tag amber">Subtask${issue.fields.subtaskType?.let { ": ${it.e()}" } ?: ""}</span>"""
    }

/** v2: Story Phase (story) / Subtask Phase (subtask), met legacy AI Phase als fallback. */
internal fun TrackerIssue.displayPhase(): String? =
    fields.storyPhase?.takeIf { it.isNotBlank() }
        ?: fields.subtaskPhase?.takeIf { it.isNotBlank() }
        ?: fields.aiPhase?.takeIf { it.isNotBlank() }

internal fun phaseDescription(issue: TrackerIssue?): String =
    when {
        issue == null -> "De issue kon niet worden opgehaald."
        issue.fields.aiSupplier.isNullOrBlank() || issue.fields.aiSupplier == "none" -> "AI supplier staat leeg of op none."
        issue.displayPhase().isNullOrBlank() -> "Develop status met supplier ${issue.fields.aiSupplier}; klaar voor pickup."
        else -> "Huidige fase: ${issue.displayPhase()}."
    }
