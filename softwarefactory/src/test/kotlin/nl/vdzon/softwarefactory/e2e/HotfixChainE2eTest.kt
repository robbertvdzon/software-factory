package nl.vdzon.softwarefactory.e2e

import com.fasterxml.jackson.databind.node.IntNode
import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SF-1959 — end-to-end dekking van de **hotfix-keten**: een story met `Hotfix = true` slaat
 * refine/plan/review/test/summary/documentatie én de manual-approve-poort over en draait exact
 * `hotfix → merge → deploy` met één DEVELOPER-run.
 *
 * De keten is dezelfde echte Spring-app als in [FullRefineToDevelopE2eTest] (alleen de buitenranden
 * vervangen): de scripted developer rapporteert het `github-pr`-event, waarna de merge-subtaak echt
 * squash-merget op de [LocalGitRemote] en de deploy-subtaak de `Skip`-route volgt.
 *
 * De regressie "een niet-hotfix-story doorloopt exact de bestaande keten" (AC 10) staat in
 * [FullRefineToDevelopE2eTest] en [ChainCompositionE2eTest]; die zijn ongewijzigd gebleven.
 */
class HotfixChainE2eTest : E2eTestBase() {

    @Test
    fun `hotfix-story loopt van start tot en met deploy zonder review-, test-, summary-, documentation- of manual-approve-subtaak`() {
        runtime.script.apply {
            developerAsksQuestion = false
            developerReportsPullRequest = true
        }
        val await = awaiter(Duration.ofSeconds(180))
        val story = "${state.projectKey}-500"
        // Goedkeuring=elke-stap: bewijst dat de hotfix-keten ApprovalMode volledig negeert (AC 4/5).
        createStory(story, autoApprove = false, hotfix = true)

        // AC 3: geen refine/plan — de story gaat direct naar in-progress.
        await.awaitStoryPhase(story, "in-progress")
        await.awaitSubtasksCreated(story, 3)

        // AC 4: exact drie subtaken, in deze volgorde, en niets anders.
        assertEquals(
            listOf("hotfix", "merge", "deploy"),
            state.childrenOf(story).map { it.fields.subtaskType },
            "hotfix-keten hoort exact [hotfix, merge, deploy] te zijn",
        )

        // AC 5: developer-only; `developed` gaat onvoorwaardelijk door naar `hotfix-approved`.
        val hotfix = childOfType(story, "hotfix")
        await.awaitSubtaskPhase(hotfix.key, "hotfix-approved")

        // AC 7: de bestaande merge- en deploy-subtaken lopen ongewijzigd door; story naar Done.
        await.awaitSubtaskPhase(childOfType(story, "merge").key, "merge-approved")
        await.awaitSubtaskPhase(childOfType(story, "deploy").key, "deploy-approved")
        await.awaitIssueState(story, "Done")

        // Er heeft nooit een andere rol dan de developer gedraaid voor deze story.
        listOf(AgentRole.REFINER, AgentRole.PLANNER, AgentRole.REVIEWER, AgentRole.TESTER, AgentRole.SUMMARIZER, AgentRole.DOCUMENTER)
            .forEach { role ->
                assertEquals(0, dispatchCount(story, role), "$role mag niet draaien op een hotfix-story")
            }
        assertEquals(1, dispatchCount(story, AgentRole.DEVELOPER), "precies één developer-run")
        // AC 3, machinaal: de story is nooit in refining/planning geweest.
        assertEquals(0, state.fieldValueCount(story, "Story Phase", "refining"))
        assertEquals(0, state.fieldValueCount(story, "Story Phase", "planning"))
    }

    @Test
    fun `hotfix-story met vragen aan stelt een vraag en loopt na beantwoording door`() {
        runtime.script.apply {
            developerAsksQuestion = true
            developerReportsPullRequest = true
        }
        val ui = loginUi()
        val await = awaiter(Duration.ofSeconds(120))
        val story = "${state.projectKey}-510"
        createStory(story, hotfix = true)

        await.awaitStoryPhase(story, "in-progress")
        await.awaitSubtasksCreated(story, 3)
        val hotfix = childOfType(story, "hotfix")

        await.awaitSubtaskPhase(hotfix.key, "developed-with-questions")
        ui.answerSubtask(hotfix.key, "variant A, graag", phase = "development-questions-answered")
        await.awaitSubtaskPhase(hotfix.key, "hotfix-approved")

        assertEquals(2, dispatchCount(story, AgentRole.DEVELOPER), "developer: vraag + afronden")
    }

    @Test
    fun `hotfix-story met vragen uit levert een CLARIFICATION-error`() {
        runtime.script.developerAsksQuestion = true
        val await = awaiter(Duration.ofSeconds(120))
        val story = "${state.projectKey}-520"
        createStory(story, hotfix = true)
        state.setEnumField(story, "QuestionsAllowed", "false")

        await.awaitStoryPhase(story, "in-progress")
        await.awaitSubtasksCreated(story, 3)
        val hotfix = childOfType(story, "hotfix")

        await.awaitErrorContains(hotfix.key, "[CLARIFICATION]")
        assertNull(
            state.issue(childOfType(story, "merge").key)?.fields?.subtaskPhase,
            "een geblokkeerde hotfix-subtaak mag de merge nooit starten",
        )
    }

    @Test
    fun `rode projecttests laten de hotfix nooit approven en starten merge en deploy niet`() {
        // De deterministische verificatie-poort (AgentCli -> TesterVerificationRunner) overruled de
        // developer naar `development-rejected` met een [FACTORY VERIFICATION]-diagnose; die blijft
        // rood, dus de loopback loopt in de cap en de subtaak eindigt in Error.
        runtime.script.apply {
            developerAsksQuestion = false
            developerVerificationFails = true
        }
        val await = awaiter(Duration.ofSeconds(180))
        val story = "${state.projectKey}-530"
        createStory(story, hotfix = true)

        await.awaitStoryPhase(story, "in-progress")
        await.awaitSubtasksCreated(story, 3)
        val hotfix = childOfType(story, "hotfix")
        // Cap laag zetten zodat de loop deterministisch (en snel) in Error eindigt.
        state.setRawField(hotfix.key, "AI Max Developer Loopbacks", IntNode.valueOf(1))

        await.awaitErrorContains(hotfix.key, "Developer-loopback cap bereikt")

        assertEquals(
            0,
            state.fieldValueCount(hotfix.key, "Subtask Phase", "hotfix-approved"),
            "een rode verificatie mag nooit tot hotfix-approved leiden",
        )
        assertTrue(
            state.fieldValueCount(hotfix.key, "Subtask Phase", "development-rejected") > 0,
            "de rode uitkomst hoort als development-rejected op de subtaak te staan",
        )
        assertNull(state.issue(childOfType(story, "merge").key)?.fields?.subtaskPhase, "merge mag niet starten")
        assertNull(state.issue(childOfType(story, "deploy").key)?.fields?.subtaskPhase, "deploy mag niet starten")
    }
}
