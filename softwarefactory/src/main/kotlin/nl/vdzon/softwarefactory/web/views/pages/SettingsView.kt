package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.nightly.NightlyTime
import nl.vdzon.softwarefactory.web.models.SettingsPageData
import nl.vdzon.softwarefactory.web.views.shared.Formatters
import nl.vdzon.softwarefactory.web.views.shared.HtmlLayout
import nl.vdzon.softwarefactory.web.views.shared.badge
import nl.vdzon.softwarefactory.web.views.shared.e
import nl.vdzon.softwarefactory.web.views.shared.section

/** Settings-pagina plus de responspagina's van de herstart-/stop-knoppen. */
internal class SettingsView(
    private val layout: HtmlLayout,
    private val fmt: Formatters,
) {

    fun render(page: SettingsPageData): String =
        layout.layout("settings", "Settings", "Account en lokale dashboardinstellingen") {
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
                      <div><span>Gestart</span><strong>${fmt.timestamp(v.startedAt).e()} &middot; ${fmt.relative(v.startedAt).e()}</strong></div>
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
                nightlySettingsSection(page) +
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

    /** Schrijfbaar formulier voor de nachtelijke scheduler (master-switch + start-/summary-tijd, NL-tijd). */
    private fun nightlySettingsSection(page: SettingsPageData): String =
        section("Nightly scheduler") {
            val n = page.nightly
            val checked = if (n.enabled) " checked" else ""
            val start = NightlyTime.formatHhMm(n.startTime).e()
            val summary = NightlyTime.formatHhMm(n.summaryTime).e()
            val feedback = when (page.nightlySaveResult) {
                "saved" -> """<p style="margin:2px 0 12px">${badge("opgeslagen", "ok")}</p>"""
                "invalid" -> """<p style="margin:2px 0 12px">${badge("ongeldige tijd (HH:MM)", "warn")}</p>"""
                else -> ""
            }
            """
            <a id="nightly"></a>
            <p class="muted" style="font-size:13.5px;margin:2px 0 12px">
              De nachtelijke scheduler draait automatisch de per-project gedeclareerde nightly jobs.
              Tijden gelden in lokale NL-tijd (Europe/Amsterdam). Staat de master-switch uit, dan doet
              de scheduler niets.
            </p>
            $feedback
            <form method="post" action="/settings/nightly">
              <div class="key-value one" style="margin-bottom:12px">
                <div>
                  <span>Master-switch</span>
                  <strong><label><input type="checkbox" name="enabled" value="on"$checked> Scheduler aan</label></strong>
                </div>
                <div>
                  <span>Start-tijd</span>
                  <strong><input type="time" name="startTime" value="$start" required></strong>
                </div>
                <div>
                  <span>Summary-tijd</span>
                  <strong><input type="time" name="summaryTime" value="$summary" required></strong>
                </div>
              </div>
              <button class="button" type="submit">Opslaan</button>
            </form>
            """.trimIndent()
        }

    /** Responspagina na "Herstart": de loop start de app zo weer; ververst zichzelf naar Settings. */
    fun restarting(): String =
        layout.layout("settings", "Factory herstart…", "") {
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
        layout.layout("settings", "Factory gestopt", "") {
            """
            <section>
              <p>De factory is gestopt en de bash-loop stopt nu ook (stop-signaal geschreven).</p>
              <p class="muted">Start <code>./factory-loop.sh</code> opnieuw in je terminal om de factory weer
                 te laten draaien.</p>
            </section>
            """.trimIndent()
        }
}
