package nl.vdzon.softwarefactory.dashboard.services

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import org.springframework.stereotype.Service

/** Menselijke testbeslissingen blijven expliciet en zijn gebonden aan het beoordeelde werk. */
@Service
class TestDecisionService(
    private val tracker: TrackerCapabilities,
    private val storyRuns: StoryRunRepository,
    private val agentRuns: AgentRunRepository,
    private val github: GitHubApi,
) {
    fun decide(issue: TrackerIssue, target: SubtaskPhase, comment: String?) {
        require(issue.fields.subtaskPhase == SubtaskPhase.TEST_DECISION_NEEDED.trackerValue) {
            "Deze subtaak wacht niet op een testbeslissing. Vernieuw de pagina."
        }
        require(target in setOf(SubtaskPhase.TEST_APPROVED, SubtaskPhase.TEST_REPAIR_REQUESTED)) {
            "Kies toch doorgaan of gericht herstel aanvragen."
        }
        val reason = comment?.trim().orEmpty()
        require(reason.isNotEmpty()) { "Geef een reden of concrete herstelopdracht." }
        val parentKey = requireNotNull(tracker.parentStoryKey(issue.key)) { "Parent-story ontbreekt." }
        val story = requireNotNull(storyRuns.latestFor(parentKey)) { "Story-run ontbreekt." }
        check(agentRuns.activeRuns().none { it.storyRunId == story.id }) { "Er draait nog een agent voor deze story." }
        val latest = agentRuns.recentForRole(story.id, AgentRole.TESTER, Int.MAX_VALUE)
            .firstOrNull { it.subtaskKey == issue.key }
        if (target == SubtaskPhase.TEST_APPROVED) {
            val sha = requireNotNull(latest?.checkoutCommitSha?.takeIf(String::isNotBlank)) {
                "Het testrapport bevat geen geverifieerde commit. Laat eerst opnieuw testen."
            }
            check(latest.endedAt != null) { "De testrun is nog niet afgerond." }
            val branch = requireNotNull(story.branchName) { "Storybranch ontbreekt." }
            check(github.latestCommitSha(story.targetRepo, branch) == sha) {
                "De storybranch is gewijzigd of niet bereikbaar. Laat de huidige versie eerst opnieuw testen."
            }
        }
        check(tracker.getIssue(issue.key).fields.subtaskPhase == SubtaskPhase.TEST_DECISION_NEEDED.trackerValue) {
            "De testfase is ondertussen veranderd. Vernieuw de pagina."
        }
        val decision = if (target == SubtaskPhase.TEST_APPROVED) "Toch doorgaan" else "Gericht herstel aanvragen"
        tracker.postComment(issue.key,
            "[TEST DECISION] $decision\n" +
                "Testrun: ${latest?.id ?: "onbekend"}; beoordeelde commit: ${latest?.checkoutCommitSha ?: "onbekend"}.\n" +
                "Menselijke reden/opdracht: $reason\nHet oorspronkelijke testrapport blijft ongewijzigd.")
        tracker.updateIssueFields(issue.key, TrackerFieldUpdate.of(
            TrackerField.SUBTASK_PHASE to target.trackerValue,
            TrackerField.ERROR to null,
            TrackerField.PAUSED to false,
            TrackerField.RETRY_AFTER to null,
        ))
    }
}
