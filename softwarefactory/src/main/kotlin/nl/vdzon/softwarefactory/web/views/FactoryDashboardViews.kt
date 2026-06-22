package nl.vdzon.softwarefactory.web.views

import nl.vdzon.softwarefactory.web.models.AgentsPageData
import nl.vdzon.softwarefactory.web.models.DashboardPageData
import nl.vdzon.softwarefactory.web.models.MergedPageData
import nl.vdzon.softwarefactory.web.models.MyActionItem
import nl.vdzon.softwarefactory.web.models.MyActionsPageData
import nl.vdzon.softwarefactory.web.models.MyActionsStoryGroup
import nl.vdzon.softwarefactory.web.models.SettingsPageData
import nl.vdzon.softwarefactory.web.models.StoriesPageData
import nl.vdzon.softwarefactory.web.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.web.models.UiBriefingAgentRun
import nl.vdzon.softwarefactory.web.models.UiBriefingItem
import nl.vdzon.softwarefactory.web.models.UiBriefingUserComment
import nl.vdzon.softwarefactory.web.models.UiStoryRun
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.TrackerIssue
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
    /**
     * Cache-bust voor de stylesheet: een korte hash van de actuele `sf-ui.css`-inhoud, als `?v=`-query
     * op de `<link>`. Wijzigt de CSS, dan wijzigt de URL en haalt de browser 'm opnieuw op — geen stale cache.
     */
    private val cssLink: String by lazy {
        val version = runCatching {
            javaClass.getResourceAsStream("/static/sf-ui.css")?.use { input ->
                Integer.toHexString(input.readBytes().contentHashCode())
            }
        }.getOrNull() ?: "1"
        """<link rel="stylesheet" href="/sf-ui.css?v=$version">"""
    }

    fun login(error: Boolean = false, next: String = "/dashboard"): String =
        """
        <!doctype html>
        <html lang="nl">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          $FAVICON
          <title>Login - Software Factory</title>
          $cssLink
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
            // Toon op het stories-overzicht alleen echte stories; subtaken weren we hier
            // zonder de gedeelde issueTable (ook door het dashboard gebruikt) aan te passen.
            val onlyStories = page.issues.filter { it.issueType == IssueType.STORY }
            alerts(page.errors) + newStoryForm(page) + storyFilterBar() +
                issueTable(
                    onlyStories,
                    page.runsByStory,
                    limit = Int.MAX_VALUE,
                    mergedKeys = page.mergedStoryKeys,
                ) { classifyStatus(it.status) } +
                storyFilterScript()
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

    /** Inline JS-toggle die story-rijen toont/verbergt op basis van de aangevinkte buckets. */
    private fun storyFilterScript(): String =
        """
        <script>
        (function () {
          var bar = document.querySelector('[data-story-filter]');
          if (!bar) return;
          var rows = Array.prototype.slice.call(document.querySelectorAll('.list.stories .lrow[data-bucket]'));
          var STORAGE_KEY = 'storyPageFilters';

          function getCheckboxes() {
            var checkboxes = {};
            bar.querySelectorAll('[data-bucket-toggle]').forEach(function (cb) {
              checkboxes[cb.getAttribute('data-bucket-toggle')] = cb.checked;
            });
            return checkboxes;
          }

          function setCheckboxes(checkboxes) {
            bar.querySelectorAll('[data-bucket-toggle]').forEach(function (cb) {
              var key = cb.getAttribute('data-bucket-toggle');
              if (key in checkboxes) {
                cb.checked = checkboxes[key];
              }
            });
          }

          function loadStoredState() {
            try {
              var stored = localStorage.getItem(STORAGE_KEY);
              if (stored) {
                var parsed = JSON.parse(stored);
                setCheckboxes(parsed);
              }
            } catch (e) {
            }
          }

          function saveState() {
            try {
              var state = getCheckboxes();
              localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
            } catch (e) {
            }
          }

          function apply() {
            var on = getCheckboxes();
            rows.forEach(function (row) {
              row.style.display = on[row.getAttribute('data-bucket')] ? '' : 'none';
            });
          }

          loadStoredState();
          apply();
          bar.addEventListener('change', function () {
            apply();
            saveState();
          });
        })();
        </script>
        """.trimIndent()

    /** Status-buckets voor het classificeren van de YouTrack board-lane (`State`-veld). */
    enum class StatusBucket(val attr: String) {
        FINISHED("finished"), IN_PROGRESS("in-progress"), TODO("todo")
    }

    /**
     * Classificeert de board-lane (`State`-veld) van een story case-insensitive in een bucket.
     * De factory zet die lane zelf: `In Progress` bij de eerste agent, `Done` als alle subtaken
     * klaar zijn (zie transitionIssue + SubtaskExecutionCoordinator). `To Verify` telt als nog
     * bezig. Onbekende/lege statussen → [StatusBucket.TODO].
     * `internal` zodat het in unit-tests aanroepbaar is, maar buiten de module verborgen blijft.
     */
    internal fun classifyStatus(status: String?): StatusBucket {
        val normalized = status?.trim()?.lowercase() ?: return StatusBucket.TODO
        return when (normalized) {
            "done", "fixed", "verified", "closed", "resolved" -> StatusBucket.FINISHED
            "in progress", "to verify", "develop", "developing" -> StatusBucket.IN_PROGRESS
            "open", "submitted", "backlog", "to do" -> StatusBucket.TODO
            else -> StatusBucket.TODO
        }
    }

    /** Afgeleide, leesbare "echte" status van een story + bijbehorende badge-kind. */
    data class StoryStatusView(val label: String, val kind: String)

    /** Story-fasen die tot het refinement- resp. planning-deel van de lifecycle horen. */
    private val refiningPhases = setOf(
        StoryPhase.REFINING, StoryPhase.REFINED_WITH_QUESTIONS, StoryPhase.QUESTIONS_ANSWERED,
        StoryPhase.REFINED, StoryPhase.REFINED_REJECTED, StoryPhase.REFINED_APPROVED,
    )
    private val planningPhases = setOf(
        StoryPhase.PLANNING, StoryPhase.PLANNED_WITH_QUESTIONS, StoryPhase.PLANNING_QUESTIONS_ANSWERED,
        StoryPhase.PLANNED, StoryPhase.PLANNING_REJECTED,
    )

    /**
     * De "echte" status van een STORY, voorbij het platte `planning-approved`. Combineert de
     * refinement-fase, de YouTrack `State`-lane (die de factory zelf op In Progress/Done zet) en —
     * waar beschikbaar — de subtaken en de merged-vlag tot één leesbaar label:
     * Merged · Fout · Gepauzeerd · Done · Todo · Refining · Planning · In progress.
     * [subtasks] mag leeg zijn (overzicht); dan leunt Done/Fout op de lane resp. story-error.
     * `internal` zodat unit-tests het kunnen aanroepen.
     */
    internal fun realStatus(issue: TrackerIssue, subtasks: List<TrackerIssue>, merged: Boolean): StoryStatusView {
        val phase = StoryPhase.fromTracker(issue.fields.storyPhase)
        val laneDone = classifyStatus(issue.status) == StatusBucket.FINISHED
        val done = laneDone || (subtasks.isNotEmpty() && subtasks.all { subtaskIsDone(it) })
        val hasError = !issue.fields.error.isNullOrBlank() || subtasks.any { !it.fields.error.isNullOrBlank() }
        return when {
            merged -> StoryStatusView("Merged", "ok")
            hasError -> StoryStatusView("Fout", "bad")
            issue.fields.paused -> StoryStatusView("Gepauzeerd", "warn")
            done -> StoryStatusView("Done", "ok")
            phase == null || phase == StoryPhase.START -> StoryStatusView("Todo", "neutral")
            phase in refiningPhases -> StoryStatusView("Refining", "info")
            phase in planningPhases -> StoryStatusView("Planning", "info")
            phase == StoryPhase.IN_PROGRESS -> StoryStatusView("In progress", "info")
            // Planning goedgekeurd maar nog niets opgepakt → wacht op de "Start developing"-knop.
            // Zodra dat gebeurt zet de orchestrator de fase op `in-progress` (zie startDeveloping),
            // dus hier hoeven we de subtaken niet te kennen — werkt ook in het overzicht. De
            // subtaak-check blijft als vangnet voor stories van vóór deze wijziging.
            phase == StoryPhase.PLANNING_APPROVED -> {
                val started = subtasks.any { !it.fields.subtaskPhase.isNullOrBlank() }
                if (started) StoryStatusView("In progress", "info")
                else StoryStatusView("Klaar om te starten", "warn")
            }
            else -> StoryStatusView("In progress", "info")
        }
    }

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
        $NEW_STORY_MODEL_FILTER_SCRIPT
        """.trimIndent()
    }

    fun storyDetail(page: StoryDetailPageData): String =
        detailLayout(page, "Story Detail", autoRefreshSeconds = 5) {
            val isSubtask = page.parentKey != null
            alerts(page.errors) +
                backButton(page) +
                statusPanel(page) +
                subtaskErrorBanner(page) +
                humanActionTop(page) +
                actionsBar(page) +
                overviewDetails(page) +
                if (isSubtask) descriptionPanel(page) else (subtasksPanel(page) + descriptionPanel(page))
        }

    /** Prominente banner bovenaan de story als één of meer subtaken in error staan (story loopt vast). */
    private fun subtaskErrorBanner(page: StoryDetailPageData): String {
        if (page.parentKey != null) return "" // alleen op story-niveau
        val broken = page.subtasks.filter { subtaskHasError(it) }
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

    fun briefing(page: StoryDetailPageData): String =
        detailLayout(page, "Briefing", autoRefreshSeconds = 5) {
            // Story-briefing toont story + alle subtaak-runs samen; subtaak-briefing alleen die subtaak.
            val isSubtask = page.parentKey != null
            val source = if (isSubtask) page.agentRuns else page.allAgentRuns
            val agentRuns = source.sortedByNewestRun()
            val runIterations = agentRunIterationLabels(agentRuns)
            // Bron-label per kaart: story-key of subtaak-key (+ titel indien bekend).
            val subtaskTitles = page.subtasks.associate { it.key to it.summary }

            // Build briefing items from agent-runs and user comments, sorted chronologically
            val briefingItems = mutableListOf<UiBriefingItem>()
            agentRuns.forEach { run ->
                briefingItems.add(UiBriefingAgentRun(run))
            }
            // Add user comments (non-agent comments) from the issue
            page.issue?.comments?.forEach { comment ->
                if (!comment.isAgentComment && comment.created != null) {
                    briefingItems.add(UiBriefingUserComment(
                        id = comment.id,
                        authorName = comment.authorDisplayName,
                        body = comment.body,
                        created = comment.created,
                    ))
                }
            }
            // Add user comments from subtasks if viewing story-briefing
            if (!isSubtask) {
                page.subtasks.forEach { subtask ->
                    subtask.comments.forEach { comment ->
                        if (!comment.isAgentComment && comment.created != null) {
                            briefingItems.add(UiBriefingUserComment(
                                id = comment.id,
                                authorName = comment.authorDisplayName,
                                body = comment.body,
                                created = comment.created,
                            ))
                        }
                    }
                }
            }

            val sortedItems = briefingItems.sortedByDescending { it.timestamp }

            alerts(page.errors) +
                statusPanel(page) +
                backLink(page.storyKey) +
                section("Agent-run samenvattingen en gebruikers-antwoorden") {
                    if (sortedItems.isEmpty()) {
                        empty("Nog geen agent-runs of gebruikers-antwoorden gevonden.")
                    } else {
                        sortedItems.joinToString("") { item ->
                            when (item) {
                                is UiBriefingAgentRun -> {
                                    val run = item.agentRun
                                    val outcome = outcomePresentation(run)
                                    val iteration = runIterations[run.id] ?: "1/1"
                                    """
                                    <article class="brief-card agent-run">
                                      <div class="brief-head">
                                        <span class="icon-tile">${run.role.take(3).uppercase()}</span>
                                        <div>
                                          ${briefingSourceBadge(run, page.storyKey, subtaskTitles)}
                                          <strong>${run.role.e()} ($iteration)</strong> ${badge(outcome.label, outcome.kind)}<br>
                                          <span class="muted">Gestart ${timestamp(run.startedAt)} - ${relative(run.startedAt)}${run.endedAt?.let { " · klaar ${timestamp(it)} (${durationMmSs(Duration.between(run.startedAt, it).toMillis())})" } ?: ""}</span>
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
                                is UiBriefingUserComment -> {
                                    """
                                    <article class="brief-card user-comment">
                                      <div class="brief-head">
                                        <span class="icon-tile">USR</span>
                                        <div>
                                          <span class="brief-source user">Gebruiker</span><br>
                                          <strong>${item.authorName?.e() ?: "Anoniem".e()}</strong><br>
                                          <span class="muted">${timestamp(item.created)} - ${relative(item.created)}</span>
                                        </div>
                                      </div>
                                      <pre>${item.body.e()}</pre>
                                    </article>
                                    """.trimIndent()
                                }
                            }
                        }
                    }
                }
        }

    /** Label dat aangeeft of een briefing-kaart bij de story of een subtaak hoort. */
    private fun briefingSourceBadge(run: UiAgentRun, storyKey: String, subtaskTitles: Map<String, String>): String {
        val subtaskKey = run.subtaskKey
        return if (subtaskKey == null) {
            """<span class="brief-source story">Story &middot; ${storyKey.e()}</span><br>"""
        } else {
            val title = subtaskTitles[subtaskKey]?.let { " &middot; ${it.e()}" } ?: ""
            """<span class="brief-source subtask">Subtaak &middot; ${subtaskKey.e()}$title</span><br>"""
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
                section("Versie & start") {
                    val v = page.version
                    val dirtyBadge = if (v.dirty) " ${badge("ongecommitte changes", "warn")}" else ""
                    """
                    <div class="key-value one">
                      <div><span>Gestart</span><strong>${timestamp(v.startedAt).e()} &middot; ${relative(v.startedAt).e()}</strong></div>
                      <div><span>Commit</span><strong>${v.commitShort.e()} &mdash; ${v.commitSubject.e()}</strong></div>
                      <div><span>Commit-datum</span><strong>${v.commitDate.e()}</strong></div>
                      <div><span>Branch</span><strong>${v.branch.e()}$dirtyBadge</strong></div>
                    </div>
                    """.trimIndent()
                } +
                section("Configuratie") {
                    """
                    <div class="key-value one">
                      ${page.configuration.entries.joinToString("") { (key, value) ->
                        """<div><span>${key.e()}</span><strong>${value.e()}</strong></div>"""
                    }}
                    </div>
                    """.trimIndent()
                } +
                section("Factory-proces") {
                    """
                    <p class="muted" style="font-size:13.5px;margin:2px 0 12px">
                      <b>Herstart</b> stopt de factory; de bash-loop (<code>factory-loop.sh</code>) start 'm
                      meteen opnieuw met een verse <code>git pull</code>. <b>Stop</b> schrijft een stop-signaal
                      zodat ook de loop zelf stopt — daarna draait er niets meer tot je het script opnieuw start.
                    </p>
                    <div class="status">
                      <form method="post" action="/admin/restart">
                        <button class="button" type="submit">&#8635; Herstart factory</button>
                      </form>
                      <form method="post" action="/admin/stop"
                            onsubmit="return confirm('Factory én de loop stoppen? Er draait daarna niets meer tot je factory-loop.sh opnieuw start.');">
                        <button class="button danger" type="submit">&#9632; Stop factory</button>
                      </form>
                    </div>
                    """.trimIndent()
                }
        }

    /** Responspagina na "Herstart": de loop start de app zo weer; ververst zichzelf naar Settings. */
    fun restarting(): String =
        layout("settings", "Factory herstart…", "") {
            """
            <section>
              <p>De factory stopt en wordt door de loop opnieuw gestart met de nieuwste code
                 (verse <code>git pull</code> + rebuild). Dit duurt meestal tien&ndash;dertig seconden.</p>
              <p class="muted">Deze pagina keert zo vanzelf terug naar Settings; lukt dat niet, dan was de
                 factory nog aan het herstarten &mdash; ververs gewoon nog eens.</p>
              <p><a class="button" href="/settings">Terug naar Settings</a></p>
            </section>
            <script>setTimeout(function(){ location.href = '/settings'; }, 15000);</script>
            """.trimIndent()
        }

    /** Responspagina na "Stop": zowel de app als de bash-loop stoppen. */
    fun stopped(): String =
        layout("settings", "Factory gestopt", "") {
            """
            <section>
              <p>De factory is gestopt en de bash-loop stopt nu ook (stop-signaal geschreven).</p>
              <p class="muted">Start <code>./factory-loop.sh</code> opnieuw in je terminal om de factory weer
                 te laten draaien.</p>
            </section>
            """.trimIndent()
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
        // Voor een STORY tonen we de afgeleide "echte" status (incl. subtaken en merged-vlag); een
        // subtask-detail houdt z'n eigen fase-gebaseerde weergave.
        val storyStatus = issue
            ?.takeIf { it.issueType == IssueType.STORY }
            ?.let { realStatus(it, page.subtasks, merged = page.run?.finalStatus.equals("merged", ignoreCase = true)) }
        val statusText = when {
            issue == null -> "Niet geladen"
            storyStatus != null -> storyStatus.label
            !issue.fields.error.isNullOrBlank() -> "Vastgelopen"
            issue.fields.paused -> "Gepauzeerd"
            phase.isNullOrBlank() -> "Klaar voor pickup"
            else -> phase
        }
        val kind = when {
            storyStatus != null -> storyStatus.kind
            issue?.fields?.error?.isNotBlank() == true -> "bad"
            issue?.fields?.paused == true -> "warn"
            phase == "planning-approved" || phase == "tested-successfully" -> "ok"
            else -> "info"
        }
        val dot = when (kind) {
            "bad" -> "bad"
            "warn", "neutral" -> "wait"
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

    /** "My actions"-inbox: alles wat op de mens wacht, over alle stories, gegroepeerd per story. */
    fun myActions(page: MyActionsPageData): String =
        layout("my-actions", "My actions", "Alles wat op jou wacht — over alle stories", autoRefreshSeconds = 5) {
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
        val context = if (item.isSubtask) {
            "Subtaak ${issue.key.e()}${issue.fields.subtaskType?.let { " &middot; ${it.e()}" } ?: ""}"
        } else {
            "Story ${issue.key.e()}"
        }
        // Na de actie terug naar de inbox. Een issue in error toont z'n foutmelding i.p.v. een
        // actie-kaart (de fase-kaarten matchen niet op een error-status).
        val error = issue.fields.error?.takeIf { it.isNotBlank() }
        val card = when {
            error != null -> myActionErrorCard(context, error)
            item.isSubtask -> subtaskActionCard(issue.key, issue, context, item.question, returnTo = "/my-actions", runs = group.runs, prUrl = group.prUrl)
            else -> storyActionCard(group.storyKey, issue, context, item.question, group.runs, returnTo = "/my-actions")
        }
        // Subtaak: ook een directe link naar het subtaak-scherm (nieuwe tab).
        val openSubtask = if (item.isSubtask) {
            """<div class="ma-item-tools"><a class="button sm" href="/stories/${issue.key.path()}" target="_blank" rel="noopener">Open subtaak &#8599;</a></div>"""
        } else {
            ""
        }
        return """<div class="ma-item">$openSubtask$card</div>"""
    }

    /** Inbox-kaart voor een issue dat in error staat: toont de foutmelding (oplossen op het story-scherm). */
    private fun myActionErrorCard(context: String, error: String): String =
        """
        <section class="error-card">
          <div class="ac-head"><span class="ac-title">&#9888; In error</span><span class="pill-wait">$context</span></div>
          <p class="ac-note">De story loopt hierdoor niet door. Open het issue om het op te lossen (clear error / re-implement).</p>
          <pre>${error.e()}</pre>
        </section>
        """.trimIndent()

    /** Feedback-actiekaart bovenaan: directe actie op deze issue, of de actieve subtaak. */
    private fun humanActionTop(page: StoryDetailPageData): String {
        val issue = page.issue ?: return ""
        // Zoek het agent-resultaat in álle runs (story + subtaken), zodat de "Bekijk resultaat"-knop
        // ook werkt voor een subtaak die op het story-scherm gesurfacet wordt.
        val runs = page.allAgentRuns.ifEmpty { page.agentRuns }
        val own = when (issue.issueType) {
            IssueType.STORY -> storyActionCard(page.storyKey, issue, "actie nodig", page.agentQuestions[page.storyKey], runs)
            IssueType.SUBTASK -> subtaskActionCard(page.storyKey, issue, "actie nodig", page.agentQuestions[page.storyKey], runs = runs, prUrl = page.run?.prUrl)
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
                    runs = runs,
                    prUrl = page.run?.prUrl,
                )
            }
        }
        return ""
    }

    private fun storyActionCard(storyKey: String, issue: TrackerIssue, context: String, question: String?, runs: List<UiAgentRun> = emptyList(), returnTo: String? = null): String =
        when (StoryPhase.fromTracker(issue.fields.storyPhase)) {
            StoryPhase.REFINED_WITH_QUESTIONS ->
                answerCard(storyKey, "story-phase", "questions-answered", "Vraag van de refiner", context, question, returnTo)
            StoryPhase.PLANNED_WITH_QUESTIONS ->
                answerCard(storyKey, "story-phase", "planning-questions-answered", "Vraag van de planner", context, question, returnTo)
            StoryPhase.REFINED ->
                approveRejectCard(storyKey, "story-phase", "refined-approved", "refined-rejected", "Refinement beoordelen", "De refiner is klaar. Keur goed om door te gaan, of stuur terug met feedback.", context, returnTo, resultText = latestAgentResult(runs, storyKey, "refiner"))
            StoryPhase.PLANNED ->
                approveRejectCard(storyKey, "story-phase", "planning-approved", "planning-rejected", "Plan beoordelen", "De planner heeft het plan afgerond. Keur goed om te starten, of stuur terug met feedback.", context, returnTo, resultText = latestAgentResult(runs, storyKey, "planner"))
            else -> ""
        }

    private fun subtaskActionCard(
        subtaskKey: String,
        issue: TrackerIssue,
        context: String,
        question: String? = null,
        returnTo: String? = null,
        runs: List<UiAgentRun> = emptyList(),
        prUrl: String? = null,
    ): String {
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
                    // De developer-wijziging bekijk je in de code/PR, dus geen tekst-popup maar een PR-link.
                    approveRejectCard(subtaskKey, ep, "development-approved", "development-rejected", "Ontwikkeling beoordelen", "De developer heeft de wijziging geïmplementeerd en gepusht. Bekijk het resultaat en keur goed, of stuur terug met feedback.", context, returnTo, resultLink = prUrl?.let { "Bekijk PR" to it })
                } else {
                    ""
                }
            SubtaskPhase.REVIEWED ->
                approveRejectCard(subtaskKey, ep, "review-approved", "review-rejected", "Review beoordelen", "De reviewer is klaar. Keur de review goed, of stuur terug met feedback.", context, returnTo, resultText = latestAgentResult(runs, subtaskKey, "reviewer"))
            SubtaskPhase.TESTED ->
                approveRejectCard(subtaskKey, ep, "test-approved", "test-rejected", "Test beoordelen", "De tester is klaar. Keur het testresultaat goed, of stuur terug met feedback.", context, returnTo, resultText = latestAgentResult(runs, subtaskKey, "tester"))
            SubtaskPhase.SUMMARIZED ->
                approveRejectCard(subtaskKey, ep, "summary-approved", "summary-rejected", "Samenvatting beoordelen", "De samenvatting is klaar. Keur goed, of stuur terug met feedback.", context, returnTo, resultText = latestAgentResult(runs, subtaskKey, "summarizer"))
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

    private fun approveRejectCard(
        key: String,
        endpoint: String,
        approvePhase: String,
        rejectPhase: String,
        title: String,
        note: String,
        context: String,
        returnTo: String? = null,
        resultText: String? = null,
        resultLink: Pair<String, String>? = null,
    ): String {
        val dialogId = "res-$key-$approvePhase".replace(Regex("[^A-Za-z0-9_-]"), "-")
        val resultButton = when {
            resultText != null ->
                """<button type="button" class="button sm" onclick="document.getElementById('$dialogId').showModal()">Bekijk resultaat</button>"""
            resultLink != null ->
                """<a class="button sm" href="${resultLink.second.e()}" target="_blank" rel="noopener">${resultLink.first.e()}</a>"""
            else -> ""
        }
        val dialog = resultText?.let {
            """
            <dialog id="$dialogId" class="result-dialog">
              <div class="rd-head"><strong>${title.e()}</strong><button type="button" class="button sm" onclick="this.closest('dialog').close()">Sluiten</button></div>
              <pre>${it.e()}</pre>
            </dialog>
            """.trimIndent()
        } ?: ""
        return """
        <section class="action-card">
          <div class="ac-head"><span class="ac-title">$title</span><span class="pill-wait">$context</span></div>
          <p class="ac-note">${note.e()}</p>
          <form method="post" action="/stories/${key.path()}/$endpoint">
            ${returnToField(returnTo)}
            <textarea name="comment" rows="3" placeholder="Reden (optioneel)"></textarea>
            <div class="button-row">
              <button class="button primary" type="submit" name="phase" value="$approvePhase">Approve</button>
              <button class="button danger" type="submit" name="phase" value="$rejectPhase">Reject</button>
              $resultButton
            </div>
          </form>
          $dialog
        </section>
        """.trimIndent()
    }

    /** Schoongemaakte samenvatting van de meest recente run van [role] op [issueKey], of null. */
    private fun latestAgentResult(runs: List<UiAgentRun>, issueKey: String, role: String): String? =
        runs
            .filter { (it.subtaskKey ?: it.storyKey) == issueKey && it.role.equals(role, ignoreCase = true) }
            .maxByOrNull { it.startedAt }
            ?.summaryText
            ?.let { cleanResultText(it) }
            ?.takeIf { it.isNotBlank() }

    /** Strip de JSON-control-regels en de proposed-description-markers uit een agent-samenvatting. */
    private fun cleanResultText(raw: String): String =
        raw.lines()
            .filterNot { line ->
                val t = line.trim()
                (t.startsWith("{") && t.endsWith("}") &&
                    (t.contains("\"phase\"") || t.contains("\"agent_tips_update\"") || t.contains("\"subtasks\""))) ||
                    t == "<!-- proposed-description:start -->" ||
                    t == "<!-- proposed-description:end -->"
            }
            .joinToString("\n")
            .trim()

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
        """<form method="post" action="/stories/${storyKey.path()}/commands/$command"><button class="$kind" type="submit">$label</button></form>"""

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

    /** Of een subtask z'n eindfase heeft bereikt (review/test/summary-approved of manual-action-done). */
    private fun subtaskIsDone(issue: TrackerIssue): Boolean =
        SubtaskPhase.fromTracker(issue.fields.subtaskPhase)?.isTerminal == true

    /** Of een subtask een fout bevat. */
    private fun subtaskHasError(issue: TrackerIssue): Boolean =
        !issue.fields.error.isNullOrBlank()

    /** Of een subtask actief is (agent draait) en nog niet in terminale fase. */
    private fun subtaskIsActive(issue: TrackerIssue): Boolean {
        val phase = SubtaskPhase.fromTracker(issue.fields.subtaskPhase)
        return phase?.isActive == true && phase.isTerminal == false
    }

    private fun subtasksPanel(page: StoryDetailPageData): String {
        if (page.subtasks.isEmpty()) {
            return ""
        }
        return section("Subtaken") {
            page.subtasks.joinToString("") { sub ->
                val waiting = subtaskAwaitsHuman(sub)
                val done = subtaskIsDone(sub)
                val hasError = subtaskHasError(sub)
                val isActive = subtaskIsActive(sub)
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
            <div><span>Gestart</span><strong>${date(run?.startedAt)}</strong></div>
            <div><span>Geeindigd</span><strong>${date(run?.endedAt)}</strong></div>
            <div><span>Final status</span><strong>${run?.finalStatus?.e() ?: "lopend"}</strong></div>
            <div><span>Repo-veld</span><strong>${issue?.fields?.repo?.e() ?: "-"}</strong></div>
            <div><span>Target repo</span><strong>${issue?.fields?.targetRepo?.e() ?: run?.targetRepo?.e() ?: "-"}</strong></div>
            <div><span>Repo folder</span><strong>${run?.workspacePath?.takeIf { it.isNotBlank() }?.let { repoFolder(it).e() } ?: "-"}</strong></div>
            <div><span>AI supplier</span><strong>${issue?.fields?.aiSupplier?.e() ?: "-"}</strong></div>
            <div><span>AI level</span><strong>${issue?.fields?.aiLevel?.toString()?.e() ?: "-"}</strong></div>
            $autoApproveButtons
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

    private fun repoFolder(workspacePath: String): String =
        workspacePath.trimEnd('/', '\\') + "/repo"

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

    // ── lijsten ─────────────────────────────────────────────────────────────

    private fun issueTable(
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
                val st = realStatus(issue, emptyList(), merged = issue.key in mergedKeys)
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
          $cssLink
        </head>
        <body${autoRefreshSeconds?.let { " data-refresh=\"$it\"" } ?: ""}>
          <div class="shell">
            <aside class="sidebar">
              <a class="brand" href="/dashboard"><span class="brand-mark">SF</span>Software Factory</a>
              <nav class="nav">
                ${nav(active, "dashboard", "/dashboard", "Dashboard")}
                ${nav(active, "stories", "/stories", "Stories")}
                ${navMyActions(active)}
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
          $PAGE_SCRIPT
        </body>
        </html>
        """.trimIndent()

    private fun nav(active: String, key: String, href: String, label: String): String =
        """<a class="${if (active == key) "active" else ""}" href="$href">$label</a>"""

    /** Nav-item "My actions" met een telbolletje dat door [NAV_BADGE_SCRIPT] live wordt gevuld. */
    private fun navMyActions(active: String): String =
        """<a class="${if (active == "my-actions") "active" else ""}" href="/my-actions">My actions <span class="nav-badge" data-myactions-badge hidden></span></a>"""

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

    /** Looptijd als minuten:seconden (bv. 1:34), seconden altijd 2-cijferig. */
    private fun durationMmSs(value: Long): String {
        val totalSeconds = (value / 1000).coerceAtLeast(0)
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
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
        /**
         * Geldige AI-modellen PER supplier (bron: AiRouting). De model-ids verschillen per supplier —
         * `claude` gebruikt streepjes (`claude-haiku-4-5`), `copilot` punten (`claude-haiku-4.5`) — dus
         * het formulier filtert het model-dropdown op de gekozen supplier. Suppliers zonder eigen
         * model-override (microsoft/none) staan hier niet: die krijgen alleen "automatisch".
         */
        private val AI_MODELS_BY_SUPPLIER: Map<String, List<String>> = mapOf(
            "claude" to listOf("claude-opus-4-8", "claude-opus-4-7", "claude-sonnet-4-6", "claude-haiku-4-5"),
            "copilot" to listOf("claude-opus-4.5", "claude-sonnet-4.5", "claude-haiku-4.5", "gpt-4.1"),
            "openai" to listOf("gpt-4.1"),
            "mock" to listOf("dummy-ai-client"),
        )

        /** Selecteerbare AI-suppliers (bron: YouTrackClient AI-supplier FieldSpec). */
        private val AI_SUPPLIER_OPTIONS = listOf("none", "mock", "claude", "openai", "copilot", "microsoft")

        /**
         * Filtert het AI-model-dropdown op de gekozen supplier (model-ids verschillen per supplier).
         * Modellen die niet bij de supplier horen worden verborgen; staat er een ongeldig model
         * geselecteerd, dan valt het terug op "automatisch". "automatisch" (lege waarde) blijft altijd.
         */
        private val NEW_STORY_MODEL_FILTER_SCRIPT =
            """
            <script>
            (function(){
              var sup = document.getElementById('nsf-supplier');
              var mod = document.getElementById('nsf-model');
              if (!sup || !mod) return;
              function sync(){
                var s = sup.value;
                Array.prototype.forEach.call(mod.options, function(o){
                  if (!o.value) return;
                  var ok = o.getAttribute('data-supplier') === s;
                  o.hidden = !ok;
                  o.disabled = !ok;
                });
                var cur = mod.options[mod.selectedIndex];
                if (cur && cur.value && cur.disabled) mod.value = '';
              }
              sup.addEventListener('change', sync);
              sync();
            })();
            </script>
            """.trimIndent()

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
        private val PAGE_SCRIPT =
            """
            <script>
            (function(){
              var hasRefresh = document.body.hasAttribute('data-refresh');
              var badge = document.querySelector('[data-myactions-badge]');
              function busy(){
                if (document.querySelector('dialog[open]')) return true;
                if (document.querySelector('details[open]')) return true;
                var sel = window.getSelection && String(window.getSelection());
                if (sel && sel.length) return true;
                var a = document.activeElement;
                if (a && (a.tagName === 'TEXTAREA' || a.tagName === 'INPUT')) return true;
                return false;
              }
              var refreshing = false;
              async function refreshMain(){
                if (!hasRefresh || busy() || refreshing) return;
                refreshing = true;
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
                  refreshing = false;
                }
              }
              var badgeAt = 0, badgeBusy = false;
              async function updateBadge(){
                if (!badge || badgeBusy) return;
                var now = Date.now();
                if (now - badgeAt < 3000) return; // debounce bursts van SSE-events
                badgeAt = now; badgeBusy = true;
                try {
                  var res = await fetch('/my-actions/count', { credentials: 'same-origin' });
                  if (!res.ok) return;
                  var n = parseInt((await res.text()).trim(), 10);
                  if (isNaN(n) || n <= 0) { badge.hidden = true; badge.textContent = ''; }
                  else { badge.textContent = String(n); badge.hidden = false; }
                } catch (e) {
                } finally {
                  badgeBusy = false;
                }
              }
              function onChange(){ refreshMain(); updateBadge(); }
              updateBadge();
              // Eén gedeelde SSE-verbinding voor zowel de content-refresh als het badge-bolletje.
              try {
                var es = new EventSource('/events');
                es.addEventListener('changed', onChange);
              } catch (e) {}
              setInterval(onChange, 30000);
            })();
            </script>
            """.trimIndent()
    }
}
