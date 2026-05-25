package nl.vdzon.softwarefactory.web.views

import nl.vdzon.softwarefactory.web.models.AgentsPageData
import nl.vdzon.softwarefactory.web.models.DashboardPageData
import nl.vdzon.softwarefactory.web.models.MergedPageData
import nl.vdzon.softwarefactory.web.models.SettingsPageData
import nl.vdzon.softwarefactory.web.models.StoriesPageData
import nl.vdzon.softwarefactory.web.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.web.models.UiStoryRun
import nl.vdzon.softwarefactory.youtrack.TrackerComment
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Component
class FactoryDashboardViews(
    private val clock: Clock,
) {
    fun login(error: Boolean = false, next: String = "/dashboard"): String =
        """
        <!doctype html>
        <html lang="nl">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Login - Software Factory</title>
          <link rel="stylesheet" href="/sf-ui.css">
        </head>
        <body>
          <main class="login-wrap">
            <section class="login-card">
              <div class="login-mark">SF</div>
              <h1>Software Factory</h1>
              <p class="muted">Login op het dashboard</p>
              ${if (error) """<p class="alert bad">Ongeldige gebruikersnaam of wachtwoord.</p>""" else ""}
              <form method="post" action="/login" class="login-form">
                <input type="hidden" name="next" value="${next.e()}">
                <label>Gebruikersnaam<input name="username" value="admin" autocomplete="username"></label>
                <label>Wachtwoord<input name="password" type="password" autocomplete="current-password"></label>
                <button class="button primary" type="submit">Inloggen</button>
              </form>
            </section>
          </main>
        </body>
        </html>
        """.trimIndent()

    fun dashboard(page: DashboardPageData): String =
        layout("dashboard", "Dashboard", "Overzicht van factory-runs en actieve stories") {
            alerts(page.errors) +
                section("Productie") {
                    panel(
                        metricGrid(
                            "Actieve stories" to page.issues.size.toString(),
                            "Lopende runs" to page.activeAgentRuns.size.toString(),
                            "Open story-runs" to page.activeRuns.size.toString(),
                            "Laatste run" to (page.recentRuns.firstOrNull()?.storyKey ?: "-"),
                        ),
                    )
                } +
                section("Stories in beheer van AI") {
                    issueTable(page.issues, page.activeRuns.associateBy { it.storyKey }, limit = 8)
                } +
                section("Recente runs") {
                    storyRunList(page.recentRuns)
                }
        }

    fun stories(page: StoriesPageData): String =
        layout("stories", "Stories", "Stories die de AI op dit moment behandelt") {
            alerts(page.errors) + issueTable(page.issues, page.runsByStory, limit = Int.MAX_VALUE)
        }

    fun storyDetail(page: StoryDetailPageData): String =
        detailLayout(page, "Story Detail") {
            statusPanel(page) +
                linksPanel(page) +
                commandPanel(page.storyKey) +
                budgetPanel(page.issue, page.run) +
                overviewPanel(page) +
                agentRunsPanel(page.agentRuns)
        }

    fun briefing(page: StoryDetailPageData): String =
        detailLayout(page, "Briefing") {
            val comments = page.issue?.comments.orEmpty()
                .filter { it.isAgentComment }
                .sortedByNewestComment()
            val commentIterations = commentIterationLabels(comments)
            val agentRuns = page.agentRuns.sortedByNewestRun()
            val runIterations = agentRunIterationLabels(agentRuns)
            alerts(page.errors) +
                backLink(page.storyKey) +
                section("Agent-comments") {
                    if (comments.isEmpty()) {
                        empty("Nog geen agent-comments gevonden.")
                    } else {
                        comments.joinToString("") { comment ->
                            val role = YouTrackApi.agentRole(comment.body)
                            val iteration = commentIterations[comment.id]
                            val title = listOfNotNull(
                                comment.authorDisplayName ?: "Agent",
                                role?.let { "(${it.markerKeyPart} $iteration)" },
                            ).joinToString(" ")
                            """
                            <article class="brief-card">
                              <div class="brief-head">
                                <span class="icon-tile">${comment.authorDisplayName?.initials() ?: "AI"}</span>
                                <div>
                                  <strong>${title.e()}</strong><br>
                                  <span class="muted">${timestamp(comment.created)} - ${relative(comment.created)}</span>
                                </div>
                              </div>
                              <pre>${comment.body.e()}</pre>
                            </article>
                            """.trimIndent()
                        }
                    }
                } +
                section("Agent-run samenvattingen") {
                    if (page.agentRuns.isEmpty()) {
                        empty("Nog geen agent-runs gevonden.")
                    } else {
                        agentRuns.joinToString("") { run ->
                            val outcome = outcomePresentation(run)
                            val iteration = runIterations[run.id] ?: "1/1"
                            """
                            <article class="brief-card">
                              <div class="brief-head">
                                <span class="icon-tile">${run.role.take(3).uppercase()}</span>
                                <div>
                                  <strong>${run.role.e()} ($iteration)</strong> ${badge(outcome.label, outcome.kind)}<br>
                                  <span class="muted">Gestart ${timestamp(run.startedAt)} - ${relative(run.startedAt)}${run.endedAt?.let { " · klaar ${timestamp(it)}" } ?: ""}</span>
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
                    }
                }
        }

    fun screenshots(page: StoryDetailPageData): String =
        detailLayout(page, "Screenshots") {
            alerts(page.errors) +
                backLink(page.storyKey) +
                if (page.events.isEmpty()) {
                    empty("Nog geen tester-screenshots gevonden.")
                } else {
                    """<section class="screenshot-grid">""" +
                        page.events.joinToString("") { event ->
                            """
                            <article class="screenshot-card">
                              <div class="screenshot-thumb">PNG</div>
                              <strong>${event.kind.e()}</strong>
                              <span class="muted">${relative(event.ts)} - ${event.role.e()}</span>
                              <pre>${event.payloadText.take(500).e()}</pre>
                            </article>
                            """.trimIndent()
                        } +
                        "</section>"
                }
        }

    fun agents(page: AgentsPageData): String =
        layout("agents", "Agents", "Lopende factory-agents en recente sessies") {
            alerts(page.errors) +
                section("Factory agents") {
                    if (page.activeAgentRuns.isEmpty()) {
                        empty("Geen actieve agent-runs.")
                    } else {
                        agentRunRows(page.activeAgentRuns)
                    }
                } +
                section("Recente agent-runs") {
                    agentRunRows(page.recentAgentRuns)
                }
        }

    fun merged(page: MergedPageData): String =
        layout("merged", "Recent merged", "PR's die naar main zijn gemerged") {
            alerts(page.errors) +
                if (page.mergedRuns.isEmpty()) {
                    empty("Nog geen gemergede story-runs gevonden.")
                } else {
                    """
                    <section class="panel">
                      <div class="row table-head merged-row">
                        <span>Story / PR</span><span>Status</span><span>Merged</span><span>Tokens</span><span>Cost</span><span></span>
                      </div>
                      ${page.mergedRuns.joinToString("") { run ->
                        """
                        <a class="row row-link merged-row" href="/stories/${run.storyKey.path()}">
                          <span><strong>${run.storyKey.e()}</strong><br><span class="muted">${run.targetRepo.e()}</span></span>
                          <span>${badge(run.finalStatus ?: "merged")}</span>
                          <span>${relative(run.endedAt)}</span>
                          <span>${tokens(run.totalTokens)}</span>
                          <span>${money(run.totalCostUsdEst)}</span>
                          <span class="chevron">&gt;</span>
                        </a>
                        """.trimIndent()
                      }}
                    </section>
                    """.trimIndent()
                }
        }

    fun downloads(): String =
        layout("downloads", "Downloads", "Build artifacts en APK's") {
            """
            <section class="panel">
              <div class="empty">
                <strong>Nog geen artifact store gekoppeld.</strong>
                <p class="muted">De UI-route staat klaar; zodra de factory artifacts registreert kunnen APK's en andere downloads hier verschijnen.</p>
              </div>
            </section>
            """.trimIndent()
        }

    fun settings(page: SettingsPageData): String =
        layout("settings", "Settings", "Account en lokale dashboardinstellingen") {
            """
            <section class="panel settings-panel">
              <div class="row settings-row"><span class="icon-tile">USR</span><span><span class="muted">Gebruiker</span><br><strong>${page.username.e()}</strong></span></div>
              <form method="post" action="/logout" class="row settings-row">
                <span class="icon-tile">OUT</span>
                <span><strong>Sessie</strong><br><span class="muted">Ingelogd op dit apparaat</span></span>
                <button class="button danger" type="submit">Uitloggen</button>
              </form>
            </section>
            <section>
              <h2>Configuratie</h2>
              <div class="panel key-value">
                ${page.configuration.entries.joinToString("") { (key, value) ->
                  """<div><span>${key.e()}</span><strong>${value.e()}</strong></div>"""
                }}
              </div>
            </section>
            """.trimIndent()
        }

    private fun detailLayout(page: StoryDetailPageData, title: String, content: () -> String): String {
        val issueTitle = page.issue?.summary ?: "Issue niet geladen"
        return layout("stories", "$title - ${page.storyKey}", issueTitle) {
            content()
        }
    }

    private fun statusPanel(page: StoryDetailPageData): String {
        val issue = page.issue
        val statusText = when {
            issue == null -> "Niet geladen"
            !issue.fields.error.isNullOrBlank() -> "Vastgelopen"
            issue.fields.paused -> "Gepauzeerd"
            issue.fields.aiPhase.isNullOrBlank() -> "Klaar voor pickup"
            else -> issue.fields.aiPhase
        }
        val kind = when {
            issue?.fields?.error?.isNotBlank() == true -> "bad"
            issue?.fields?.paused == true -> "warn"
            issue?.fields?.aiPhase == "tested-successfully" -> "ok"
            else -> "info"
        }
        return """
        <section class="status-panel $kind">
          <div>
            <strong>${statusText.e()}</strong>
            <p>${issue?.fields?.error?.takeIf { it.isNotBlank() }?.e() ?: phaseDescription(issue)}</p>
          </div>
          <span>${badge(issue?.status ?: "unknown", kind)}</span>
        </section>
        """.trimIndent()
    }

    private fun linksPanel(page: StoryDetailPageData): String =
        """
        <section class="panel links-panel">
          <div class="section-label">Links</div>
          <div class="button-row">
            <a class="button" href="${page.youTrackUrl.e()}">YouTrack</a>
            ${page.run?.prUrl?.let { """<a class="button" href="${it.e()}">PR #${page.run.prNumber}</a>""" } ?: ""}
            ${page.previewUrl?.let { """<a class="button" href="${it.e()}">Test op preview</a>""" } ?: ""}
            <a class="button" href="/stories/${page.storyKey.path()}/briefing">Briefing</a>
            <a class="button" href="/stories/${page.storyKey.path()}/screenshots">Screenshots</a>
          </div>
        </section>
        """.trimIndent()

    private fun commandPanel(storyKey: String): String =
        """
        <section class="panel">
          <div class="section-label">Commando's</div>
          <div class="button-row">
            ${commandForm(storyKey, "pause", "Pause")}
            ${commandForm(storyKey, "clear-error", "Clear error")}
            ${commandForm(storyKey, "retry-current-step", "Retry current step", "warn")}
            ${commandForm(storyKey, "merge", "Merge")}
            ${commandForm(storyKey, "delete", "Delete", "danger")}
            ${commandForm(storyKey, "re-implement", "Re-implement", "warn")}
          </div>
        </section>
        """.trimIndent()

    private fun commandForm(storyKey: String, command: String, label: String, kind: String = ""): String =
        """
        <form method="post" action="/stories/${storyKey.path()}/commands/$command">
          <button class="button $kind" type="submit">$label</button>
        </form>
        """.trimIndent()

    private fun budgetPanel(issue: TrackerIssue?, run: UiStoryRun?): String {
        val budget = issue?.fields?.aiTokenBudget ?: 40_000L
        val used = listOf(run?.totalTokens ?: 0L, issue?.fields?.aiTokensUsed ?: 0L).max()
        val percent = if (budget > 0) ((used.toDouble() / budget.toDouble()) * 100).roundToInt().coerceIn(0, 999) else 0
        return """
        <section class="panel">
          <div class="budget-head"><span class="section-label">Budget</span><strong>${percent}%</strong></div>
          <div class="progress"><span style="width:${percent.coerceAtMost(100)}%"></span></div>
          <div class="budget-foot"><span>${tokens(used)} / ${tokens(budget)} tokens</span><span>${tokens((budget - used).coerceAtLeast(0))} over</span></div>
        </section>
        """.trimIndent()
    }

    private fun overviewPanel(page: StoryDetailPageData): String =
        section("Overzicht") {
            val issue = page.issue
            val run = page.run
            """
            <div class="panel key-value">
              <div><span>Gestart</span><strong>${date(run?.startedAt)}</strong></div>
              <div><span>Geeindigd</span><strong>${date(run?.endedAt)}</strong></div>
              <div><span>Final status</span><strong>${run?.finalStatus?.e() ?: "lopend"}</strong></div>
              <div><span>Target repo</span><strong>${issue?.fields?.targetRepo?.e() ?: run?.targetRepo?.e() ?: "-"}</strong></div>
              <div><span>AI supplier</span><strong>${issue?.fields?.aiSupplier?.e() ?: "-"}</strong></div>
              <div><span>AI level</span><strong>${issue?.fields?.aiLevel?.toString()?.e() ?: "-"}</strong></div>
              <div><span>Aantal agent-runs</span><strong>${page.agentRuns.size}</strong></div>
              <div><span>Input tokens</span><strong>${tokens(run?.totalInputTokens ?: 0)}</strong></div>
              <div><span>Output tokens</span><strong>${tokens(run?.totalOutputTokens ?: 0)}</strong></div>
              <div><span>Cache-read tokens</span><strong>${tokens(run?.totalCacheReadTokens ?: 0)}</strong></div>
              <div><span>Cache-creation tokens</span><strong>${tokens(run?.totalCacheCreationTokens ?: 0)}</strong></div>
              <div><span>Geschatte kosten</span><strong>${money(run?.totalCostUsdEst ?: 0.0)}</strong></div>
            </div>
            """.trimIndent()
        }

    private fun agentRunsPanel(runs: List<UiAgentRun>): String =
        section("Agent-runs") {
            if (runs.isEmpty()) empty("Nog geen agent-runs gevonden.") else agentRunRows(runs.sortedByNewestRun())
        }

    private fun issueTable(issues: List<TrackerIssue>, runsByStory: Map<String, UiStoryRun>, limit: Int): String {
        val visible = issues.take(limit)
        if (visible.isEmpty()) {
            return empty("Geen stories in Develop met een actieve AI-supplier.")
        }
        return """
        <section class="panel">
          <div class="row table-head story-row">
            <span>Story</span><span>Status</span><span>Fase</span><span>Runs</span><span>Tokens</span><span>AI lvl</span><span>Budget</span><span>Cost</span><span></span>
          </div>
          ${visible.joinToString("") { issue ->
            val run = runsByStory[issue.key]
            val budget = issue.fields.aiTokenBudget ?: 40_000L
            val used = listOf(issue.fields.aiTokensUsed ?: 0L, run?.totalTokens ?: 0L).max()
            """
            <a class="row row-link story-row" href="/stories/${issue.key.path()}">
              <span><strong>${issue.key.e()}</strong><br><span class="muted">${issue.summary.e()}</span></span>
              <span>${badge(issue.status.ifBlank { "Develop" })}</span>
              <span>${phaseDots(issue.fields.aiPhase)}</span>
              <span>${if (run == null) "-" else "open"}</span>
              <span>${tokens(used)}</span>
              <span>L${issue.fields.aiLevel ?: 0}</span>
              <span>${tokens(used)} / ${tokens(budget)}</span>
              <span>${money(run?.totalCostUsdEst ?: 0.0)}</span>
              <span class="chevron">&gt;</span>
            </a>
            """.trimIndent()
          }}
        </section>
        """.trimIndent()
    }

    private fun storyRunList(runs: List<UiStoryRun>): String {
        if (runs.isEmpty()) {
            return empty("Nog geen story-runs gevonden.")
        }
        return """
        <section class="panel">
          ${runs.joinToString("") { run ->
            """
            <a class="row row-link run-row" href="/stories/${run.storyKey.path()}">
              <span><strong>${run.storyKey.e()}</strong><br><span class="muted">${run.targetRepo.e()}</span></span>
              <span>${badge(run.finalStatus ?: if (run.endedAt == null) "running" else "done")}</span>
              <span>${tokens(run.totalTokens)}</span>
              <span>${money(run.totalCostUsdEst)}</span>
              <span>${relative(run.startedAt)}</span>
              <span class="chevron">&gt;</span>
            </a>
            """.trimIndent()
          }}
        </section>
        """.trimIndent()
    }

    private fun agentRunRows(runs: List<UiAgentRun>): String {
        if (runs.isEmpty()) {
            return empty("Geen agent-runs gevonden.")
        }
        val sortedRuns = runs.sortedByNewestRun()
        val iterations = agentRunIterationLabels(sortedRuns)
        return """
        <section class="panel">
          ${sortedRuns.joinToString("") { run ->
            val outcome = outcomePresentation(run)
            """
            <a class="row row-link agent-row" href="/stories/${run.storyKey.path()}">
              <span class="icon-tile">${run.role.take(3).uppercase()}</span>
              <span>
                <strong>${run.storyKey.e()}</strong><br>
                <span class="muted">${run.role.e()} (${iterations[run.id] ?: "1/1"}) - ${run.containerName.e()}</span><br>
                <span class="muted">Gestart ${timestamp(run.startedAt)}${run.endedAt?.let { " · klaar ${timestamp(it)}" } ?: ""}</span>
              </span>
              <span>${badge(outcome.label, outcome.kind)}</span>
              <span class="nowrap">${timestamp(run.startedAt)}</span>
              <span>${tokens(run.totalTokens)}</span>
              <span>${duration(run.durationMs)}</span>
              <span>${money(run.costUsdEst)}</span>
              <span class="chevron">&gt;</span>
            </a>
            """.trimIndent()
          }}
        </section>
        """.trimIndent()
    }

    private fun layout(active: String, title: String, subtitle: String, content: () -> String): String =
        """
        <!doctype html>
        <html lang="nl">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${title.e()} - Software Factory</title>
          <link rel="stylesheet" href="/sf-ui.css">
        </head>
        <body>
          <div class="app-shell">
            <aside class="sidebar">
              <a class="brand" href="/dashboard"><span class="brand-mark">SF</span><strong>Software Factory</strong></a>
              <nav class="nav">
                ${nav(active, "dashboard", "/dashboard", "D", "Dashboard")}
                ${nav(active, "stories", "/stories", "S", "Stories")}
                ${nav(active, "agents", "/agents", "A", "Agents")}
                ${nav(active, "merged", "/merged", "M", "Recent merged")}
                ${nav(active, "downloads", "/downloads", "DL", "Downloads")}
                ${nav(active, "settings", "/settings", "SET", "Settings")}
              </nav>
            </aside>
            <main class="content">
              <header class="page-head">
                <div><h1>${title.e()}</h1><p>${subtitle.e()}</p></div>
                <a class="button icon-only" href="">Refresh</a>
              </header>
              ${content()}
            </main>
          </div>
        </body>
        </html>
        """.trimIndent()

    private fun nav(active: String, key: String, href: String, icon: String, label: String): String =
        """<a class="${if (active == key) "active" else ""}" href="$href"><span>$icon</span>$label</a>"""

    private fun section(title: String, body: () -> String): String =
        """<section><h2>${title.e()}</h2>${body()}</section>"""

    private fun panel(body: String): String =
        """<section class="panel">$body</section>"""

    private fun metricGrid(vararg metrics: Pair<String, String>): String =
        """<div class="metric-grid">${metrics.joinToString("") { """<div><span>${it.first.e()}</span><strong>${it.second.e()}</strong></div>""" }}</div>"""

    private fun alerts(errors: List<String>): String =
        errors.joinToString("") { """<p class="alert bad">${it.e()}</p>""" }

    private fun empty(message: String): String =
        """<section class="panel"><div class="empty">${message.e()}</div></section>"""

    private fun backLink(storyKey: String): String =
        """<p><a class="button" href="/stories/${storyKey.path()}">Terug naar story</a></p>"""

    private fun badge(value: String, forcedKind: String? = null): String {
        val kind = forcedKind ?: when {
            value.contains("success", ignoreCase = true) || value.contains("merged", ignoreCase = true) || value.contains("pass", ignoreCase = true) -> "ok"
            value.contains("fail", ignoreCase = true) || value.contains("error", ignoreCase = true) || value.contains("vast", ignoreCase = true) -> "bad"
            value.contains("pause", ignoreCase = true) || value.contains("stuck", ignoreCase = true) -> "warn"
            else -> "info"
        }
        return """<span class="badge $kind">${value.e()}</span>"""
    }

    private fun phaseDots(phase: String?): String {
        val index = when (phase) {
            "refining", "refined-with-questions-for-user", "refined-finished", "questions-answered-for-refinement" -> 0
            "developing", "developed", "reviewed-with-feedback-for-developer", "tested-with-feedback-for-developer" -> 1
            "reviewing", "review-finished" -> 2
            "testing", "tested-successfully" -> 3
            else -> -1
        }
        return """
        <span class="phase" title="${phase?.e() ?: "Geen fase"}">
          ${(0..3).joinToString("") { """<span class="dot ${if (it < index) "done" else if (it == index) "running" else ""}"></span>""" }}
        </span>
        """.trimIndent()
    }

    private fun phaseDescription(issue: TrackerIssue?): String =
        when {
            issue == null -> "De issue kon niet worden opgehaald."
            issue.fields.aiSupplier.isNullOrBlank() || issue.fields.aiSupplier == "none" -> "AI supplier staat leeg of op none."
            issue.fields.aiPhase.isNullOrBlank() -> "Develop status met supplier ${issue.fields.aiSupplier}; klaar voor pickup."
            else -> "Huidige fase: ${issue.fields.aiPhase}."
        }

    private fun outcomePresentation(run: UiAgentRun): OutcomePresentation {
        val raw = run.outcome?.trim().orEmpty()
        if (run.endedAt == null && raw.isBlank()) {
            return OutcomePresentation("Loopt", null, "info")
        }
        val normalized = raw.lowercase()
        val role = run.role.lowercase()
        return when {
            normalized == "refined-finished" || (role == "refiner" && normalized == "ok") ->
                OutcomePresentation("Refinement klaar", "REFINED_FINISHED", "ok")
            normalized == "refined-with-questions-for-user" || (role == "refiner" && normalized == "questions") ->
                OutcomePresentation("Vragen voor gebruiker", "REFINED_WITH_QUESTIONS_FOR_USER", "warn")
            normalized == "developed" || (role == "developer" && normalized == "ok") ->
                OutcomePresentation("Ontwikkeld", "DEVELOPED", "ok")
            normalized == "review-finished" || (role == "reviewer" && normalized == "ok") ->
                OutcomePresentation("Review akkoord", "REVIEW_FINISHED", "ok")
            normalized == "reviewed-with-feedback-for-developer" || (role == "reviewer" && normalized == "feedback") ->
                OutcomePresentation("Review met feedback", "REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER", "warn")
            normalized == "tested-successfully" || (role == "tester" && normalized == "ok") ->
                OutcomePresentation("Test geslaagd", "TESTED_SUCCESSFULLY", "ok")
            normalized == "tested-with-feedback-for-developer" || (role == "tester" && normalized == "bug") ->
                OutcomePresentation("Test-feedback", "TESTED_WITH_FEEDBACK_FOR_DEVELOPER", "warn")
            normalized == "credits-exhausted" ->
                OutcomePresentation("Credits op", "CREDITS_EXHAUSTED", "bad")
            normalized == "stopped-manually" ->
                OutcomePresentation("Handmatig gestopt", "STOPPED_MANUALLY", "warn")
            normalized.contains("error") || normalized.contains("failed") ->
                OutcomePresentation("Mislukt", raw.uppercase().replace("-", "_"), "bad")
            raw.isNotBlank() ->
                OutcomePresentation(raw.replace("-", " ").replaceFirstChar { it.titlecase() }, raw.uppercase().replace("-", "_"), "info")
            else ->
                OutcomePresentation("Afgerond", null, "info")
        }
    }

    private fun List<UiAgentRun>.sortedByNewestRun(): List<UiAgentRun> =
        sortedWith(compareByDescending<UiAgentRun> { it.startedAt }.thenByDescending { it.id })

    private fun List<TrackerComment>.sortedByNewestComment(): List<TrackerComment> =
        sortedWith(compareByDescending<TrackerComment> { it.created ?: OffsetDateTime.MIN }.thenByDescending { it.id })

    private fun agentRunIterationLabels(runs: List<UiAgentRun>): Map<Long, String> =
        runs.groupBy { it.role.lowercase() }
            .flatMap { (_, roleRuns) ->
                val chronological = roleRuns.sortedWith(compareBy<UiAgentRun> { it.startedAt }.thenBy { it.id })
                chronological.mapIndexed { index, run -> run.id to "${index + 1}/${chronological.size}" }
            }
            .toMap()

    private fun commentIterationLabels(comments: List<TrackerComment>): Map<String, String> =
        comments.mapNotNull { comment ->
            YouTrackApi.agentRole(comment.body)?.let { role -> role to comment }
        }
            .groupBy({ it.first }, { it.second })
            .flatMap { (_, roleComments) ->
                val chronological = roleComments.sortedWith(compareBy<TrackerComment> { it.created ?: OffsetDateTime.MIN }.thenBy { it.id })
                chronological.mapIndexed { index, comment -> comment.id to "${index + 1}/${chronological.size}" }
            }
            .toMap()

    private fun relative(value: OffsetDateTime?): String {
        if (value == null) {
            return "-"
        }
        val duration = Duration.between(value, OffsetDateTime.now(clock)).abs()
        return when {
            duration.toMinutes() < 1 -> "net"
            duration.toHours() < 1 -> "${duration.toMinutes()}m geleden"
            duration.toDays() < 1 -> "${duration.toHours()}u geleden"
            else -> "${duration.toDays()}d geleden"
        }
    }

    private fun date(value: OffsetDateTime?): String =
        value?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: "-"

    private fun timestamp(value: OffsetDateTime?): String =
        value?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) ?: "-"

    private fun tokens(value: Long): String =
        when {
            value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
            value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
            else -> value.toString()
        }

    private fun money(value: Double): String =
        String.format(Locale.US, "${'$'}%.4f", value)

    private fun duration(value: Long): String {
        if (value <= 0) {
            return "-"
        }
        val seconds = value / 1000
        val minutes = seconds / 60
        val remaining = seconds % 60
        return if (minutes > 0) "${minutes}m ${remaining}s" else "${seconds}s"
    }

    private fun String.e(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun String.path(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

    private fun String.initials(): String =
        split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "AI" }

    private data class OutcomePresentation(
        val label: String,
        val code: String?,
        val kind: String,
    )
}
