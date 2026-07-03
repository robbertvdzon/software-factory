package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.web.models.AgentsPageData
import nl.vdzon.softwarefactory.web.views.shared.HtmlLayout
import nl.vdzon.softwarefactory.web.views.shared.ListComponents
import nl.vdzon.softwarefactory.web.views.shared.alerts
import nl.vdzon.softwarefactory.web.views.shared.empty
import nl.vdzon.softwarefactory.web.views.shared.section

/** Agents-pagina: lopende factory-agents en recente sessies. */
internal class AgentsView(
    private val layout: HtmlLayout,
    private val lists: ListComponents,
) {

    fun render(page: AgentsPageData): String =
        layout.layout("agents", "Agents", "Lopende factory-agents en recente sessies") {
            alerts(page.errors) +
                section("Factory agents") {
                    if (page.activeAgentRuns.isEmpty()) {
                        empty("Geen actieve agent-runs.")
                    } else {
                        lists.agentRunRows(page.activeAgentRuns)
                    }
                } +
                section("Recente agent-runs") {
                    lists.agentRunRows(page.recentAgentRuns)
                }
        }
}
