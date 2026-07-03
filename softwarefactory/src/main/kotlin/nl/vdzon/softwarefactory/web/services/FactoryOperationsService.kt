package nl.vdzon.softwarefactory.web.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.FactoryOperations
import nl.vdzon.softwarefactory.core.HumanActionPolicy
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.MergeReadyInfo
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.web.models.UiStoryRun
import nl.vdzon.softwarefactory.web.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.springframework.stereotype.Service

/**
 * Implementatie van de domeinpoort [FactoryOperations] (`core`): de dashboard-/orchestratie-operaties
 * die lagere modules (zoals `telegram`) nodig hebben. Uit [FactoryDashboardService] gelicht zodat die
 * service weer puur page-data-assembler is en de poort-implementatie een eigen, kleine bean heeft.
 *
 * Leeft bewust in `web` (en niet in bv. `orchestrator`): de implementatie leunt op de web-eigen
 * [FactoryDashboardRepository]/[UiAgentRun]-modellen; verhuizen zou een andere module afhankelijk
 * maken van web-internals (Modulith-schending) of een orchestrator↔web-cyclus geven.
 *
 * LET OP: dit moet de ENIGE Spring-bean van [FactoryOperations] blijven — `telegram` injecteert
 * op het interface-type.
 */
@Service
class FactoryOperationsService(
    private val issueTrackerClient: YouTrackApi,
    private val orchestratorApi: OrchestratorApi,
    private val repository: FactoryDashboardRepository,
    private val previewApi: PreviewApi,
) : FactoryOperations {

    /**
     * De door de agent gestelde vraag voor dit issue, of null. Zelfde bron als de "My actions"-inbox
     * (de meest recente run met een niet-lege samenvatting, met de questions-JSON eruit gefilterd).
     * Hergebruikt door de Telegram-notifier zodat een melding exact dezelfde vraagtekst toont als het
     * dashboard.
     */
    override fun questionFor(issue: TrackerIssue): String? {
        val ownerKey = if (issue.issueType == IssueType.SUBTASK) {
            runCatching { issueTrackerClient.parentStoryKey(issue.key) }.getOrNull() ?: issue.key
        } else {
            issue.key
        }
        val run = runCatching { repository.latestStoryRun(ownerKey) }.getOrNull() ?: return null
        val runs = runCatching { repository.agentRunsForStory(run.id) }.getOrDefault(emptyList())
        return latestAgentQuestions(runs, ownerKey)[issue.key]
    }

    /**
     * Is [storyKey] klaar om te mergen? Alle subtaken terminaal én er is een PR die nog niet gemerged
     * is. Geeft de PR-info terug, of null wanneer er nog werk open staat / geen PR is / al gemerged.
     * Gebruikt door de Telegram-notifier om aan het einde een merge-actie aan te bieden.
     */
    override fun mergeReady(storyKey: String): MergeReadyInfo? {
        val subtasks = runCatching { issueTrackerClient.subtasksOf(storyKey) }.getOrNull() ?: return null
        if (subtasks.isEmpty()) return null
        val allDone = subtasks.all { SubtaskPhase.fromTracker(it.fields.subtaskPhase)?.isTerminal == true }
        if (!allDone) return null
        val run = runCatching { repository.latestStoryRun(storyKey) }.getOrNull() ?: return null
        if (run.prNumber == null) return null
        if (run.finalStatus.equals("merged", ignoreCase = true)) return null
        return MergeReadyInfo(storyKey, run.prNumber, run.prUrl)
    }

    /** Idem, maar startend vanaf een zojuist afgeronde subtaak: zoekt eerst de parent-story op. */
    override fun mergeReadyForSubtask(subtask: TrackerIssue): MergeReadyInfo? {
        val parentKey = runCatching { issueTrackerClient.parentStoryKey(subtask.key) }.getOrNull() ?: return null
        return mergeReady(parentKey)
    }

    /**
     * Het laatste tester-rapport van [storyKey]: de samenvatting van de meest recente TESTER-agent-run met
     * niet-lege tekst, of null. Gebruikt door de Telegram-melding om bij een afgeronde test-subtaak het
     * testrapport mee te sturen. Soft-fail: een DB-fout geeft null i.p.v. te gooien.
     */
    override fun testerReportFor(storyKey: String): String? {
        val run = runCatching { repository.latestStoryRun(storyKey) }.getOrNull() ?: return null
        val runs = runCatching { repository.agentRunsForStory(run.id) }.getOrDefault(emptyList())
        return runs
            .filter { it.role.equals(AgentRole.TESTER.markerKeyPart, ignoreCase = true) }
            .sortedByDescending { it.startedAt }
            .firstNotNullOfOrNull { it.summaryText?.takeIf { s -> s.isNotBlank() } }
    }

    /**
     * De preview-/test-URL van [storyKey] (dezelfde als de 'Test op preview'-knop), of null wanneer het
     * project geen preview heeft (`previewUrlTemplate` ontbreekt). Soft-fail: gooit nooit.
     */
    override fun previewUrlFor(storyKey: String): String? {
        val run = runCatching { repository.latestStoryRun(storyKey) }.getOrNull() ?: return null
        return runCatching { previewUrlOf(run) }.getOrNull()
    }

    /** De preview-URL van een al geladen story-run; gedeeld met de detailpagina van het dashboard. */
    internal fun previewUrlOf(run: UiStoryRun): String? =
        previewApi.render(run.previewUrlTemplate, run.prNumber)

    /**
     * Auto-approve geldt centraal op de PARENT-story; de beslislogica leeft in
     * [HumanActionPolicy], zodat inbox, meldingen en uitvoering dezelfde nemen.
     */
    override fun autoApproveActive(issue: TrackerIssue): Boolean =
        HumanActionPolicy.autoApproveActive(issue) { subtaskKey ->
            runCatching { issueTrackerClient.parentStoryKey(subtaskKey) }.getOrNull()
                ?.let { parentKey -> runCatching { issueTrackerClient.getIssue(parentKey).fields }.getOrNull() }
        }

    /** Wacht deze (sub)taak op een mens (error, vraag, goedkeuring of handmatige stap)? */
    internal fun awaitsHuman(issue: TrackerIssue): Boolean =
        HumanActionPolicy.awaitsHuman(issue, autoApproveActive(issue))

    /**
     * Mens-actie vanuit de UI: zet de `Story Phase` (goedkeuren/afkeuren/antwoorden)
     * en post een optionele reden/antwoord als comment. Valideert tegen StoryPhase.
     */
    override fun setStoryPhase(storyKey: String, phase: String, comment: String?) {
        val target = StoryPhase.fromTracker(phase) ?: error("Onbekende Story Phase: $phase")
        comment?.takeIf { it.isNotBlank() }?.let { issueTrackerClient.postComment(storyKey, it) }
        issueTrackerClient.updateIssueFields(
            storyKey,
            TrackerFieldUpdate.of(TrackerField.STORY_PHASE to target.trackerValue),
        )
    }

    /** Mens-actie op een subtask: zet de `Subtask Phase` + optionele reden/antwoord als comment. */
    override fun setSubtaskPhase(subtaskKey: String, phase: String, comment: String?) {
        val target = SubtaskPhase.fromTracker(phase) ?: error("Onbekende Subtask Phase: $phase")
        comment?.takeIf { it.isNotBlank() }?.let { issueTrackerClient.postComment(subtaskKey, it) }
        issueTrackerClient.updateIssueFields(
            subtaskKey,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to target.trackerValue),
        )
    }

    override fun queueCommand(storyKey: String, command: FactoryCommand, reason: String?) {
        orchestratorApi.queueCommand(storyKey, command, reason)
    }

    companion object {
        private val questionMapper = jacksonObjectMapper()

        /**
         * Per issue-key (story = [fallbackKey], subtask = subtask_key) de gestelde vraag: de meest
         * recente run MET een niet-lege samenvatting. Bewust niet "de laatste run" — een latere lege
         * of half-afgeronde run (bv. uit recovery-churn) mag de eerder gestelde vraag niet verbergen.
         */
        internal fun latestAgentQuestions(runs: List<UiAgentRun>, fallbackKey: String): Map<String, String> =
            runs.groupBy { it.subtaskKey ?: fallbackKey }
                .mapNotNull { (key, group) ->
                    group.sortedByDescending { it.startedAt }
                        .firstNotNullOfOrNull { it.summaryText?.takeIf { s -> s.isNotBlank() } }
                        ?.let { key to questionTextFrom(it) }
                }
                .toMap()

        /**
         * Een agent stuurt z'n hele bericht als samenvatting, met ergens een control-JSON:
         * `{"phase":"...-with-questions","questions":["...","..."]}`. Toon ALLEEN die vragen
         * (genummerd bij meerdere). De JSON mag multi-line / pretty-printed zijn en in een
         * ```json-codeblok staan. Geen herkenbare questions-JSON → val terug op de volle samenvatting.
         */
        internal fun questionTextFrom(summary: String): String {
            val questions = jsonObjectsIn(summary)
                .firstOrNull { it.path("questions").isArray }
                ?.path("questions")
                ?.mapNotNull { node -> node.asText("").takeIf { it.isNotBlank() } }
                ?.takeIf { it.isNotEmpty() }
                ?: return summary
            return if (questions.size == 1) {
                questions.single()
            } else {
                questions.mapIndexed { index, q -> "${index + 1}. $q" }.joinToString("\n\n")
            }
        }

        /**
         * Alle JSON-objecten die ergens in [text] beginnen. Jackson kan niet midden in lopende tekst
         * zoeken; daarom proberen we vanaf elke `{` een object te parsen en slaan we bij succes de
         * geparste tekst over (zo blijven alleen top-level objecten over, net als voorheen).
         * Kandidaten die geen geldige JSON blijken (bv. een losse accolade in proza) worden stil
         * overgeslagen — zelfde soft-fail als de oude handgeschreven accolade-parser.
         */
        private fun jsonObjectsIn(text: String): List<JsonNode> {
            val results = mutableListOf<JsonNode>()
            var index = 0
            while (index < text.length) {
                val start = text.indexOf('{', index)
                if (start < 0) break
                val parsed = runCatching {
                    questionMapper.createParser(text.substring(start)).use { parser ->
                        val node: JsonNode? = parser.readValueAsTree()
                        if (node?.isObject == true) node to parser.currentLocation().charOffset else null
                    }
                }.getOrNull()
                if (parsed == null) {
                    index = start + 1
                } else {
                    // Bewust add() en niet `+=`: JsonNode is zelf Iterable, dus `+=` is ambigu.
                    results.add(parsed.first)
                    index = start + maxOf(parsed.second.toInt(), 1)
                }
            }
            return results
        }
    }
}
