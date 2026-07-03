package nl.vdzon.softwarefactory.web.views.shared

import nl.vdzon.softwarefactory.core.HumanActionPolicy
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.web.models.UiAgentRun

/**
 * De actiekaarten waarmee een mens de factory verder helpt (vraag beantwoorden,
 * approve/reject, handmatige stap afronden). Gedeeld door het story-detailscherm en de
 * "My actions"-inbox. De kaarten escapen zelf hun title/context-parameters bij interpolatie;
 * aanroepers geven platte tekst mee.
 */
internal object ActionCards {

    fun storyActionCard(storyKey: String, issue: TrackerIssue, context: String, question: String?, runs: List<UiAgentRun> = emptyList(), returnTo: String? = null): String =
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

    fun subtaskActionCard(
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
            // SF-192 — manual-approve-poort: approve/reject lopen via het commando-mechanisme, niet via
            // een phase-pad. Reject geeft de ingevulde reden mee.
            SubtaskPhase.MANUAL_APPROVE_NEEDED ->
                approveRejectCommandCard(subtaskKey, "approve", "reject", "Handmatige goedkeuring", "De factory wacht vóór de merge op je goedkeuring. Keur goed om door te gaan, of keur af met een reden om de hele story opnieuw uit te voeren.", context, returnTo)
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

    fun answerCard(key: String, endpoint: String, targetPhase: String, title: String, context: String, question: String?, returnTo: String? = null): String =
        """
        <section class="action-card">
          <div class="ac-head"><span class="ac-title">${title.e()}</span><span class="pill-wait">${context.e()}</span></div>
          ${question?.takeIf { it.isNotBlank() }?.let { """<div class="q">${it.e()}</div>""" } ?: ""}
          <form method="post" action="/stories/${key.path()}/$endpoint">
            ${returnToField(returnTo)}
            <input type="hidden" name="phase" value="$targetPhase">
            <textarea name="comment" rows="3" placeholder="Jouw antwoord" required></textarea>
            <div class="button-row"><button class="button primary" type="submit">Antwoord versturen</button></div>
          </form>
        </section>
        """.trimIndent()

    fun approveRejectCard(
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
          <div class="ac-head"><span class="ac-title">${title.e()}</span><span class="pill-wait">${context.e()}</span></div>
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

    /**
     * SF-192 — approve/reject-kaart die via het commando-mechanisme loopt (geen los phase-pad). Mirror
     * van [approveRejectCard], maar de knoppen posten naar `/commands/{approve|reject}` (via `formaction`)
     * met de ingevulde reden als `comment`-veld.
     */
    fun approveRejectCommandCard(
        key: String,
        approveCommand: String,
        rejectCommand: String,
        title: String,
        note: String,
        context: String,
        returnTo: String? = null,
    ): String = """
        <section class="action-card">
          <div class="ac-head"><span class="ac-title">${title.e()}</span><span class="pill-wait">${context.e()}</span></div>
          <p class="ac-note">${note.e()}</p>
          <form method="post" action="/stories/${key.path()}/commands/$approveCommand">
            ${returnToField(returnTo)}
            <textarea name="comment" rows="3" placeholder="Reden (verplicht bij afkeuren)"></textarea>
            <div class="button-row">
              <button class="button primary" type="submit" formaction="/stories/${key.path()}/commands/$approveCommand">Approve</button>
              <button class="button danger" type="submit" formaction="/stories/${key.path()}/commands/$rejectCommand">Reject</button>
            </div>
          </form>
        </section>
        """.trimIndent()

    fun approveOnlyCard(key: String, endpoint: String, targetPhase: String, title: String, note: String, label: String, context: String, returnTo: String? = null): String =
        """
        <section class="action-card">
          <div class="ac-head"><span class="ac-title">${title.e()}</span><span class="pill-wait">${context.e()}</span></div>
          <p class="ac-note">${note.e()}</p>
          <form method="post" action="/stories/${key.path()}/$endpoint">
            ${returnToField(returnTo)}
            <input type="hidden" name="phase" value="$targetPhase">
            <textarea name="comment" rows="2" placeholder="Notitie (optioneel)"></textarea>
            <div class="button-row"><button class="button primary" type="submit">${label.e()}</button></div>
          </form>
        </section>
        """.trimIndent()

    /** Inbox-kaart voor een issue dat in error staat: toont de foutmelding (oplossen op het story-scherm). */
    fun myActionErrorCard(context: String, error: String): String =
        """
        <section class="error-card">
          <div class="ac-head"><span class="ac-title">&#9888; In error</span><span class="pill-wait">${context.e()}</span></div>
          <p class="ac-note">De story loopt hierdoor niet door. Open het issue om het op te lossen (clear error / re-implement).</p>
          <pre>${error.e()}</pre>
        </section>
        """.trimIndent()

    /**
     * Of een subtask op een mens-actie wacht (vragen/goedkeuring/handmatig). De centrale
     * [HumanActionPolicy] beslist; [subtaskActionCard] rendert voor precies dezelfde gevallen
     * een kaart (auto-approve telt hier bewust niet mee: de pagina toont een momentopname).
     */
    fun subtaskAwaitsHuman(issue: TrackerIssue): Boolean =
        HumanActionPolicy.gateFor(issue) != null

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

    /** Verborgen veld zodat een actie op een gesurfacede subtaak terugkeert naar het juiste scherm. */
    private fun returnToField(returnTo: String?): String =
        returnTo?.takeIf { it.isNotBlank() }?.let { """<input type="hidden" name="returnTo" value="${it.e()}">""" } ?: ""
}
