package nl.vdzon.softwarefactory.web.views

import nl.vdzon.softwarefactory.web.models.AgentsPageData
import nl.vdzon.softwarefactory.web.models.DashboardPageData
import nl.vdzon.softwarefactory.web.models.MergedPageData
import nl.vdzon.softwarefactory.web.models.SettingsPageData
import nl.vdzon.softwarefactory.web.models.StoriesPageData
import nl.vdzon.softwarefactory.web.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.web.models.UiStoryRun
import nl.vdzon.softwarefactory.orchestrator.StoryPhase
import nl.vdzon.softwarefactory.orchestrator.SubtaskPhase
import nl.vdzon.softwarefactory.youtrack.IssueType
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
          $FAVICON
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
                """<p class="label">Productie</p>""" +
                metricGrid(
                    "Actieve stories" to page.issues.size.toString(),
                    "Lopende runs" to page.activeAgentRuns.size.toString(),
                    "Open story-runs" to page.activeRuns.size.toString(),
                    "Laatste run" to (page.recentRuns.firstOrNull()?.storyKey ?: "-"),
                ) +
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
        detailLayout(page, "Story Detail", autoRefreshSeconds = 5) {
            alerts(page.errors) +
                backButton(page) +
                statusPanel(page) +
                humanActionTop(page) +
                actionsBar(page) +
                subtasksPanel(page) +
                overviewDetails(page) +
                agentRunsSection(page.agentRuns)
        }

    fun briefing(page: StoryDetailPageData): String =
        detailLayout(page, "Briefing", autoRefreshSeconds = 5) {
            val agentRuns = page.agentRuns.sortedByNewestRun()
            val runIterations = agentRunIterationLabels(agentRuns)
            alerts(page.errors) +
                statusPanel(page) +
                backLink(page.storyKey) +
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
                    <section class="list merged">
                      <div class="lhead"><span>Story / PR</span><span>Status</span><span>Merged</span><span>Tokens</span><span>Kosten</span><span></span></div>
                      ${page.mergedRuns.joinToString("") { run ->
                        """
                        <a class="lrow" href="/stories/${run.storyKey.path()}">
                          <span class="k">${run.storyKey.e()}<span class="desc">${run.targetRepo.e()}</span></span>
                          <span>${badge(run.finalStatus ?: "merged")}</span>
                          <span class="num">${relative(run.endedAt)}</span>
                          <span class="num">${tokens(run.totalTokens)}</span>
                          <span class="num">${money(run.totalCostUsdEst)}</span>
                          <span class="go">&rarr;</span>
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
            <div class="empty">
              <strong>Nog geen artifact store gekoppeld.</strong>
              De UI-route staat klaar; zodra de factory artifacts registreert kunnen APK's en andere downloads hier verschijnen.
            </div>
            """.trimIndent()
        }

    fun settings(page: SettingsPageData): String =
        layout("settings", "Settings", "Account en lokale dashboardinstellingen") {
            """
            <div class="status">
              <span class="avatar">USR</span>
              <span><span class="muted" style="font-size:13px">Gebruiker</span><br><b>${page.username.e()}</b></span>
              <span class="spacer"></span>
              <form method="post" action="/logout"><button class="button danger" type="submit">Uitloggen</button></form>
            </div>
            <div class="rule"></div>
            """.trimIndent() +
                section("Configuratie") {
                    """
                    <div class="key-value one">
                      ${page.configuration.entries.joinToString("") { (key, value) ->
                        """<div><span>${key.e()}</span><strong>${value.e()}</strong></div>"""
                    }}
                    </div>
                    """.trimIndent()
                }
        }

    // ── story-detail bouwstenen ─────────────────────────────────────────────

    private fun detailLayout(
        page: StoryDetailPageData,
        title: String,
        autoRefreshSeconds: Int? = null,
        content: () -> String,
    ): String {
        val issueTitle = page.issue?.summary ?: "Issue niet geladen"
        return layout(
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

    private fun statusPanel(page: StoryDetailPageData): String {
        val issue = page.issue
        val phase = issue?.displayPhase()
        val statusText = when {
            issue == null -> "Niet geladen"
            !issue.fields.error.isNullOrBlank() -> "Vastgelopen"
            issue.fields.paused -> "Gepauzeerd"
            phase.isNullOrBlank() -> "Klaar voor pickup"
            else -> phase
        }
        val kind = when {
            issue?.fields?.error?.isNotBlank() == true -> "bad"
            issue?.fields?.paused == true -> "warn"
            phase == "planning-approved" || phase == "tested-successfully" -> "ok"
            else -> "info"
        }
        val dot = when (kind) {
            "bad" -> "bad"
            "warn" -> "wait"
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

    /** Feedback-actiekaart bovenaan: directe actie op deze issue, of de actieve subtaak. */
    private fun humanActionTop(page: StoryDetailPageData): String {
        val issue = page.issue ?: return ""
        val own = when (issue.issueType) {
            IssueType.STORY -> storyActionCard(page.storyKey, issue, "actie nodig", page.agentQuestions[page.storyKey])
            IssueType.SUBTASK -> subtaskActionCard(page.storyKey, issue, "actie nodig", page.agentQuestions[page.storyKey])
        }
        if (own.isNotBlank()) return own
        if (issue.issueType == IssueType.STORY) {
            val active = page.subtasks.firstOrNull { subtaskAwaitsHuman(it) }
            if (active != null) {
                // Gesurfacet op het story-scherm: na de actie terug naar de story, niet naar de subtaak.
                return subtaskActionCard(
                    active.key,
                    active,
                    "Subtaak ${active.key.e()} &middot; actie nodig",
                    page.agentQuestions[active.key],
                    returnTo = "/stories/${page.storyKey.path()}",
                )
            }
        }
        return ""
    }

    private fun storyActionCard(storyKey: String, issue: TrackerIssue, context: String, question: String?): String =
        when (StoryPhase.fromTracker(issue.fields.storyPhase)) {
            StoryPhase.REFINED_WITH_QUESTIONS ->
                answerCard(storyKey, "story-phase", "questions-answered", "Vraag van de refiner", context, question)
            StoryPhase.PLANNED_WITH_QUESTIONS ->
                answerCard(storyKey, "story-phase", "planning-questions-answered", "Vraag van de planner", context, question)
            StoryPhase.REFINED ->
                approveRejectCard(storyKey, "story-phase", "refined-approved", "refined-rejected", "Refinement beoordelen", "De refiner is klaar. Keur goed om door te gaan, of stuur terug met feedback.", context)
            StoryPhase.PLANNED ->
                approveRejectCard(storyKey, "story-phase", "planning-approved", "planning-rejected", "Plan beoordelen", "De planner heeft het plan afgerond. Keur goed om te starten, of stuur terug met feedback.", context)
            else -> ""
        }

    private fun subtaskActionCard(subtaskKey: String, issue: TrackerIssue, context: String, question: String? = null, returnTo: String? = null): String {
        val ep = "subtask-phase"
        val isDevelopmentSubtask = issue.fields.subtaskType.equals("development", ignoreCase = true)
        return when (SubtaskPhase.fromTracker(issue.fields.subtaskPhase)) {
            SubtaskPhase.AWAITING_HUMAN ->
                approveOnlyCard(subtaskKey, ep, "manual-action-done", "Handmatige actie afronden", "De factory wacht op een handmatige stap. Markeer als klaar zodra je het hebt gedaan.", "Mark done", context, returnTo)
            SubtaskPhase.DEVELOPED_WITH_QUESTIONS ->
                answerCard(subtaskKey, ep, "development-questions-answered", "Vraag van de developer", context, question, returnTo)
            SubtaskPhase.REVIEWED_WITH_QUESTIONS ->
                answerCard(subtaskKey, ep, "review-questions-answered", "Vraag van de reviewer", context, question, returnTo)
            SubtaskPhase.TESTED_WITH_QUESTIONS ->
                answerCard(subtaskKey, ep, "test-questions-answered", "Vraag van de tester", context, question, returnTo)
            SubtaskPhase.SUMMARY_WITH_QUESTIONS ->
                answerCard(subtaskKey, ep, "summary-questions-answered", "Vraag van de summarizer", context, question, returnTo)
            SubtaskPhase.DEVELOPED ->
                if (isDevelopmentSubtask) {
                    approveRejectCard(subtaskKey, ep, "development-approved", "development-rejected", "Ontwikkeling beoordelen", "De developer heeft de wijziging geïmplementeerd en gepusht. Bekijk het resultaat en keur goed, of stuur terug met feedback.", context, returnTo)
                } else {
                    ""
                }
            SubtaskPhase.REVIEWED ->
                approveRejectCard(subtaskKey, ep, "review-approved", "review-rejected", "Review beoordelen", "De reviewer is klaar. Keur de review goed, of stuur terug met feedback.", context, returnTo)
            SubtaskPhase.TESTED ->
                approveRejectCard(subtaskKey, ep, "test-approved", "test-rejected", "Test beoordelen", "De tester is klaar. Keur het testresultaat goed, of stuur terug met feedback.", context, returnTo)
            SubtaskPhase.SUMMARIZED ->
                approveRejectCard(subtaskKey, ep, "summary-approved", "summary-rejected", "Samenvatting beoordelen", "De samenvatting is klaar. Keur goed, of stuur terug met feedback.", context, returnTo)
            else -> ""
        }
    }

    private fun answerCard(key: String, endpoint: String, targetPhase: String, title: String, context: String, question: String?, returnTo: String? = null): String =
        """
        <section class="action-card">
          <div class="ac-head"><span class="ac-title">$title</span><span class="pill-wait">$context</span></div>
          ${question?.takeIf { it.isNotBlank() }?.let { """<div class="q">${it.e()}</div>""" } ?: ""}
          <form method="post" action="/stories/${key.path()}/$endpoint">
            ${returnToField(returnTo)}
            <input type="hidden" name="phase" value="$targetPhase">
            <textarea name="comment" rows="3" placeholder="Jouw antwoord" required></textarea>
            <div class="button-row"><button class="button primary" type="submit">Antwoord versturen</button></div>
          </form>
        </section>
        """.trimIndent()

    private fun approveRejectCard(key: String, endpoint: String, approvePhase: String, rejectPhase: String, title: String, note: String, context: String, returnTo: String? = null): String =
        """
        <section class="action-card">
          <div class="ac-head"><span class="ac-title">$title</span><span class="pill-wait">$context</span></div>
          <p class="ac-note">${note.e()}</p>
          <form method="post" action="/stories/${key.path()}/$endpoint">
            ${returnToField(returnTo)}
            <textarea name="comment" rows="3" placeholder="Reden (optioneel)"></textarea>
            <div class="button-row">
              <button class="button primary" type="submit" name="phase" value="$approvePhase">Approve</button>
              <button class="button danger" type="submit" name="phase" value="$rejectPhase">Reject</button>
            </div>
          </form>
        </section>
        """.trimIndent()

    private fun approveOnlyCard(key: String, endpoint: String, targetPhase: String, title: String, note: String, label: String, context: String, returnTo: String? = null): String =
        """
        <section class="action-card">
          <div class="ac-head"><span class="ac-title">$title</span><span class="pill-wait">$context</span></div>
          <p class="ac-note">${note.e()}</p>
          <form method="post" action="/stories/${key.path()}/$endpoint">
            ${returnToField(returnTo)}
            <input type="hidden" name="phase" value="$targetPhase">
            <textarea name="comment" rows="2" placeholder="Notitie (optioneel)"></textarea>
            <div class="button-row"><button class="button primary" type="submit">$label</button></div>
          </form>
        </section>
        """.trimIndent()

    /** Verborgen veld zodat een actie op een gesurfacede subtaak terugkeert naar het juiste scherm. */
    private fun returnToField(returnTo: String?): String =
        returnTo?.takeIf { it.isNotBlank() }?.let { """<input type="hidden" name="returnTo" value="${it.e()}">""" } ?: ""

    /** Eén kalm menu met alle commando's + links; klein, uitklapbaar budget ernaast. */
    private fun actionsBar(page: StoryDetailPageData): String {
        val key = page.storyKey
        return """
        <div class="bar-row">
          <details class="menu">
            <summary>Acties &amp; links <span class="chev">&#8964;</span></summary>
            <div class="pop">
              ${startDevelopingItem(page)}
              <div class="grp">
                <span class="grp-label">Commando's</span>
                ${cmd(key, "pause", "Pause")}
                ${cmd(key, "clear-error", "Clear error")}
                ${cmd(key, "retry-current-step", "Retry current step")}
                ${cmd(key, "sync", "Commit + push")}
                ${cmd(key, "merge", "Merge")}
                ${cmd(key, "re-implement", "Re-implement")}
              </div>
              <div class="grp">
                <span class="grp-label">Links</span>
                <a href="${page.youTrackUrl.e()}">YouTrack <span class="ext">&#8599;</span></a>
                ${page.run?.prUrl?.let { """<a href="${it.e()}">PR #${page.run.prNumber} <span class="ext">&#8599;</span></a>""" } ?: ""}
                ${page.previewUrl?.let { """<a href="${it.e()}">Test op preview <span class="ext">&#8599;</span></a>""" } ?: ""}
                ${page.run?.workspacePath?.takeIf { it.isNotBlank() }?.let { openWorkspaceItem(key) } ?: ""}
                <a href="/stories/${key.path()}/briefing">Briefing</a>
                <a href="/stories/${key.path()}/screenshots">Screenshots</a>
              </div>
              <div class="grp">
                ${cmd(key, "delete", "Delete story", "danger")}
                ${if (page.issue?.issueType == IssueType.STORY) purgeButton(key) else ""}
              </div>
            </div>
          </details>
          <span class="spacer"></span>
          ${budgetMenu(page.issue, page.run)}
        </div>
        """.trimIndent()
    }

    private fun cmd(storyKey: String, command: String, label: String, kind: String = ""): String =
        """<form method="post" action="/stories/${storyKey.path()}/commands/$command"><button class="$kind" type="submit">$label</button></form>"""

    /**
     * Hard purge: verwijdert de hele story (issue + subtaken + branch + workfolder + run)
     * permanent. Extra waarschuwing via een JS-confirm voor het posten van het form.
     */
    private fun purgeButton(storyKey: String): String =
        """<form method="post" action="/stories/${storyKey.path()}/purge" onsubmit="return confirm('${storyKey.e()} volledig verwijderen?\n\nDit verwijdert de story én alle subtaken PERMANENT uit YouTrack, plus de branch, PR en workfolder. Dit kan niet ongedaan gemaakt worden.');"><button class="danger" type="submit">Verwijder story volledig (incl. subtaken)</button></form>"""

    private fun openWorkspaceItem(storyKey: String): String =
        """<form method="post" action="/stories/${storyKey.path()}/open-workspace"><button type="submit">Open in IntelliJ <span class="ext">&#8599;</span></button></form>"""

    /** "Start developing": tagt de eerste subtask `ai-development`. Alleen in planning-approved met ongetagde subtaken. */
    private fun startDevelopingItem(page: StoryDetailPageData): String {
        val issue = page.issue ?: return ""
        if (issue.issueType != IssueType.STORY) return ""
        if (StoryPhase.fromTracker(issue.fields.storyPhase) != StoryPhase.PLANNING_APPROVED) return ""
        if (page.subtasks.isEmpty() || page.subtasks.any { "ai-development" in it.tags }) return ""
        return """<form method="post" action="/stories/${page.storyKey.path()}/start-developing"><button class="primary" type="submit">&#9654; Start developing</button></form>"""
    }

    private fun budgetMenu(issue: TrackerIssue?, run: UiStoryRun?): String {
        val used = listOf(run?.totalTokens ?: 0L, issue?.fields?.aiTokensUsed ?: 0L).max()
        val budget = issue?.fields?.aiTokenBudget?.takeIf { it > 0 }
        // Geen budget ingesteld → onbeperkt: geen percentage/limiet, alleen het verbruik tonen.
        if (budget == null) {
            return """
            <details class="budget">
              <summary>Budget <span class="pct">&#8734;</span> <span class="chev">&#8964;</span></summary>
              <div class="pop right">
                <div class="big">Onbeperkt</div>
                <div class="foot"><span>${tokens(used)} tokens gebruikt</span><span>geen limiet</span></div>
              </div>
            </details>
            """.trimIndent()
        }
        val percent = ((used.toDouble() / budget.toDouble()) * 100).roundToInt().coerceIn(0, 999)
        val width = percent.coerceAtMost(100)
        return """
        <details class="budget">
          <summary>Budget <span class="pct">${percent}%</span> <span class="mini"><span style="width:${width}%"></span></span> <span class="chev">&#8964;</span></summary>
          <div class="pop right">
            <div class="big">${percent}%</div>
            <div class="bar"><span style="width:${width}%"></span></div>
            <div class="foot"><span>${tokens(used)} / ${tokens(budget)} tokens</span><span>${tokens((budget - used).coerceAtLeast(0))} over</span></div>
          </div>
        </details>
        """.trimIndent()
    }

    /** Of een subtask op een mens-actie wacht (vragen/goedkeuring/handmatig). */
    private fun subtaskAwaitsHuman(issue: TrackerIssue): Boolean =
        subtaskActionCard(issue.key, issue, "").isNotBlank()

    private fun developmentTagBadge(issue: TrackerIssue): String =
        if ("ai-development" in issue.tags) badge("ai-development", "ok") else badge("ongetagd", "neutral")

    private fun subtasksPanel(page: StoryDetailPageData): String {
        if (page.subtasks.isEmpty()) {
            return ""
        }
        return section("Subtaken") {
            page.subtasks.joinToString("") { sub ->
                val waiting = subtaskAwaitsHuman(sub)
                """
                <div class="sub${if (waiting) " needs" else ""}">
                  <span class="n">${sub.key.e()}</span>
                  <div class="body">
                    <div class="t"><a href="/stories/${sub.key.path()}">${sub.summary.e()}</a> ${typeBadge(sub)} ${developmentTagBadge(sub)}${if (waiting) " ${badge("actie nodig", "warn")}" else ""}</div>
                    <div class="d">${sub.fields.subtaskType?.e()?.let { "$it &middot; " } ?: ""}fase: ${sub.fields.subtaskPhase?.e() ?: "—"}</div>
                  </div>
                  <span class="ph">${sub.fields.subtaskPhase?.e() ?: "—"}</span>
                  <a class="go" href="/stories/${sub.key.path()}">&rarr;</a>
                </div>
                """.trimIndent()
            }
        }
    }

    private fun overviewDetails(page: StoryDetailPageData): String {
        val issue = page.issue
        val run = page.run
        return """
        <div class="rule"></div>
        <details class="props">
          <summary>Eigenschappen <span class="chev">&#8964;</span></summary>
          <div class="key-value">
            <div><span>Gestart</span><strong>${date(run?.startedAt)}</strong></div>
            <div><span>Geeindigd</span><strong>${date(run?.endedAt)}</strong></div>
            <div><span>Final status</span><strong>${run?.finalStatus?.e() ?: "lopend"}</strong></div>
            <div><span>Target repo</span><strong>${issue?.fields?.targetRepo?.e() ?: run?.targetRepo?.e() ?: "-"}</strong></div>
            <div><span>Repo folder</span><strong>${run?.workspacePath?.takeIf { it.isNotBlank() }?.let { repoFolder(it).e() } ?: "-"}</strong></div>
            <div><span>AI supplier</span><strong>${issue?.fields?.aiSupplier?.e() ?: "-"}</strong></div>
            <div><span>AI level</span><strong>${issue?.fields?.aiLevel?.toString()?.e() ?: "-"}</strong></div>
            <div><span>Aantal agent-runs</span><strong>${page.agentRuns.size}</strong></div>
            <div><span>Input tokens</span><strong>${tokens(run?.totalInputTokens ?: 0)}</strong></div>
            <div><span>Output tokens</span><strong>${tokens(run?.totalOutputTokens ?: 0)}</strong></div>
            <div><span>Cache-read tokens</span><strong>${tokens(run?.totalCacheReadTokens ?: 0)}</strong></div>
            <div><span>Cache-creation tokens</span><strong>${tokens(run?.totalCacheCreationTokens ?: 0)}</strong></div>
            <div><span>Geschatte kosten</span><strong>${money(run?.totalCostUsdEst ?: 0.0)}</strong></div>
          </div>
        </details>
        """.trimIndent()
    }

    private fun agentRunsSection(runs: List<UiAgentRun>): String =
        section("Agent-runs") {
            if (runs.isEmpty()) empty("Nog geen agent-runs gevonden.") else agentRunRows(runs.sortedByNewestRun())
        }

    private fun repoFolder(workspacePath: String): String =
        workspacePath.trimEnd('/', '\\') + "/repo"

    // ── lijsten ─────────────────────────────────────────────────────────────

    private fun issueTable(issues: List<TrackerIssue>, runsByStory: Map<String, UiStoryRun>, limit: Int): String {
        val visible = issues.take(limit)
        if (visible.isEmpty()) {
            return empty("Geen stories in Develop met een actieve AI-supplier.")
        }
        return """
        <section class="list stories">
          <div class="lhead"><span>Story</span><span>Fase</span><span>Tokens</span><span>Kosten</span><span></span></div>
          ${visible.joinToString("") { issue ->
            val run = runsByStory[issue.key]
            val budget = issue.fields.aiTokenBudget ?: 40_000L
            val used = listOf(issue.fields.aiTokensUsed ?: 0L, run?.totalTokens ?: 0L).max()
            """
            <a class="lrow" href="/stories/${issue.key.path()}">
              <span class="k">${issue.key.e()} ${typeBadge(issue)}<span class="desc">${issue.summary.e()}</span></span>
              <span class="num">${issue.displayPhase()?.e() ?: "—"}</span>
              <span class="num">${tokens(used)} / ${tokens(budget)}</span>
              <span class="num">${money(run?.totalCostUsdEst ?: 0.0)}</span>
              <span class="go">&rarr;</span>
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
        <section class="list runs">
          ${runs.joinToString("") { run ->
            """
            <a class="lrow" href="/stories/${run.storyKey.path()}">
              <span class="k">${run.storyKey.e()}<span class="desc">${run.targetRepo.e()}</span></span>
              <span>${badge(run.finalStatus ?: if (run.endedAt == null) "running" else "done")}</span>
              <span class="num">${tokens(run.totalTokens)}</span>
              <span class="num">${money(run.totalCostUsdEst)}</span>
              <span class="num nowrap">${relative(run.startedAt)}</span>
              <span class="go">&rarr;</span>
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
        <section class="list agents">
          ${sortedRuns.joinToString("") { run ->
            val outcome = outcomePresentation(run)
            """
            <a class="lrow" href="/stories/${run.storyKey.path()}">
              <span class="avatar">${run.role.take(3).uppercase()}</span>
              <span class="k">${run.storyKey.e()}<span class="desc">${run.role.e()} (${iterations[run.id] ?: "1/1"}) &middot; ${run.containerName.e()}<br>Gestart ${timestamp(run.startedAt)}${run.endedAt?.let { " &middot; klaar ${timestamp(it)}" } ?: ""}</span></span>
              <span>${badge(outcome.label, outcome.kind)}</span>
              <span class="num">${tokens(run.totalTokens)}</span>
              <span class="num nowrap">${duration(run.durationMs)}</span>
              <span class="num">${money(run.costUsdEst)}</span>
              <span class="go">&rarr;</span>
            </a>
            """.trimIndent()
          }}
        </section>
        """.trimIndent()
    }

    // ── layout + kleine helpers ─────────────────────────────────────────────

    private fun layout(
        active: String,
        title: String,
        subtitle: String,
        autoRefreshSeconds: Int? = null,
        eyebrow: String? = null,
        browserTitle: String = title,
        content: () -> String,
    ): String =
        """
        <!doctype html>
        <html lang="nl">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          $FAVICON
          <title>${browserTitle.e()} - Software Factory</title>
          <link rel="stylesheet" href="/sf-ui.css">
        </head>
        <body${autoRefreshSeconds?.let { " data-refresh=\"$it\"" } ?: ""}>
          <div class="shell">
            <aside class="sidebar">
              <a class="brand" href="/dashboard"><span class="brand-mark">SF</span>Software Factory</a>
              <nav class="nav">
                ${nav(active, "dashboard", "/dashboard", "Dashboard")}
                ${nav(active, "stories", "/stories", "Stories")}
                ${nav(active, "agents", "/agents", "Agents")}
                ${nav(active, "merged", "/merged", "Recent merged")}
                ${nav(active, "downloads", "/downloads", "Downloads")}
                ${nav(active, "settings", "/settings", "Settings")}
              </nav>
            </aside>
            <main class="content">
              ${eyebrow?.let { """<div class="eyebrow">$it</div>""" } ?: ""}
              <h1>${title.e()}</h1>
              ${if (subtitle.isNotBlank()) """<p class="page-sub">${subtitle.e()}</p>""" else ""}
              ${content()}
            </main>
          </div>
          $AUTO_REFRESH_SCRIPT
        </body>
        </html>
        """.trimIndent()

    private fun nav(active: String, key: String, href: String, label: String): String =
        """<a class="${if (active == key) "active" else ""}" href="$href">$label</a>"""

    private fun section(title: String, body: () -> String): String =
        """<section><h2 class="section-title">${title.e()}</h2>${body()}</section>"""

    private fun metricGrid(vararg metrics: Pair<String, String>): String =
        """<div class="metric-grid">${metrics.joinToString("") { """<div><span>${it.first.e()}</span><strong>${it.second.e()}</strong></div>""" }}</div>"""

    private fun alerts(errors: List<String>): String =
        errors.joinToString("") { """<p class="alert bad">${it.e()}</p>""" }

    private fun empty(message: String): String =
        """<div class="empty">${message.e()}</div>"""

    private fun backLink(storyKey: String): String =
        """<p class="backbar"><a class="button back" href="/stories/${storyKey.path()}">&larr; Terug naar story</a></p>"""

    /** Zichtbare terug-knop op een subpagina (subtask-detail terug naar de parent-story). */
    private fun backButton(page: StoryDetailPageData): String {
        val parent = page.parentKey ?: return ""
        return """<p class="backbar"><a class="button back" href="/stories/${parent.path()}">&larr; Terug naar ${parent.e()}</a></p>"""
    }

    /** v2: Story Phase (story) / Subtask Phase (subtask), met legacy AI Phase als fallback. */
    private fun TrackerIssue.displayPhase(): String? =
        fields.storyPhase?.takeIf { it.isNotBlank() }
            ?: fields.subtaskPhase?.takeIf { it.isNotBlank() }
            ?: fields.aiPhase?.takeIf { it.isNotBlank() }

    private fun typeBadge(issue: TrackerIssue): String =
        when (issue.issueType) {
            IssueType.STORY -> badge("Story", "info")
            IssueType.SUBTASK -> badge("Subtask${issue.fields.subtaskType?.let { ": $it" } ?: ""}", "warn")
        }

    private fun typeTag(issue: TrackerIssue): String =
        when (issue.issueType) {
            IssueType.STORY -> """<span class="tag">Story</span>"""
            IssueType.SUBTASK -> """<span class="tag amber">Subtask${issue.fields.subtaskType?.let { ": ${it.e()}" } ?: ""}</span>"""
        }

    private fun badge(value: String, forcedKind: String? = null): String {
        val kind = forcedKind ?: when {
            value.contains("success", ignoreCase = true) || value.contains("merged", ignoreCase = true) || value.contains("pass", ignoreCase = true) -> "ok"
            value.contains("fail", ignoreCase = true) || value.contains("error", ignoreCase = true) || value.contains("vast", ignoreCase = true) -> "bad"
            value.contains("pause", ignoreCase = true) || value.contains("stuck", ignoreCase = true) -> "warn"
            else -> "info"
        }
        return """<span class="badge $kind">${value.e()}</span>"""
    }

    private fun phaseDescription(issue: TrackerIssue?): String =
        when {
            issue == null -> "De issue kon niet worden opgehaald."
            issue.fields.aiSupplier.isNullOrBlank() || issue.fields.aiSupplier == "none" -> "AI supplier staat leeg of op none."
            issue.displayPhase().isNullOrBlank() -> "Develop status met supplier ${issue.fields.aiSupplier}; klaar voor pickup."
            else -> "Huidige fase: ${issue.displayPhase()}."
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
            normalized == "summary-finished" || (role == "summarizer" && normalized == "ok") ->
                OutcomePresentation("Samenvatting klaar", "SUMMARY_FINISHED", "ok")
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

    private fun agentRunIterationLabels(runs: List<UiAgentRun>): Map<Long, String> =
        runs.groupBy { it.role.lowercase() }
            .flatMap { (_, roleRuns) ->
                val chronological = roleRuns.sortedWith(compareBy<UiAgentRun> { it.startedAt }.thenBy { it.id })
                chronological.mapIndexed { index, run -> run.id to "${index + 1}/${chronological.size}" }
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

    private data class OutcomePresentation(
        val label: String,
        val code: String?,
        val kind: String,
    )

    private companion object {
        /** SF-icoon als inline SVG; vervangt de standaard browser-favicon. */
        private val FAVICON =
            "<link rel=\"icon\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%233f3d56'/%3E%3Ctext x='16' y='22' font-family='Arial,Helvetica,sans-serif' font-size='15' font-weight='bold' fill='%23ffffff' text-anchor='middle'%3ESF%3C/text%3E%3C/svg%3E\">"

        /**
         * Live updates: de backend pusht via SSE (`/events`) een "changed"-signaal; de pagina
         * ververst dan alleen z'n data-laag (`main.content`), niet de hele pagina. Een vangnet-poll
         * dekt het geval dat de SSE-verbinding wegvalt. Een cyclus wordt overgeslagen zodra de
         * gebruiker bezig is — een `<details>`-menu open heeft, tekst geselecteerd heeft of in een
         * invoerveld typt — zodat die interactie niet verloren gaat. Er wordt alleen vervangen als
         * de data daadwerkelijk anders is.
         */
        private val AUTO_REFRESH_SCRIPT =
            """
            <script>
            (function(){
              if (!document.body.hasAttribute('data-refresh')) return;
              function busy(){
                if (document.querySelector('details[open]')) return true;
                var sel = window.getSelection && String(window.getSelection());
                if (sel && sel.length) return true;
                var a = document.activeElement;
                if (a && (a.tagName === 'TEXTAREA' || a.tagName === 'INPUT')) return true;
                return false;
              }
              var pending = false;
              async function refresh(){
                if (busy() || pending) return;
                pending = true;
                try {
                  var res = await fetch(location.href, { credentials: 'same-origin' });
                  if (!res.ok) return;
                  var text = await res.text();
                  var doc = new DOMParser().parseFromString(text, 'text/html');
                  var fresh = doc.querySelector('main.content');
                  var cur = document.querySelector('main.content');
                  if (!fresh || !cur || busy()) return;
                  if (fresh.innerHTML === cur.innerHTML) return;
                  var y = window.scrollY;
                  cur.replaceWith(fresh);
                  window.scrollTo(0, y);
                } catch (e) {
                } finally {
                  pending = false;
                }
              }
              try {
                var es = new EventSource('/events');
                es.addEventListener('changed', function(){ refresh(); });
              } catch (e) {}
              setInterval(refresh, 30000);
            })();
            </script>
            """.trimIndent()
    }
}
