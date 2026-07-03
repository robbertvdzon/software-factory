package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.web.models.MyActionItem
import nl.vdzon.softwarefactory.web.models.MyActionsPageData
import nl.vdzon.softwarefactory.web.models.MyActionsStoryGroup
import nl.vdzon.softwarefactory.web.views.shared.ActionCards
import nl.vdzon.softwarefactory.web.views.shared.HtmlLayout
import nl.vdzon.softwarefactory.web.views.shared.alerts
import nl.vdzon.softwarefactory.web.views.shared.e
import nl.vdzon.softwarefactory.web.views.shared.empty
import nl.vdzon.softwarefactory.web.views.shared.path

/** "My actions"-inbox: alles wat op de mens wacht, over alle stories, gegroepeerd per story. */
internal class MyActionsView(private val layout: HtmlLayout) {

    fun render(page: MyActionsPageData): String =
        layout.layout("my-actions", "My actions", "Alles wat op jou wacht — over alle stories", autoRefreshSeconds = 5) {
            alerts(page.errors) +
                if (page.groups.isEmpty()) {
                    empty("Geen openstaande acties — niks dat op je wacht.")
                } else {
                    page.groups.joinToString("") { myActionsGroup(it) }
                }
        }

    private fun myActionsGroup(group: MyActionsStoryGroup): String =
        """
        <section class="ma-group">
          <div class="ma-head">
            <a class="ma-story" href="/stories/${group.storyKey.path()}">${group.storyKey.e()} &middot; ${group.storySummary.e()}</a>
            <span class="ma-tools">
              <a class="button sm" href="/stories/${group.storyKey.path()}" target="_blank" rel="noopener">Open story &#8599;</a>
              <form method="post" action="/stories/${group.storyKey.path()}/open-workspace"><button class="button sm" type="submit">IntelliJ &#8599;</button></form>
            </span>
          </div>
          ${group.items.joinToString("") { myActionItem(group, it) }}
        </section>
        """.trimIndent()

    private fun myActionItem(group: MyActionsStoryGroup, item: MyActionItem): String {
        val issue = item.issue
        // Platte tekst; de kaart-functies escapen zelf bij interpolatie.
        val context = if (item.isSubtask) {
            "Subtaak ${issue.key}${issue.fields.subtaskType?.let { " · $it" } ?: ""}"
        } else {
            "Story ${issue.key}"
        }
        // Na de actie terug naar de inbox. Een issue in error toont z'n foutmelding i.p.v. een
        // actie-kaart (de fase-kaarten matchen niet op een error-status).
        val error = issue.fields.error?.takeIf { it.isNotBlank() }
        val card = when {
            error != null -> ActionCards.myActionErrorCard(context, error)
            item.isSubtask -> ActionCards.subtaskActionCard(issue.key, issue, context, item.question, returnTo = "/my-actions", runs = group.runs, prUrl = group.prUrl)
            else -> ActionCards.storyActionCard(group.storyKey, issue, context, item.question, group.runs, returnTo = "/my-actions")
        }
        // Subtaak: ook een directe link naar het subtaak-scherm (nieuwe tab).
        val openSubtask = if (item.isSubtask) {
            """<div class="ma-item-tools"><a class="button sm" href="/stories/${issue.key.path()}" target="_blank" rel="noopener">Open subtaak &#8599;</a></div>"""
        } else {
            ""
        }
        return """<div class="ma-item">$openSubtask$card</div>"""
    }
}
