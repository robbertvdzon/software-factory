package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.web.models.DashboardPageData
import nl.vdzon.softwarefactory.web.views.shared.HtmlLayout
import nl.vdzon.softwarefactory.web.views.shared.ListComponents
import nl.vdzon.softwarefactory.web.views.shared.alerts
import nl.vdzon.softwarefactory.web.views.shared.metricGrid
import nl.vdzon.softwarefactory.web.views.shared.section

/** Dashboard: metriek-overzicht + actieve stories + recente runs. */
internal class DashboardView(
    private val layout: HtmlLayout,
    private val lists: ListComponents,
) {

    fun render(page: DashboardPageData): String =
        layout.layout("dashboard", "Dashboard", "Overzicht van factory-runs en actieve stories") {
            alerts(page.errors) +
                """<p class="label">Productie</p>""" +
                metricGrid(
                    "Actieve stories" to page.issues.size.toString(),
                    "Lopende runs" to page.activeAgentRuns.size.toString(),
                    "Open story-runs" to page.activeRuns.size.toString(),
                    "Laatste run" to (page.recentRuns.firstOrNull()?.storyKey ?: "-"),
                ) +
                section("Stories in beheer van AI") {
                    lists.issueTable(page.issues, page.activeRuns.associateBy { it.storyKey }, limit = 8)
                } +
                section("Recente runs") {
                    lists.storyRunList(page.recentRuns)
                }
        }
}
