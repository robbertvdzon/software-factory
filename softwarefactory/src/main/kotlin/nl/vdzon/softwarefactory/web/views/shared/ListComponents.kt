package nl.vdzon.softwarefactory.web.views.shared

import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.web.models.UiStoryRun
import nl.vdzon.softwarefactory.web.views.FactoryDashboardViews.StatusBucket

/**
 * Gedeelde lijst-weergaven: de story-tabel (dashboard + stories), de recente story-runs
 * (dashboard) en de agent-run-rijen (agents). Als class omdat de kolommen [Formatters]
 * (en dus de Clock) nodig hebben.
 */
internal class ListComponents(private val fmt: Formatters) {

    fun issueTable(
        issues: List<TrackerIssue>,
        runsByStory: Map<String, UiStoryRun>,
        limit: Int,
        mergedKeys: Set<String> = emptySet(),
        bucketOf: ((TrackerIssue) -> StatusBucket)? = null,
    ): String {
        val visible = issues.take(limit)
        if (visible.isEmpty()) {
            return empty("Geen stories in Develop met een actieve AI-supplier.")
        }
        return """
        <section class="list stories">
          <div class="lhead"><span>Story</span><span>Project</span><span>Fase</span><span>Tokens</span><span>Kosten</span><span></span></div>
          ${visible.joinToString("") { issue ->
            val run = runsByStory[issue.key]
            val budget = issue.fields.aiTokenBudget ?: 40_000L
            val used = listOf(issue.fields.aiTokensUsed ?: 0L, run?.totalTokens ?: 0L).max()
            val bucketAttr = bucketOf?.let { " data-bucket=\"${it(issue).attr}\"" } ?: ""
            // Voor stories tonen we de afgeleide "echte" status (Todo/In progress/Done/…); subtaken
            // houden hun eigen fase. Subtaak-info hebben we hier niet, dus Done/Fout leunt op de lane.
            val phaseCell = if (issue.issueType == IssueType.STORY) {
                val st = StoryStatusPresenter.realStatus(issue, emptyList(), merged = issue.key in mergedKeys)
                badge(st.label, st.kind)
            } else {
                issue.displayPhase()?.e() ?: "—"
            }
            val repoFull = (run?.targetRepo ?: issue.fields.repo).orEmpty()
            """
            <a class="lrow"$bucketAttr href="/stories/${issue.key.path()}">
              <span class="k">${issue.key.e()} ${typeBadge(issue)}<span class="desc">${issue.summary.e()}</span></span>
              <span class="proj" title="${repoFull.e()}">${storyRepoLabel(issue, run).e()}</span>
              <span class="num">$phaseCell</span>
              <span class="num">${fmt.tokens(used)} / ${fmt.tokens(budget)}</span>
              <span class="num">${fmt.money(run?.totalCostUsdEst ?: 0.0)}</span>
              <span class="go">&rarr;</span>
            </a>
            """.trimIndent()
          }}
        </section>
        """.trimIndent()
    }

    fun storyRunList(runs: List<UiStoryRun>): String {
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
              <span class="num">${fmt.tokens(run.totalTokens)}</span>
              <span class="num">${fmt.money(run.totalCostUsdEst)}</span>
              <span class="num nowrap">${fmt.relative(run.startedAt)}</span>
              <span class="go">&rarr;</span>
            </a>
            """.trimIndent()
          }}
        </section>
        """.trimIndent()
    }

    fun agentRunRows(runs: List<UiAgentRun>): String {
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
              <span class="k">${run.storyKey.e()}<span class="desc">${run.role.e()} (${iterations[run.id] ?: "1/1"}) &middot; ${run.containerName.e()}<br>Gestart ${fmt.timestamp(run.startedAt)}${run.endedAt?.let { " &middot; klaar ${fmt.timestamp(it)}" } ?: ""}</span></span>
              <span>${badge(outcome.label, outcome.kind)}</span>
              <span class="num">${fmt.tokens(run.totalTokens)}</span>
              <span class="num nowrap">${fmt.duration(run.durationMs)}</span>
              <span class="num">${fmt.money(run.costUsdEst)}</span>
              <span class="go">&rarr;</span>
            </a>
            """.trimIndent()
          }}
        </section>
        """.trimIndent()
    }

    /**
     * Korte projectlabel voor in de stories-lijst. Voorkeur voor het `Repo`-veld van de story zelf
     * (meestal de projectnaam uit projects.yaml, ook aanwezig vóór de eerste run); valt anders terug
     * op de target-repo van de run. Een repo-URL wordt verkort tot het laatste pad-segment (zonder `.git`).
     */
    private fun storyRepoLabel(issue: TrackerIssue, run: UiStoryRun?): String {
        val raw = issue.fields.repo?.takeIf { it.isNotBlank() }
            ?: run?.targetRepo?.takeIf { it.isNotBlank() }
            ?: return "—"
        return raw.trim().trimEnd('/', '\\').substringAfterLast('/').removeSuffix(".git")
            .ifBlank { raw.trim() }
    }
}
