package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.core.AiRouting
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.web.models.StoriesPageData
import nl.vdzon.softwarefactory.web.views.shared.HtmlLayout
import nl.vdzon.softwarefactory.web.views.shared.ListComponents
import nl.vdzon.softwarefactory.web.views.shared.StoryStatusPresenter
import nl.vdzon.softwarefactory.web.views.shared.alerts
import nl.vdzon.softwarefactory.web.views.shared.e

/** Stories-overzicht: nieuwe-story-formulier, bucket-filter en de story-tabel. */
internal class StoriesView(
    private val layout: HtmlLayout,
    private val lists: ListComponents,
) {

    fun render(page: StoriesPageData): String =
        layout.layout("stories", "Stories", "Stories die de AI op dit moment behandelt") {
            // Toon op het stories-overzicht alleen echte stories; subtaken weren we hier
            // zonder de gedeelde issueTable (ook door het dashboard gebruikt) aan te passen.
            val onlyStories = page.issues.filter { it.issueType == IssueType.STORY }
            alerts(page.errors) + newStoryForm(page) + storyFilterBar() +
                lists.issueTable(
                    onlyStories,
                    page.runsByStory,
                    limit = Int.MAX_VALUE,
                    mergedKeys = page.mergedStoryKeys,
                ) { StoryStatusPresenter.classifyStatus(it.status) } +
                // Toggle-script staat als static (`stories.js`); rijen tonen/verbergen per bucket.
                layout.scriptTag("/stories.js")
        }

    /**
     * Checkbox-balk boven de stories-lijst. Standaard alle drie aangevinkt, zodat de
     * volledige lijst (minus subtaken) zichtbaar is. Filtering gebeurt client-side.
     */
    private fun storyFilterBar(): String =
        """
        <div class="story-filter" data-story-filter>
          <label><input type="checkbox" data-bucket-toggle="finished" checked> Show finished stories</label>
          <label><input type="checkbox" data-bucket-toggle="in-progress" checked> Show stories in progress</label>
          <label><input type="checkbox" data-bucket-toggle="todo" checked> Show stories in TODO</label>
        </div>
        """.trimIndent()

    /** Inklapbaar formulier om vanaf het dashboard een nieuwe story aan te maken. */
    private fun newStoryForm(page: StoriesPageData): String {
        val projectOptions = page.projects.joinToString("") {
            """<option value="${it.key.e()}">${it.key.e()} — ${it.name.e()}</option>"""
        }
        val repoOptions = """<option value="">— geen —</option>""" +
            page.repoNames.joinToString("") { """<option value="${it.e()}">${it.e()}</option>""" }
        val supplierOptions = AI_SUPPLIER_OPTIONS
            .joinToString("") { """<option value="$it"${if (it == "claude") " selected" else ""}>$it</option>""" }
        val modelOptions = """<option value="">— automatisch (op AI-niveau) —</option>""" +
            AI_MODELS_BY_SUPPLIER.entries.joinToString("") { (supplier, models) ->
                models.joinToString("") { """<option value="${it.e()}" data-supplier="${supplier.e()}">${it.e()}</option>""" }
            }
        return """
        <details class="new-story">
          <summary>&#43; Nieuwe story</summary>
          <form method="post" action="/stories/create" class="story-form">
            <label>Titel
              <input type="text" name="title" required placeholder="Korte titel van de story">
            </label>
            <label>Omschrijving
              <textarea name="description" rows="4" placeholder="Wat moet er gebeuren?"></textarea>
            </label>
            <label>Project
              <select name="project" required>$projectOptions</select>
            </label>
            <label>Repo
              <select name="repo">$repoOptions</select>
            </label>
            <label>AI-supplier
              <select id="nsf-supplier" name="aiSupplier">$supplierOptions</select>
            </label>
            <label>AI-model
              <select id="nsf-model" name="aiModel">$modelOptions</select>
            </label>
            <label class="check"><input type="checkbox" name="autoApprove" value="on"> Auto-approve aanzetten</label>
            <label class="check"><input type="checkbox" name="start" value="on"> Direct starten (fase = start)</label>
            <div class="button-row">
              <button class="button primary" type="submit">Story aanmaken</button>
            </div>
          </form>
        </details>
        ${layout.scriptTag("/new-story.js")}
        """.trimIndent()
    }

    private companion object {
        /**
         * Geldige AI-modellen PER supplier (bron: [AiRouting.MODELS_BY_SUPPLIER]); het formulier
         * filtert het model-dropdown op de gekozen supplier (zie `new-story.js`). Suppliers zonder
         * eigen model-override (microsoft/none) staan hier niet: die krijgen alleen "automatisch".
         */
        private val AI_MODELS_BY_SUPPLIER: Map<String, List<String>> = AiRouting.MODELS_BY_SUPPLIER

        /** Selecteerbare AI-suppliers (bron: YouTrackClient AI-supplier FieldSpec). */
        private val AI_SUPPLIER_OPTIONS = listOf("none", "mock", "claude", "openai", "copilot", "microsoft")
    }
}
