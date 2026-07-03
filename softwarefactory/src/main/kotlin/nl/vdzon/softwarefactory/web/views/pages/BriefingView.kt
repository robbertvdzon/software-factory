package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.web.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.web.models.UiBriefingAgentRun
import nl.vdzon.softwarefactory.web.models.UiBriefingItem
import nl.vdzon.softwarefactory.web.models.UiBriefingUserComment
import nl.vdzon.softwarefactory.web.views.shared.DetailChrome
import nl.vdzon.softwarefactory.web.views.shared.Formatters
import nl.vdzon.softwarefactory.web.views.shared.agentRunIterationLabels
import nl.vdzon.softwarefactory.web.views.shared.alerts
import nl.vdzon.softwarefactory.web.views.shared.badge
import nl.vdzon.softwarefactory.web.views.shared.e
import nl.vdzon.softwarefactory.web.views.shared.empty
import nl.vdzon.softwarefactory.web.views.shared.outcomePresentation
import nl.vdzon.softwarefactory.web.views.shared.section
import nl.vdzon.softwarefactory.web.views.shared.sortedByNewestRun
import java.time.Duration

/** Briefing: agent-run-samenvattingen en gebruikers-antwoorden, chronologisch (nieuwste eerst). */
internal class BriefingView(
    private val chrome: DetailChrome,
    private val fmt: Formatters,
) {

    fun render(page: StoryDetailPageData): String =
        chrome.detailLayout(page, "Briefing", autoRefreshSeconds = 5) {
            // Story-briefing toont story + alle subtaak-runs samen; subtaak-briefing alleen die subtaak.
            val isSubtask = page.parentKey != null
            val source = if (isSubtask) page.agentRuns else page.allAgentRuns
            val agentRuns = source.sortedByNewestRun()
            val runIterations = agentRunIterationLabels(agentRuns)
            // Bron-label per kaart: story-key of subtaak-key (+ titel indien bekend).
            val subtaskTitles = page.subtasks.associate { it.key to it.summary }

            // Build briefing items from agent-runs and user comments, sorted chronologically
            val briefingItems = mutableListOf<UiBriefingItem>()
            agentRuns.forEach { run ->
                briefingItems.add(UiBriefingAgentRun(run))
            }
            // Add user comments (non-agent comments) from the issue
            page.issue?.comments?.forEach { comment ->
                if (!comment.isAgentComment && comment.created != null) {
                    briefingItems.add(UiBriefingUserComment(
                        id = comment.id,
                        authorName = comment.authorDisplayName,
                        body = comment.body,
                        created = comment.created,
                    ))
                }
            }
            // Add user comments from subtasks if viewing story-briefing
            if (!isSubtask) {
                page.subtasks.forEach { subtask ->
                    subtask.comments.forEach { comment ->
                        if (!comment.isAgentComment && comment.created != null) {
                            briefingItems.add(UiBriefingUserComment(
                                id = comment.id,
                                authorName = comment.authorDisplayName,
                                body = comment.body,
                                created = comment.created,
                            ))
                        }
                    }
                }
            }

            val sortedItems = briefingItems.sortedByDescending { it.timestamp }

            alerts(page.errors) +
                chrome.statusPanel(page) +
                chrome.backLink(page.storyKey) +
                section("Agent-run samenvattingen en gebruikers-antwoorden") {
                    if (sortedItems.isEmpty()) {
                        empty("Nog geen agent-runs of gebruikers-antwoorden gevonden.")
                    } else {
                        sortedItems.joinToString("") { item ->
                            when (item) {
                                is UiBriefingAgentRun -> {
                                    val run = item.agentRun
                                    val outcome = outcomePresentation(run)
                                    val iteration = runIterations[run.id] ?: "1/1"
                                    """
                                    <article class="brief-card agent-run">
                                      <div class="brief-head">
                                        <span class="icon-tile">${run.role.take(3).uppercase()}</span>
                                        <div>
                                          ${briefingSourceBadge(run, page.storyKey, subtaskTitles)}
                                          <strong>${run.role.e()} ($iteration)</strong> ${badge(outcome.label, outcome.kind)}<br>
                                          <span class="muted">Gestart ${fmt.timestamp(run.startedAt)} - ${fmt.relative(run.startedAt)}${run.endedAt?.let { " · klaar ${fmt.timestamp(it)} (${fmt.durationMmSs(Duration.between(run.startedAt, it).toMillis())})" } ?: ""}</span>
                                        </div>
                                      </div>
                                      <div class="brief-result">
                                        <span>Resultaat</span>
                                        <strong>${outcome.label.e()}</strong>
                                        ${outcome.code?.let { """<code>${it.e()}</code>""" } ?: ""}
                                      </div>
                                      <pre>${run.summaryText?.takeIf { it.isNotBlank() }?.e() ?: "Geen samenvatting opgeslagen."}</pre>
                                    </article>
                                    """.trimIndent()
                                }
                                is UiBriefingUserComment -> {
                                    """
                                    <article class="brief-card user-comment">
                                      <div class="brief-head">
                                        <span class="icon-tile">USR</span>
                                        <div>
                                          <span class="brief-source user">Gebruiker</span><br>
                                          <strong>${item.authorName?.e() ?: "Anoniem".e()}</strong><br>
                                          <span class="muted">${fmt.timestamp(item.created)} - ${fmt.relative(item.created)}</span>
                                        </div>
                                      </div>
                                      <pre>${item.body.e()}</pre>
                                    </article>
                                    """.trimIndent()
                                }
                            }
                        }
                    }
                }
        }

    /** Label dat aangeeft of een briefing-kaart bij de story of een subtaak hoort. */
    private fun briefingSourceBadge(run: UiAgentRun, storyKey: String, subtaskTitles: Map<String, String>): String {
        val subtaskKey = run.subtaskKey
        return if (subtaskKey == null) {
            """<span class="brief-source story">Story &middot; ${storyKey.e()}</span><br>"""
        } else {
            val title = subtaskTitles[subtaskKey]?.let { " &middot; ${it.e()}" } ?: ""
            """<span class="brief-source subtask">Subtaak &middot; ${subtaskKey.e()}$title</span><br>"""
        }
    }
}
