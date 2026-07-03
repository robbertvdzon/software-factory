package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.web.models.NightlyJobsPageData
import nl.vdzon.softwarefactory.web.models.NightlyRunView
import nl.vdzon.softwarefactory.web.views.shared.Formatters
import nl.vdzon.softwarefactory.web.views.shared.HtmlLayout
import nl.vdzon.softwarefactory.web.views.shared.alerts
import nl.vdzon.softwarefactory.web.views.shared.badge
import nl.vdzon.softwarefactory.web.views.shared.e
import nl.vdzon.softwarefactory.web.views.shared.empty
import nl.vdzon.softwarefactory.web.views.shared.path
import nl.vdzon.softwarefactory.web.views.shared.section

/** Nightly-pagina: nachtelijke jobs van alle projecten, handmatig te starten + de lopende run. */
internal class NightlyView(
    private val layout: HtmlLayout,
    private val fmt: Formatters,
) {

    fun render(page: NightlyJobsPageData): String =
        layout.layout("nightly", "Nightly", "Nachtelijke jobs van alle projecten — handmatig te starten") {
            alerts(page.errors) +
                nightlyRunNotice(page.runNotice) +
                """
                <section>
                  <div style="display:flex;justify-content:space-between;align-items:center;gap:16px">
                    <div><strong>Hele nightly nu draaien</strong>
                      <div style="font-size:0.85em;opacity:0.65">Start alle enabled jobs als een nieuwe run; de digest volgt zodra de run klaar is. Kan alleen als er geen run loopt.</div></div>
                    <form method="post" action="/nightly/run-now" onsubmit="return confirm('Een nieuwe nightly-run starten met alle enabled jobs?');">
                      <button class="button" type="submit">&#9654; Run nu</button>
                    </form>
                  </div>
                </section>
                """.trimIndent() +
                nightlyRunSection(page.run) +
                if (page.jobs.isEmpty()) {
                    empty("Geen nachtelijke jobs gevonden (.factory/nightly/ in de project-repo's).")
                } else {
                    page.jobs.groupBy { it.project }.entries.joinToString("") { (project, jobs) ->
                        val rows = jobs.joinToString("") { job ->
                            val flags = buildList {
                                if (!job.enabled) add("disabled")
                                if (job.silent) add("silent")
                                job.aiSupplier?.let { add(it) }
                                job.aiModel?.let { add(it) }
                            }.joinToString(" &middot; ") { it.e() }
                            """
                            <div style="display:flex;justify-content:space-between;align-items:center;gap:16px;margin:10px 0">
                              <div>
                                <strong>${job.title.e()}</strong>
                                <div style="font-size:0.85em;opacity:0.65">${job.name.e()}${if (flags.isNotBlank()) " &middot; $flags" else ""}</div>
                              </div>
                              <form method="post" action="/nightly/create-story" onsubmit="return confirm('Story maken en starten voor: ${job.title.e()}?');">
                                <input type="hidden" name="project" value="${job.project.e()}">
                                <input type="hidden" name="jobName" value="${job.name.e()}">
                                <button class="button" type="submit">&#9654; Story maken &amp; starten</button>
                              </form>
                            </div>
                            """.trimIndent()
                        }
                        """
                        <section>
                          <h2 class="section-title">${project.e()}</h2>
                          $rows
                        </section>
                        """.trimIndent()
                    }
                }
        }

    /** Statusblok bovenaan `/nightly`: de huidige/laatste automatische run, per project gescheiden. */
    private fun nightlyRunSection(run: NightlyRunView?): String {
        if (run == null) {
            return section("Automatische run") {
                empty("Nog geen automatische nightly-run. Zet de scheduler aan via Settings → Nightly scheduler.")
            }
        }
        val projects = if (run.projects.isEmpty()) {
            empty("Deze run heeft geen jobs (geen enabled nachtelijke jobs gevonden).")
        } else {
            run.projects.joinToString("") { project ->
                val rows = project.jobs.joinToString("") { job ->
                    val link = job.storyKey?.let {
                        """ &middot; <a href="/stories/${it.path()}">${it.e()}</a>"""
                    } ?: ""
                    val started = job.startedAt
                        ?.let { """ &middot; gestart ${fmt.relative(it).e()} (${fmt.absolute(it).e()})""" }
                        ?: " &middot; niet gestart"
                    """
                    <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;margin:6px 0">
                      <div><strong>${job.title.e()}</strong>
                        <span style="font-size:0.85em;opacity:0.65">${job.jobName.e()}$link$started</span></div>
                      ${nightlyJobBadge(job.status)}
                    </div>
                    """.trimIndent()
                }
                """<div style="margin:10px 0"><div style="font-weight:600">${project.project.e()}</div>$rows</div>"""
            }
        }
        val meta = buildList {
            add("Datum ${run.runDate}")
            add(if (run.kind == "manual") "handmatig" else "automatisch")
            run.startedAt?.let { add("gestart ${fmt.relative(it)}") }
            run.summarySentAt?.let { add("digest verstuurd ${fmt.relative(it)}") }
        }.joinToString(" &middot; ") { it.e() }
        val digest = run.summaryText?.takeIf { it.isNotBlank() }?.let {
            """<details style="margin-top:10px"><summary>Digest</summary><pre style="white-space:pre-wrap">${it.e()}</pre></details>"""
        } ?: ""
        val stopButton = if (run.status == "running") {
            """
            <form method="post" action="/nightly/stop" onsubmit="return confirm('De lopende run onderbreken? Resterende jobs worden geannuleerd.');">
              <button class="button" type="submit">&#9632; Onderbreek run</button>
            </form>
            """.trimIndent()
        } else {
            ""
        }
        return section("Automatische run") {
            """
            <div style="display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:8px">
              <div style="display:flex;align-items:center;gap:10px">
                ${badge(run.status)}<span class="muted" style="font-size:13px">$meta</span>
              </div>
              $stopButton
            </div>
            $projects
            $digest
            """.trimIndent()
        }
    }

    /** Feedback-banner na een Run nu / onderbreek-actie (`?run=...`). */
    private fun nightlyRunNotice(notice: String?): String = when (notice) {
        "started" -> """<p style="margin:0 0 12px">${badge("nieuwe run gestart", "ok")}</p>"""
        "busy" -> """<p style="margin:0 0 12px">${badge("er loopt al een run — onderbreek die eerst", "warn")}</p>"""
        "stopped" -> """<p style="margin:0 0 12px">${badge("run onderbroken", "ok")}</p>"""
        "stop-none" -> """<p style="margin:0 0 12px">${badge("geen lopende run om te onderbreken", "warn")}</p>"""
        else -> ""
    }

    private fun nightlyJobBadge(status: String): String = when (status) {
        "done" -> badge("done", "ok")
        "failed" -> badge("failed", "bad")
        "running" -> badge("running", "warn")
        "cancelled" -> badge("cancelled", "bad")
        else -> badge("pending", "info")
    }
}
