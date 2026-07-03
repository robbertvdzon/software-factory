package nl.vdzon.softwarefactory.web.views.shared

import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.web.models.StoryDetailPageData

/**
 * Chrome voor de story-detailschermen (detail, briefing, screenshots): layout met breadcrumb,
 * het statuspaneel en de terug-navigatie. Gedeeld zodat die drie pagina's identiek ogen.
 */
internal class DetailChrome(private val layout: HtmlLayout) {

    fun detailLayout(
        page: StoryDetailPageData,
        title: String,
        autoRefreshSeconds: Int? = null,
        content: () -> String,
    ): String {
        val issueTitle = page.issue?.summary ?: "Issue niet geladen"
        return layout.layout(
            active = "stories",
            title = issueTitle,
            subtitle = page.storyKey,
            autoRefreshSeconds = autoRefreshSeconds,
            eyebrow = breadcrumb(page),
            browserTitle = "$title - ${page.storyKey}",
        ) { content() }
    }

    private fun breadcrumb(page: StoryDetailPageData): String {
        val parent = page.parentKey
        return if (parent != null) {
            """<a href="/stories">Stories</a> &nbsp;&middot;&nbsp; <a href="/stories/${parent.path()}">${parent.e()}</a> &nbsp;&middot;&nbsp; ${page.storyKey.e()}"""
        } else {
            """<a href="/stories">Stories</a> &nbsp;&middot;&nbsp; ${page.storyKey.e()}"""
        }
    }

    fun statusPanel(page: StoryDetailPageData): String {
        val issue = page.issue
        val phase = issue?.displayPhase()
        // Voor een STORY tonen we de afgeleide "echte" status (incl. subtaken en merged-vlag); een
        // subtask-detail houdt z'n eigen fase-gebaseerde weergave.
        val storyStatus = issue
            ?.takeIf { it.issueType == IssueType.STORY }
            ?.let { StoryStatusPresenter.realStatus(it, page.subtasks, merged = page.run?.finalStatus.equals("merged", ignoreCase = true)) }
        val statusText = when {
            issue == null -> "Niet geladen"
            storyStatus != null -> storyStatus.label
            !issue.fields.error.isNullOrBlank() -> "Vastgelopen"
            issue.fields.paused -> "Gepauzeerd"
            phase.isNullOrBlank() -> "Klaar voor pickup"
            else -> phase
        }
        val kind = when {
            storyStatus != null -> storyStatus.kind
            issue?.fields?.error?.isNotBlank() == true -> "bad"
            issue?.fields?.paused == true -> "warn"
            phase == "planning-approved" || phase == "tested-successfully" -> "ok"
            else -> "info"
        }
        val dot = when (kind) {
            "bad" -> "bad"
            "warn", "neutral" -> "wait"
            "ok" -> ""
            else -> "run"
        }
        val desc = issue?.fields?.error?.takeIf { it.isNotBlank() }?.e() ?: phaseDescription(issue).e()
        return """
        <section class="status">
          <span class="dot $dot"></span>
          <b>${statusText.e()}</b>
          <span class="muted">&mdash; $desc</span>
          ${issue?.let { typeTag(it) } ?: ""}
        </section>
        """.trimIndent()
    }

    fun backLink(storyKey: String): String =
        """<p class="backbar"><a class="button back" href="/stories/${storyKey.path()}">&larr; Terug naar story</a></p>"""

    /** Zichtbare terug-knop op een subpagina (subtask-detail terug naar de parent-story). */
    fun backButton(page: StoryDetailPageData): String {
        val parent = page.parentKey ?: return ""
        return """<p class="backbar"><a class="button back" href="/stories/${parent.path()}">&larr; Terug naar ${parent.e()}</a></p>"""
    }
}
