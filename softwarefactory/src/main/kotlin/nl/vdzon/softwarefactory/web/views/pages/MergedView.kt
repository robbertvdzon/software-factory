package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.web.models.MergedPageData
import nl.vdzon.softwarefactory.web.views.shared.Formatters
import nl.vdzon.softwarefactory.web.views.shared.HtmlLayout
import nl.vdzon.softwarefactory.web.views.shared.alerts
import nl.vdzon.softwarefactory.web.views.shared.badge
import nl.vdzon.softwarefactory.web.views.shared.e
import nl.vdzon.softwarefactory.web.views.shared.empty
import nl.vdzon.softwarefactory.web.views.shared.path

/** Recent merged: PR's die naar main zijn gemerged. */
internal class MergedView(
    private val layout: HtmlLayout,
    private val fmt: Formatters,
) {

    fun render(page: MergedPageData): String =
        layout.layout("merged", "Recent merged", "PR's die naar main zijn gemerged") {
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
                          <span class="num">${fmt.relative(run.endedAt)}</span>
                          <span class="num">${fmt.tokens(run.totalTokens)}</span>
                          <span class="num">${fmt.money(run.totalCostUsdEst)}</span>
                          <span class="go">&rarr;</span>
                        </a>
                        """.trimIndent()
                      }}
                    </section>
                    """.trimIndent()
                }
        }
}
