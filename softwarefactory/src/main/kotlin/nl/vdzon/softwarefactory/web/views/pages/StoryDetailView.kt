package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.web.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.models.UiStoryRun
import nl.vdzon.softwarefactory.web.views.shared.ActionCards
import nl.vdzon.softwarefactory.web.views.shared.DetailChrome
import nl.vdzon.softwarefactory.web.views.shared.Formatters
import nl.vdzon.softwarefactory.web.views.shared.StoryStatusPresenter
import nl.vdzon.softwarefactory.web.views.shared.alerts
import nl.vdzon.softwarefactory.web.views.shared.badge
import nl.vdzon.softwarefactory.web.views.shared.e
import nl.vdzon.softwarefactory.web.views.shared.path
import nl.vdzon.softwarefactory.web.views.shared.section
import nl.vdzon.softwarefactory.web.views.shared.typeBadge
import kotlin.math.roundToInt

/** Story-/subtask-detailscherm: status, actiekaarten, acties-menu, subtaken en omschrijving. */
internal class StoryDetailView(
    private val chrome: DetailChrome,
    private val fmt: Formatters,
) {

    fun render(page: StoryDetailPageData): String =
        chrome.detailLayout(page, "Story Detail", autoRefreshSeconds = 5) {
            val isSubtask = page.parentKey != null
            alerts(page.errors) +
                chrome.backButton(page) +
                chrome.statusPanel(page) +
                subtaskErrorBanner(page) +
                humanActionTop(page) +
                actionsBar(page) +
                overviewDetails(page) +
                if (isSubtask) descriptionPanel(page) else (subtasksPanel(page) + descriptionPanel(page))
        }

    /** Prominente banner bovenaan de story als één of meer subtaken in error staan (story loopt vast). */
    private fun subtaskErrorBanner(page: StoryDetailPageData): String {
        if (page.parentKey != null) return "" // alleen op story-niveau
        val broken = page.subtasks.filter { StoryStatusPresenter.subtaskHasError(it) }
        if (broken.isEmpty()) return ""
        val title = if (broken.size == 1) "Een subtaak zit in error" else "${broken.size} subtaken zitten in error"
        val items = broken.joinToString("") { sub ->
            """
            <div class="se-item">
              <a class="se-key" href="/stories/${sub.key.path()}">${sub.key.e()} &middot; ${sub.summary.e()} &rarr;</a>
              <pre>${sub.fields.error?.e().orEmpty()}</pre>
            </div>
            """.trimIndent()
        }
        return """
        <section class="error-card">
          <div class="ac-head"><span class="ac-title">&#9888; ${title.e()}</span></div>
          <p class="ac-note">De story loopt hierdoor niet door. Open de subtaak om het op te lossen, of doe een re-implement.</p>
          $items
        </section>
        """.trimIndent()
    }

    /** Feedback-actiekaart bovenaan: directe actie op deze issue, of de actieve subtaak. */
    private fun humanActionTop(page: StoryDetailPageData): String {
        val issue = page.issue ?: return ""
        // Zoek het agent-resultaat in álle runs (story + subtaken), zodat de "Bekijk resultaat"-knop
        // ook werkt voor een subtaak die op het story-scherm gesurfacet wordt.
        val runs = page.allAgentRuns.ifEmpty { page.agentRuns }
        val own = when (issue.issueType) {
            IssueType.STORY -> ActionCards.storyActionCard(page.storyKey, issue, "actie nodig", page.agentQuestions[page.storyKey], runs)
            IssueType.SUBTASK -> ActionCards.subtaskActionCard(page.storyKey, issue, "actie nodig", page.agentQuestions[page.storyKey], runs = runs, prUrl = page.run?.prUrl)
        }
        if (own.isNotBlank()) return own
        if (issue.issueType == IssueType.STORY) {
            val active = page.subtasks.firstOrNull { ActionCards.subtaskAwaitsHuman(it) }
            if (active != null) {
                // Gesurfacet op het story-scherm: na de actie terug naar de story, niet naar de subtaak.
                return ActionCards.subtaskActionCard(
                    active.key,
                    active,
                    "Subtaak ${active.key} · actie nodig",
                    page.agentQuestions[active.key],
                    returnTo = "/stories/${page.storyKey.path()}",
                    runs = runs,
                    prUrl = page.run?.prUrl,
                )
            }
        }
        return ""
    }

    /** Eén kalm menu met alle commando's + links; klein, uitklapbaar budget ernaast. */
    private fun actionsBar(page: StoryDetailPageData): String {
        val key = page.storyKey
        return """
        <div class="bar-row">
          <details class="menu">
            <summary>Acties &amp; links <span class="chev">&#8964;</span></summary>
            <div class="pop">
              ${startRefiningItem(page)}
              ${startDevelopingItem(page)}
              <div class="grp">
                <span class="grp-label">Commando's</span>
                ${cmd(key, "pause", "Pause")}
                ${cmd(key, "clear-error", "Clear error")}
                ${cmd(key, "retry-current-step", "Retry current step")}
                ${cmd(key, "merge", "Merge")}
                ${cmd(key, "re-implement", "Re-implement")}
              </div>
              <div class="grp">
                <span class="grp-label">Links</span>
                <a href="${page.youTrackUrl.e()}" target="_blank" rel="noopener">YouTrack <span class="ext">&#8599;</span></a>
                ${page.run?.prUrl?.let { """<a href="${it.e()}" target="_blank" rel="noopener">PR #${page.run.prNumber} <span class="ext">&#8599;</span></a>""" } ?: ""}
                ${page.previewUrl?.let { """<a href="${it.e()}" target="_blank" rel="noopener">Test op preview <span class="ext">&#8599;</span></a>""" } ?: ""}
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
        """<form method="post" action="/stories/${storyKey.path()}/commands/$command"><button class="$kind" type="submit">${label.e()}</button></form>"""

    /**
     * Hard purge: verwijdert de hele story (issue + subtaken + branch + workfolder + run)
     * permanent. Extra waarschuwing via een JS-confirm voor het posten van het form.
     */
    private fun purgeButton(storyKey: String): String =
        """<form method="post" action="/stories/${storyKey.path()}/purge" onsubmit="return confirm('${storyKey.e()} volledig verwijderen?\n\nDit verwijdert de story én alle subtaken PERMANENT uit YouTrack, plus de branch, PR en workfolder. Dit kan niet ongedaan gemaakt worden.');"><button class="danger" type="submit">Verwijder story volledig (incl. subtaken)</button></form>"""

    private fun openWorkspaceItem(storyKey: String): String =
        """<form method="post" action="/stories/${storyKey.path()}/open-workspace"><button type="submit">Open in IntelliJ <span class="ext">&#8599;</span></button></form>"""

    /** "Start refining": zet de story-fase op `start`. Alleen op een story met nog lege Story Phase. */
    private fun startRefiningItem(page: StoryDetailPageData): String {
        val issue = page.issue ?: return ""
        if (issue.issueType != IssueType.STORY) return ""
        if (!issue.fields.storyPhase.isNullOrBlank()) return ""
        return """<form method="post" action="/stories/${page.storyKey.path()}/start-refining"><button class="primary" type="submit">&#9654; Start refining</button></form>"""
    }

    /** "Start developing": zet de eerste subtask op fase `start`. Alleen in planning-approved met nog niet-gestarte subtaken. */
    private fun startDevelopingItem(page: StoryDetailPageData): String {
        val issue = page.issue ?: return ""
        if (issue.issueType != IssueType.STORY) return ""
        if (StoryPhase.fromTracker(issue.fields.storyPhase) != StoryPhase.PLANNING_APPROVED) return ""
        if (page.subtasks.isEmpty() || page.subtasks.any { !it.fields.subtaskPhase.isNullOrBlank() }) return ""
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
                <div class="foot"><span>${fmt.tokens(used)} tokens gebruikt</span><span>geen limiet</span></div>
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
            <div class="foot"><span>${fmt.tokens(used)} / ${fmt.tokens(budget)} tokens</span><span>${fmt.tokens((budget - used).coerceAtLeast(0))} over</span></div>
          </div>
        </details>
        """.trimIndent()
    }

    private fun subtasksPanel(page: StoryDetailPageData): String {
        if (page.subtasks.isEmpty()) {
            return ""
        }
        return section("Subtaken") {
            page.subtasks.joinToString("") { sub ->
                val waiting = ActionCards.subtaskAwaitsHuman(sub)
                val done = StoryStatusPresenter.subtaskIsDone(sub)
                val hasError = StoryStatusPresenter.subtaskHasError(sub)
                val isActive = StoryStatusPresenter.subtaskIsActive(sub)
                val descPreview = descriptionPreview(sub.description, 5)
                val errorBadge = if (hasError) " ${badge("fout", "bad")}" else ""
                val activeBadge = if (isActive) " ${badge("bezig", "warn")}" else ""
                val statusBadge = when {
                    done -> " ${badge("klaar", "ok")}"
                    waiting -> " ${badge("actie nodig", "warn")}"
                    else -> ""
                }
                """
                <div class="sub${if (waiting) " needs" else ""}">
                  <span class="n">${sub.key.e()}</span>
                  <div class="body">
                    <div class="t"><a href="/stories/${sub.key.path()}">${sub.summary.e()}</a> ${typeBadge(sub)}$errorBadge$activeBadge$statusBadge</div>
                    <div class="d">${sub.fields.subtaskType?.e()?.let { "$it &middot; " } ?: ""}fase: ${sub.fields.subtaskPhase?.e() ?: "—"}</div>
                    ${if (descPreview.isNotBlank()) "<div class=\"desc-preview\">$descPreview</div>" else ""}
                  </div>
                  <span class="ph">${sub.fields.subtaskPhase?.e() ?: "—"}</span>
                  <a class="go" href="/stories/${sub.key.path()}">&rarr;</a>
                </div>
                """.trimIndent()
            }
        }
    }

    /** Toont de volledige description van een (sub)taak onder de eigenschappen. */
    private fun descriptionPanel(page: StoryDetailPageData): String {
        val description = page.issue?.description?.takeIf { it.isNotBlank() } ?: return ""
        return section("Omschrijving") {
            """<div class="desc-full">${description.e().replace("\n", "<br>")}</div>"""
        }
    }

    /** Eerste [maxLines] niet-lege regels van een description, HTML-escaped en met <br> gescheiden. */
    private fun descriptionPreview(description: String?, maxLines: Int): String {
        val lines = description?.trim()?.lines().orEmpty().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        val shown = lines.take(maxLines).joinToString("<br>") { it.trim().e() }
        return if (lines.size > maxLines) "$shown<br>&hellip;" else shown
    }

    private fun overviewDetails(page: StoryDetailPageData): String {
        val issue = page.issue
        val run = page.run
        val autoApproveStatus = if (issue?.fields?.autoApprove == true) "aan" else "uit"
        val autoApproveButtons = if (issue != null) {
            """
            <div><span>Auto-approve</span><strong>$autoApproveStatus</strong>
              ${if (issue.fields.autoApprove != true) """<form method="post" action="/stories/${page.storyKey.path()}/set-auto-approve/on" style="display:inline;"><button class="button sm" type="submit">Aanzetten</button></form>""" else ""}
              ${if (issue.fields.autoApprove != false) """<form method="post" action="/stories/${page.storyKey.path()}/set-auto-approve/off" style="display:inline;"><button class="button sm" type="submit">Uitzetten</button></form>""" else ""}
            </div>
            """.trimIndent()
        } else {
            ""
        }
        return """
        <div class="rule"></div>
        <details class="props">
          <summary>Eigenschappen <span class="chev">&#8964;</span></summary>
          <div class="key-value">
            <div><span>Gestart</span><strong>${fmt.date(run?.startedAt)}</strong></div>
            <div><span>Geeindigd</span><strong>${fmt.date(run?.endedAt)}</strong></div>
            <div><span>Final status</span><strong>${run?.finalStatus?.e() ?: "lopend"}</strong></div>
            <div><span>Repo-veld</span><strong>${issue?.fields?.repo?.e() ?: "-"}</strong></div>
            <div><span>Target repo</span><strong>${issue?.fields?.targetRepo?.e() ?: run?.targetRepo?.e() ?: "-"}</strong></div>
            <div><span>Repo folder</span><strong>${run?.workspacePath?.takeIf { it.isNotBlank() }?.let { repoFolder(it).e() } ?: "-"}</strong></div>
            <div><span>AI supplier</span><strong>${issue?.fields?.aiSupplier?.e() ?: "-"}</strong></div>
            <div><span>AI level</span><strong>${issue?.fields?.aiLevel?.toString()?.e() ?: "-"}</strong></div>
            $autoApproveButtons
            <div><span>Aantal agent-runs</span><strong>${page.agentRuns.size}</strong></div>
            <div><span>Input tokens</span><strong>${fmt.tokens(run?.totalInputTokens ?: 0)}</strong></div>
            <div><span>Output tokens</span><strong>${fmt.tokens(run?.totalOutputTokens ?: 0)}</strong></div>
            <div><span>Cache-read tokens</span><strong>${fmt.tokens(run?.totalCacheReadTokens ?: 0)}</strong></div>
            <div><span>Cache-creation tokens</span><strong>${fmt.tokens(run?.totalCacheCreationTokens ?: 0)}</strong></div>
            <div><span>Geschatte kosten</span><strong>${fmt.money(run?.totalCostUsdEst ?: 0.0)}</strong></div>
          </div>
        </details>
        """.trimIndent()
    }

    private fun repoFolder(workspacePath: String): String =
        workspacePath.trimEnd('/', '\\') + "/repo"
}
