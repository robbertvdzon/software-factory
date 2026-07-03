package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.web.models.ProjectsPageData
import nl.vdzon.softwarefactory.web.views.shared.Formatters
import nl.vdzon.softwarefactory.web.views.shared.HtmlLayout
import nl.vdzon.softwarefactory.web.views.shared.alerts
import nl.vdzon.softwarefactory.web.views.shared.e
import nl.vdzon.softwarefactory.web.views.shared.empty
import nl.vdzon.softwarefactory.web.views.shared.path

/** Projects-pagina: per project de repo, prd-versie, story-tellers en force-deploy. */
internal class ProjectsView(
    private val layout: HtmlLayout,
    private val fmt: Formatters,
) {

    fun render(page: ProjectsPageData): String =
        layout.layout("projects", "Projects", "Overzicht van geconfigureerde projecten") {
            alerts(page.errors) +
                if (page.projects.isEmpty()) {
                    empty("Geen projecten geconfigureerd")
                } else {
                    page.projects.joinToString("") { project ->
                        val prdVersionText = when {
                            !project.hasDeployConfig -> "geen deploy-config"
                            project.prdVersion == null -> "ophalen mislukt"
                            else -> "${project.prdVersion.commitShort.e()} &middot; ${project.prdVersion.branch.e()} &middot; ${project.prdVersion.commitDate.e()}"
                        }
                        val deployButton = if (project.hasDeployConfig) {
                            """
                            <form method="post" action="/projects/${project.name.path()}/force-deploy" style="margin-top:8px">
                              <button class="button" type="submit">&#8635; Force deploy</button>
                            </form>
                            """.trimIndent()
                        } else ""
                        """
                        <section>
                          <h2 class="section-title">${project.name.e()}</h2>
                          <div class="key-value one">
                            <div><span>Repo</span><strong>${project.repoUrl.e()}</strong></div>
                            <div><span>Productieversie</span><strong>$prdVersionText</strong></div>
                          </div>
                          <div class="metric-grid">
                            <div><span>Todo</span><strong>${project.storiesTodo}</strong></div>
                            <div><span>In progress</span><strong>${project.storiesInProgress}</strong></div>
                            <div><span>Done</span><strong>${project.storiesDone}</strong></div>
                            <div><span>Kosten</span><strong>${fmt.money(project.totalCostUsd)}</strong></div>
                            <div><span>Actieve agents</span><strong>${project.activeAgentCount}</strong></div>
                          </div>
                          $deployButton
                        </section>
                        """.trimIndent()
                    }
                }
        }
}
